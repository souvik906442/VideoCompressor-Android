package com.souvik.videocompressor;

import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Video Compressor for Android.
 *
 * Videos are chosen with the system picker (no storage permission needed), so the list can never
 * be "empty because permission/loading raced". The screen is never recreated by split-screen or
 * rotation (see configChanges in the manifest), so the queue is always kept.
 */
public class MainActivity extends AppCompatActivity {

    static class Item {
        Uri uri;
        String name;
        long size;
        String status = "Queued";
        TextView tv;
        long durMs, srcBps, tgtBps;   // for the "source / target / result" bitrate report
    }

    private final List<Item> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private LinearLayout llFiles;
    private Button btnAdd, btnClear, btnStart, btnCancel;
    private RadioButton rbAudio;
    private Spinner spQuality, spCodec, spRes, spRot;
    private CheckBox cbMute;
    private EditText etStart, etEnd;
    private ProgressBar progress;
    private TextView tvStatus, tvOut;
    private SharedPreferences prefs;
    private volatile Uri outTree;   // user-chosen save folder (null = Movies/VideoCompressor)

    // run state
    private FFmpegSession session;
    private volatile float jobProgress;   // 0..1 of the current file
    private boolean running, cancelled, saving;
    private int index, okCount;
    private long t0, tJob;
    private File tmpOut;
    private ParcelFileDescriptor inPfd;   // input kept open while ffmpeg reads it
    // options captured at Start
    private boolean audioMode, mute, h265;
    private int resH, rotDeg;
    private int crf;        // x264 quality: lower = better picture, bigger file
    private int audioBps;
    private long startMs, endMs;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (uris != null && !uris.isEmpty()) addUris(uris);
            });

    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                outTree = uri;
                prefs.edit().putString("out_tree", uri.toString()).apply();
                updateOutLabel();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        installCrashLogger();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        llFiles = findViewById(R.id.llFiles);
        btnAdd = findViewById(R.id.btnAdd);
        btnClear = findViewById(R.id.btnClear);
        btnStart = findViewById(R.id.btnStart);
        btnCancel = findViewById(R.id.btnCancel);
        rbAudio = findViewById(R.id.rbAudio);
        cbMute = findViewById(R.id.cbMute);
        etStart = findViewById(R.id.etStart);
        etEnd = findViewById(R.id.etEnd);
        progress = findViewById(R.id.progress);
        tvStatus = findViewById(R.id.tvStatus);
        tvOut = findViewById(R.id.tvOut);
        prefs = getSharedPreferences("vc", MODE_PRIVATE);
        String saved = prefs.getString("out_tree", null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            for (UriPermission up : getContentResolver().getPersistedUriPermissions()) {
                if (up.getUri().equals(u) && up.isWritePermission()) outTree = u;
            }
        }
        updateOutLabel();
        ((Button) findViewById(R.id.btnBrowse)).setOnClickListener(v -> folderPicker.launch(null));
        ((Button) findViewById(R.id.btnDefaultOut)).setOnClickListener(v -> {
            outTree = null;
            prefs.edit().remove("out_tree").apply();
            updateOutLabel();
        });

        spQuality = setupSpinner(R.id.spQuality, new String[]{"High", "Medium", "Low"}, 1);
        spCodec = setupSpinner(R.id.spCodec, new String[]{"H.264 (most compatible)", "H.265 (smaller)"}, 0);
        spRes = setupSpinner(R.id.spRes, new String[]{"Original", "1080p", "720p", "480p", "360p"}, 0);
        spRot = setupSpinner(R.id.spRot, new String[]{"None", "90° right", "180°", "90° left"}, 0);

        btnAdd.setOnClickListener(v -> picker.launch(new String[]{"video/*"}));
        btnClear.setOnClickListener(v -> {
            if (running) return;
            items.clear();
            llFiles.removeAllViews();
        });
        btnStart.setOnClickListener(v -> start());
        btnCancel.setOnClickListener(v -> cancelAll());
        ((android.widget.RadioGroup) findViewById(R.id.rgMode)).setOnCheckedChangeListener(
                (g, id) -> updateModeUi());

        handleIntent(getIntent());
        showLastCrash();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent i) {
        if (i == null || i.getAction() == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (!uris.isEmpty()) addUris(uris);
    }

    private Spinner setupSpinner(int id, String[] values, int selected) {
        Spinner s = findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
        return s;
    }

    private void updateOutLabel() {
        Uri t = outTree;
        if (t == null) {
            tvOut.setText("Save to: Movies/VideoCompressor (default)");
            return;
        }
        String name = "chosen folder";
        try (Cursor c = getContentResolver().query(
                DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } catch (Exception ignored) {
        }
        tvOut.setText("Save to: " + name);
    }

    private void updateModeUi() {
        boolean video = !rbAudio.isChecked();
        spQuality.setEnabled(video);
        spCodec.setEnabled(video);
        spRes.setEnabled(video);
        spRot.setEnabled(video);
        cbMute.setEnabled(video);
    }

    // ------------------------------------------------------------ file list
    private void addUris(List<Uri> uris) {
        for (Uri u : uris) {
            boolean dup = false;
            for (Item e : items) if (e.uri.equals(u)) dup = true;
            if (dup) continue;
            final Item it = new Item();
            it.uri = u;
            it.name = "video";
            it.size = 0;
            try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int s = c.getColumnIndex(OpenableColumns.SIZE);
                    if (n >= 0 && c.getString(n) != null) it.name = c.getString(n);
                    if (s >= 0) it.size = c.getLong(s);
                }
            } catch (Exception ignored) {
            }
            it.tv = new TextView(this);
            it.tv.setPadding(0, 12, 0, 12);
            it.tv.setOnClickListener(v -> {
                if (running) return;
                items.remove(it);
                llFiles.removeView(it.tv);
            });
            llFiles.addView(it.tv);
            refresh(it);
            items.add(it);
        }
    }

    private void refresh(Item it) {
        it.tv.setText(it.name + "\n" + human(it.size) + "  •  " + it.status);
    }

    // ------------------------------------------------------------- running
    private void start() {
        if (running) return;
        if (items.isEmpty()) {
            Toast.makeText(this, "Add at least one video first.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startMs = parseTime(etStart.getText().toString());
            endMs = parseTime(etEnd.getText().toString());
        } catch (Exception e) {
            Toast.makeText(this, "Trim times must look like 90 or 1:30 or 0:01:30.", Toast.LENGTH_LONG).show();
            return;
        }
        if (endMs > 0 && endMs <= startMs) {
            Toast.makeText(this, "Trim end must be after trim start.", Toast.LENGTH_LONG).show();
            return;
        }
        audioMode = rbAudio.isChecked();
        mute = cbMute.isChecked();
        h265 = spCodec.getSelectedItemPosition() == 1;
        int q = spQuality.getSelectedItemPosition();
        crf = q == 0 ? 24 : (q == 1 ? 28 : 31);                  // constant-quality encoding (like the original app)
        audioBps = q == 0 ? 128_000 : (q == 1 ? 96_000 : 64_000);
        int[] heights = {0, 1080, 720, 480, 360};
        resH = heights[spRes.getSelectedItemPosition()];
        int[] rots = {0, 90, 180, 270};   // degrees clockwise
        rotDeg = rots[spRot.getSelectedItemPosition()];

        for (Item it : items) {
            it.status = "Queued";
            refresh(it);
        }
        cancelled = false;
        saving = false;
        running = true;
        index = 0;
        okCount = 0;
        t0 = System.currentTimeMillis();
        progress.setProgress(0);
        btnStart.setEnabled(false);
        btnCancel.setEnabled(true);
        btnAdd.setEnabled(false);
        btnClear.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.post(tick);
        startNext();
    }

    private void cancelAll() {
        if (!running) return;
        cancelled = true;
        tvStatus.setText("Cancelling…");
        if (!saving) {
            try {
                if (session != null) FFmpegKit.cancel(session.getSessionId());
            } catch (Exception ignored) {
            }
            if (index < items.size()) {
                items.get(index).status = "Cancelled";
                refresh(items.get(index));
            }
            finishAll();
        }
    }

    private void startNext() {
        if (cancelled || index >= items.size()) {
            finishAll();
            return;
        }
        final Item it = items.get(index);
        it.status = "Starting…";
        refresh(it);
        tJob = System.currentTimeMillis();
        tmpOut = new File(getCacheDir(), "out_" + System.nanoTime() + (audioMode ? ".m4a" : ".mp4"));
        try {
            buildAndStart(it);
        } catch (Throwable e) {   // includes UnsatisfiedLinkError etc. from the ffmpeg native library
            jobFailed(it, "Error: " + e);
        }
    }

    private void buildAndStart(final Item it) {
        // --- duration of the source (for progress + a size cap)
        long durMs = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, it.uri);
            durMs = toLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        long effMs = durMs;
        if (startMs > 0 && effMs > 0) effMs = Math.max(effMs - startMs, 0);
        if (endMs > 0) effMs = effMs > 0 ? Math.min(effMs, endMs - startMs) : endMs - startMs;
        final long progressMs = effMs;
        it.durMs = durMs;
        it.srcBps = (durMs > 0 && it.size > 0) ? it.size * 8000L / durMs : 0;

        // open the video ourselves and hand ffmpeg a plain /proc/self/fd path (more robust than the saf: protocol)
        String inArg = null;
        closeIn();
        try {
            inPfd = getContentResolver().openFileDescriptor(it.uri, "r");
            if (inPfd != null) inArg = "/proc/self/fd/" + inPfd.getFd();
        } catch (Exception ignored) {
        }
        if (inArg == null) inArg = FFmpegKitConfig.getSafParameterForRead(this, it.uri);
        if (inArg == null) {
            jobFailed(it, "Cannot open this video");
            return;
        }

        List<String> a = new ArrayList<>();
        a.add("-y");
        a.add("-hide_banner");
        if (startMs > 0) {
            a.add("-ss");
            a.add(secs(startMs));
        }
        a.add("-i");
        a.add(inArg);
        if (endMs > 0) {
            a.add("-t");
            a.add(secs(endMs - startMs));
        }

        if (audioMode) {
            a.add("-vn");
            a.add("-c:a");
            a.add("aac");
            a.add("-b:a");
            a.add("128k");
        } else {
            a.add("-map");
            a.add("0:v:0");
            if (!mute) {
                a.add("-map");
                a.add("0:a?");
            }
            // picture filters (ffmpeg already turns the picture upright before them)
            List<String> vf = new ArrayList<>();
            if (rotDeg == 90) vf.add("transpose=1");
            else if (rotDeg == 270) vf.add("transpose=2");
            else if (rotDeg == 180) vf.add("hflip,vflip");
            if (resH > 0) vf.add("scale=-2:'min(ih," + resH + ")'");
            if (!vf.isEmpty()) {
                a.add("-vf");
                a.add(android.text.TextUtils.join(",", vf));
            }
            // the same recipe as the original app: x264, constant quality (CRF), preset fast
            if (h265) {
                a.add("-c:v");
                a.add("libx265");
                a.add("-crf");
                a.add(String.valueOf(crf + 2));
                a.add("-preset");
                a.add("fast");
                a.add("-tag:v");
                a.add("hvc1");
            } else {
                a.add("-c:v");
                a.add("libx264");
                a.add("-crf");
                a.add(String.valueOf(crf));
                a.add("-preset");
                a.add("fast");
                a.add("-profile:v");
                a.add("high");
            }
            a.add("-pix_fmt");
            a.add("yuv420p");
            // safety net: never let the picture use more bits than the original did
            if (it.srcBps > 0) {
                long cap = Math.max(100_000L, (long) (it.srcBps * 0.85) - (mute ? 0 : audioBps));
                a.add("-maxrate");
                a.add(String.valueOf(cap));
                a.add("-bufsize");
                a.add(String.valueOf(cap * 2));
            }
            if (mute) {
                a.add("-an");
            } else {
                a.add("-c:a");
                a.add("aac");
                a.add("-b:a");
                a.add(String.valueOf(audioBps));
            }
            a.add("-movflags");
            a.add("+faststart");
        }
        a.add(tmpOut.getAbsolutePath());

        jobProgress = 0f;
        session = FFmpegKit.executeWithArgumentsAsync(a.toArray(new String[0]),
                done -> handler.post(() -> {
                    closeIn();
                    if (cancelled) return;
                    if (ReturnCode.isSuccess(done.getReturnCode())) {
                        jobDone(it);
                    } else {
                        jobFailed(it, "Failed: " + lastLine(done.getOutput()));
                    }
                }),
                null,
                st -> {
                    double t = st.getTime();      // position written so far, in ms
                    if (progressMs > 0 && t > 0) jobProgress = (float) Math.min(t / progressMs, 1.0);
                });
    }

    private void jobDone(final Item it) {
        final File src = tmpOut;
        final boolean audio = audioMode;
        // compress gave a bigger file than the original -> don't keep it
        if (!audio && startMs == 0 && endMs == 0 && it.size > 0 && src.length() >= it.size) {
            long big = src.length();
            src.delete();
            String rates = "";
            if (it.durMs > 0) {
                rates = String.format(Locale.US, " [source %d kbps, result %d kbps] - try Low quality or a smaller resolution",
                        it.srcBps / 1000, big * 8000L / it.durMs / 1000);
            }
            it.status = "Skipped - result " + human(big) + " is not smaller than the original" + rates;
            refresh(it);
            index++;
            startNext();
            return;
        }
        saving = true;
        it.status = "Saving…";
        refresh(it);
        io.execute(() -> {
            long outSize = -1;
            String err = null;
            try {
                outSize = saveToMediaStore(src, it.name, audio);
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final long fOut = outSize;
            final String fErr = err;
            handler.post(() -> {
                saving = false;
                src.delete();
                if (fErr == null) {
                    okCount++;
                    String text = "Done - " + human(fOut);
                    if (!audio && it.size > 0 && startMs == 0 && endMs == 0) {
                        long pct = (fOut - it.size) * 100 / it.size;
                        text += String.format(Locale.US, " (%+d%%)", pct);
                    }
                    text += " in " + hms((System.currentTimeMillis() - tJob) / 1000);
                    it.status = text;
                } else {
                    it.status = "Could not save: " + fErr;
                }
                refresh(it);
                index++;
                startNext();
            });
        });
    }

    private void closeIn() {
        try {
            if (inPfd != null) inPfd.close();
        } catch (Exception ignored) {
        }
        inPfd = null;
    }

    private void jobFailed(Item it, String msg) {
        closeIn();
        if (tmpOut != null) tmpOut.delete();
        it.status = msg;
        refresh(it);
        index++;
        startNext();
    }

    private void finishAll() {
        running = false;
        handler.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnStart.setEnabled(true);
        btnCancel.setEnabled(false);
        btnAdd.setEnabled(true);
        btnClear.setEnabled(true);
        String total = hms((System.currentTimeMillis() - t0) / 1000);
        if (cancelled) {
            tvStatus.setText("Cancelled  -  time " + total);
        } else {
            progress.setProgress(1000);
            tvStatus.setText("Finished: " + okCount + " of " + items.size() + " done  -  total time " + total
                    + "\n" + (outTree == null
                    ? "Saved in " + (audioMode ? "Music" : "Movies") + "/VideoCompressor"
                    : "Saved in your chosen folder"));
        }
    }

    /** Updates progress + elapsed time (hh:mm:ss) twice a second while running. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (index < items.size()) {
                Item it = items.get(index);
                int pct = 0;
                if (!saving && session != null) {
                    pct = Math.max(0, Math.min(99, (int) (jobProgress * 100)));
                    it.status = pct + "%";
                    refresh(it);
                }
                progress.setProgress((int) ((index + pct / 100f) / items.size() * 1000));
                tvStatus.setText(String.format(Locale.US, "[%d/%d] %s    Elapsed %s",
                        index + 1, items.size(), it.name, hms((System.currentTimeMillis() - t0) / 1000)));
            }
            handler.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------- helpers
    private long saveToMediaStore(File src, String sourceName, boolean audio) throws Exception {
        String base = sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String outName = audio ? base + ".m4a" : base + "_compressed.mp4";

        ContentResolver cr = getContentResolver();
        Uri tree = outTree;
        if (tree != null) {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri d = DocumentsContract.createDocument(cr, parent, audio ? "audio/mp4" : "video/mp4", outName);
            if (d == null) throw new Exception("cannot create file in chosen folder");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(d)) {
                if (out == null) throw new Exception("cannot open output");
                byte[] b = new byte[256 * 1024];
                int k;
                while ((k = in.read(b)) > 0) out.write(b, 0, k);
            } catch (Exception e) {
                try {
                    DocumentsContract.deleteDocument(cr, d);
                } catch (Exception ignored) {
                }
                throw e;
            }
            return src.length();
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, audio ? "audio/mp4" : "video/mp4");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                audio ? "Music/VideoCompressor" : "Movies/VideoCompressor");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = audio
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri dst = cr.insert(collection, cv);
        if (dst == null) throw new Exception("MediaStore insert failed");
        try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(dst)) {
            if (out == null) throw new Exception("cannot open output");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            cr.delete(dst, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(dst, done, null, null);
        return src.length();
    }

    private static int toInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** milliseconds -> "12.345" seconds for ffmpeg */
    private static String secs(long ms) {
        return String.format(Locale.US, "%.3f", ms / 1000.0);
    }

    /** last non-empty line of the ffmpeg log, shortened */
    private static String lastLine(String log) {
        if (log == null) return "unknown error";
        String[] lines = log.trim().split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (!l.isEmpty()) return l.length() > 160 ? l.substring(l.length() - 160) : l;
        }
        return "unknown error";
    }

    private static long toLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseTime(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        double t = 0;
        for (String p : s.split(":")) t = t * 60 + Double.parseDouble(p.trim());
        return (long) (t * 1000);
    }

    static String hms(long sec) {
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60);
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024);
    }

    // ------------------------------------------------------------- crash report
    private File crashFile() {
        return new File(getFilesDir(), "last_crash.txt");
    }

    /** Java crashes: save the stack trace so the next launch can show it. */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                java.io.FileWriter fw = new java.io.FileWriter(crashFile());
                fw.write("Java crash on thread " + t.getName() + "\n" + sw);
                fw.close();
            } catch (Throwable ignored) {
            }
            if (old != null) old.uncaughtException(t, e);
        });
    }

    /** On launch: show the saved Java crash, or why the system says the last run died (native crash etc.). */
    private void showLastCrash() {
        String report = null;
        try {
            File f = crashFile();
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int k;
                    while ((k = in.read(b)) > 0) bo.write(b, 0, k);
                    report = bo.toString("UTF-8");
                }
                f.delete();
            }
        } catch (Throwable ignored) {
        }
        if (report == null && Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
                if (!l.isEmpty()) {
                    ApplicationExitInfo e = l.get(0);
                    long last = prefs.getLong("last_exit_ts", 0);
                    int r = e.getReason();
                    boolean bad = r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_CRASH
                            || r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_SIGNALED
                            || r == ApplicationExitInfo.REASON_LOW_MEMORY || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
                    if (bad && e.getTimestamp() > last) {
                        StringBuilder sb = new StringBuilder("Last run ended with reason code " + r
                                + " (" + e.getDescription() + "), status " + e.getStatus() + "\n");
                        if (r == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                            try (InputStream in = e.getTraceInputStream()) {
                                if (in != null) {
                                    // tombstone is binary protobuf: pull out the readable text (library names, signal)
                                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                                    byte[] b = new byte[4096];
                                    int k;
                                    while ((k = in.read(b)) > 0 && bo.size() < 400_000) bo.write(b, 0, k);
                                    byte[] d = bo.toByteArray();
                                    StringBuilder run = new StringBuilder();
                                    int lines = 0;
                                    for (int i = 0; i <= d.length && lines < 40; i++) {
                                        int c = i < d.length ? d[i] & 0xff : 0;
                                        if (c >= 32 && c < 127) {
                                            run.append((char) c);
                                        } else {
                                            String x = run.toString();
                                            if (x.length() >= 8 && (x.contains(".so") || x.contains("SIG") || x.contains("abort")
                                                    || x.contains("Abort") || x.contains("ffmpeg") || x.contains("Fatal"))) {
                                                sb.append(x).append('\n');
                                                lines++;
                                            }
                                            run.setLength(0);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        report = sb.toString();
                    }
                    prefs.edit().putLong("last_exit_ts", e.getTimestamp()).apply();
                }
            } catch (Throwable ignored) {
            }
        }
        if (report == null) return;
        final String text = report;
        new AlertDialog.Builder(this)
                .setTitle("App crashed last time")
                .setMessage(text)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(tick);
        try {
            if (session != null && running) FFmpegKit.cancel(session.getSessionId());
        } catch (Exception ignored) {
        }
        io.shutdown();
    }
}
