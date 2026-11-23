package com.souvik.videocompressor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
    private Transformer transformer;
    private boolean running, cancelled, saving;
    private int index, okCount;
    private long t0, tJob;
    private File tmpOut;
    // options captured at Start
    private boolean audioMode, mute, h265;
    private int resH, rotDeg;
    private double qFactor;
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
        qFactor = q == 0 ? 0.6 : (q == 1 ? 0.4 : 0.25);        // share of the source VIDEO bitrate
        audioBps = q == 0 ? 96_000 : (q == 1 ? 64_000 : 48_000);
        int[] heights = {0, 1080, 720, 480, 360};
        resH = heights[spRes.getSelectedItemPosition()];
        int[] rots = {0, 270, 180, 90};   // Media3 rotates counter-clockwise
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
                if (transformer != null) transformer.cancel();
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
        } catch (Exception e) {
            jobFailed(it, String.valueOf(e.getMessage()));
        }
    }

    private void buildAndStart(final Item it) {
        // --- read source info (for a sensible target bitrate)
        int w = 0, h = 0, rot = 0;
        long br = 0, durMs = 0;
        int fps = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, it.uri);
            w = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            h = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            rot = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            br = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            durMs = toLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            fps = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        int dispH = (rot == 90 || rot == 270) ? w : h;     // height as seen by the viewer
        if (rotDeg == 90 || rotDeg == 270) dispH = (rot == 90 || rot == 270) ? h : w;
        int dispW = (rot == 90 || rot == 270) ? h : w;     // width as seen by the viewer
        if (rotDeg == 90 || rotDeg == 270) dispW = (rot == 90 || rot == 270) ? w : h;

        MediaItem.Builder mib = new MediaItem.Builder().setUri(it.uri);
        if (startMs > 0 || endMs > 0) {
            MediaItem.ClippingConfiguration.Builder cc = new MediaItem.ClippingConfiguration.Builder();
            if (startMs > 0) cc.setStartPositionMs(startMs);
            if (endMs > 0) cc.setEndPositionMs(endMs);
            mib.setClippingConfiguration(cc.build());
        }

        EditedMediaItem.Builder emb = new EditedMediaItem.Builder(mib.build());
        Transformer.Builder tb = new Transformer.Builder(this).addListener(new Transformer.Listener() {
            @Override
            public void onCompleted(Composition composition, ExportResult exportResult) {
                if (!cancelled) jobDone(it);
            }

            @Override
            public void onError(Composition composition, ExportResult exportResult, ExportException e) {
                if (!cancelled) jobFailed(it, "Failed: " + e.getErrorCodeName());
            }
        });

        if (audioMode) {
            emb.setRemoveVideo(true);
            tb.setAudioMimeType(MimeTypes.AUDIO_AAC);
        } else {
            List<Effect> fx = new ArrayList<>();
            if (rotDeg != 0) {
                fx.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(rotDeg).build());
            }
            int outH = dispH;
            if (resH > 0 && dispH > resH) {
                fx.add(Presentation.createForHeight(resH));
                outH = resH;
            }
            // Resampling to 32 kHz forces the audio to be re-encoded (at a low bitrate) instead of copied
            SonicAudioProcessor sonic = new SonicAudioProcessor();
            sonic.setOutputSampleRateHz(32000);
            emb.setEffects(new Effects(Collections.<AudioProcessor>singletonList(sonic), fx));
            if (mute) emb.setRemoveAudio(true);

            // Real average bitrate of the file (size / duration). The "bitrate" tag in the metadata is
            // often missing or wrong, which made the old guess (2 Mbps) far too high for small files.
            long srcBps = (durMs > 0 && it.size > 0) ? it.size * 8000L / durMs : br;
            if (srcBps <= 0) srcBps = 2_000_000L;
            double audioEst = Math.min(128_000, srcBps * 0.25);       // audio part of the source bitrate
            double base = Math.max(srcBps - audioEst, 100_000);         // video part
            double scale = dispH > 0 ? Math.pow((double) outH / dispH, 2) : 1.0;
            double bps = base * qFactor * scale * (h265 ? 0.7 : 1.0);
            int bitrate = (int) Math.max(120_000, Math.min(bps, 50_000_000));
            it.durMs = durMs;
            it.srcBps = srcBps;
            it.tgtBps = bitrate;

            // A phone's hardware encoder cannot hold a very low bitrate for a big picture: it quietly
            // produces MORE than asked. Estimate that, and don't waste minutes when the file is already
            // as small as this picture size allows.
            double fpsUse = fps > 0 ? Math.min(fps, 30) : 30;
            double outW = dispH > 0 ? (double) dispW * outH / dispH : 0;
            double floorBps = outW * outH * fpsUse * (h265 ? 0.03 : 0.045);
            long estTotal = (long) ((Math.max(bitrate, floorBps) + (mute ? 0 : audioBps)) * durMs / 8000.0);
            boolean plainCompress = rotDeg == 0 && !mute && startMs == 0 && endMs == 0;
            if (durMs > 0 && it.size > 0 && plainCompress && estTotal >= it.size * 0.9) {
                it.status = "Skipped - already very small (about " + (srcBps / 1000) + " kbps). "
                        + "Compressing again would not make it smaller. Try a lower resolution (e.g. 480p).";
                refresh(it);
                index++;
                startNext();
                return;
            }

            tb.setVideoMimeType(h265 ? MimeTypes.VIDEO_H265 : MimeTypes.VIDEO_H264);
            tb.setEncoderFactory(new DefaultEncoderFactory.Builder(this)
                    .setRequestedAudioEncoderSettings(
                            new AudioEncoderSettings.Builder().setBitrate(audioBps).build())
                    .setRequestedVideoEncoderSettings(
                            new VideoEncoderSettings.Builder()
                                    .setBitrate(bitrate)
                                    // CBR makes the phone's encoder really stick to the target size
                                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                                    .setiFrameIntervalSeconds(4f)
                                    .build())
                    .build());
        }
        transformer = tb.build();
        transformer.start(emb.build(), tmpOut.getAbsolutePath());
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
                rates = String.format(Locale.US, " [source %d kbps, asked %d kbps, got %d kbps]",
                        it.srcBps / 1000, it.tgtBps / 1000, big * 8000L / it.durMs / 1000);
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

    private void jobFailed(Item it, String msg) {
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
                if (!saving && transformer != null) {
                    ProgressHolder ph = new ProgressHolder();
                    if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) pct = ph.progress;
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(tick);
        try {
            if (transformer != null && running) transformer.cancel();
        } catch (Exception ignored) {
        }
        io.shutdown();
    }
}
