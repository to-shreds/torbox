package app.jabs.apkcatcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;

/** No layout or launcher: receive a GitHub share and hand the APK to the system installer. */
@SuppressWarnings("deprecation")
public final class CatchActivity extends Activity {
    private static final int ALLOW_SOURCE = 10;
    private static final int INSTALL = 11;
    private static final int READY = 0;
    private static final int SETTINGS = 1;
    private static final int INSTALLER = 2;

    private static final class SharedSource {
        final Uri uri;
        final String url;
        SharedSource(Uri uri, String url) { this.uri = uri; this.url = url; }
        static SharedSource file(Uri uri) { return new SharedSource(uri, null); }
        static SharedSource github(String url) { return new SharedSource(null, url); }
    }

    private File apk;
    private int phase = READY;
    private Thread worker;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getString("job") != null) {
            try {
                String id = state.getString("job");
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
                apk = new File(new File(ApkProvider.root(getCacheDir()), id), "package.apk");
                phase = state.getInt("phase", READY);
                if (!apk.isFile()) { stop("The temporary APK expired. Share the file again."); return; }
                if (phase == READY) continueInstall();
                return;
            } catch (RuntimeException invalidState) {
                stop("Please share the file again.");
                return;
            }
        }

        final SharedSource source;
        try {
            source = sharedSource(getIntent());
        } catch (ApkArchive.Rejected failure) {
            stop(failure.getMessage());
            return;
        } catch (RuntimeException malformedShare) {
            stop("GitHub did not share a readable file or link.");
            return;
        }
        worker = new Thread(new Runnable() {
            @Override public void run() { prepare(source); }
        }, "apk-catcher-copy");
        worker.start();
    }

    private static void addUri(LinkedHashSet<Uri> files, Object value) {
        if (value instanceof Uri) files.add((Uri) value);
    }

    private SharedSource sharedSource(Intent intent) throws ApkArchive.Rejected {
        if (intent == null || (!Intent.ACTION_SEND.equals(intent.getAction())
                && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
            throw new ApkArchive.Rejected("Share an APK from GitHub to APK Catcher.");
        }

        LinkedHashSet<Uri> sharedUris = new LinkedHashSet<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Parcelable> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) for (Parcelable stream : streams) addUri(sharedUris, stream);
        } else {
            addUri(sharedUris, intent.getParcelableExtra(Intent.EXTRA_STREAM));
        }
        ClipData clip = intent.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) addUri(sharedUris, clip.getItemAt(i).getUri());
        addUri(sharedUris, intent.getData());

        if (sharedUris.size() > 1) throw new ApkArchive.Rejected("Share one APK or artifact ZIP at a time.");
        if (sharedUris.size() == 1) {
            Uri source = sharedUris.iterator().next();
            if ("content".equalsIgnoreCase(source.getScheme()) && !ApkProvider.AUTHORITY.equals(source.getAuthority())) {
                return SharedSource.file(source);
            }
            String url = GithubShare.extractUrl(source.toString());
            if (url != null) return SharedSource.github(url);
        }

        String url = GithubShare.extractUrl(intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
        if (url != null) return SharedSource.github(url);
        throw new ApkArchive.Rejected("GitHub did not share the APK file or a usable GitHub link.");
    }

    private InputStream open(SharedSource source) throws IOException {
        if (source.uri != null) {
            InputStream in = getContentResolver().openInputStream(source.uri);
            if (in == null) throw new IOException("No file stream");
            return in;
        }
        return GithubShare.open(source.url);
    }

    private void prepare(SharedSource source) {
        File job = null;
        try {
            File root = ApkProvider.root(getCacheDir());
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("No temporary storage");
            cleanOldFiles(root);
            job = new File(root, UUID.randomUUID().toString());
            if (!job.mkdir()) throw new IOException("No temporary storage");
            final File prepared;
            try (InputStream in = open(source)) {
                prepared = ApkArchive.prepare(in, job);
            }
            PackageInfo info = getPackageManager().getPackageArchiveInfo(prepared.getPath(), 0);
            if (info == null || info.applicationInfo == null || info.packageName == null) {
                throw new ApkArchive.Rejected("Android does not recognize this as an APK.");
            }
            if (destroyed || Thread.currentThread().isInterrupted()) { deleteJob(job); return; }
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed || isFinishing()) { deleteJob(prepared.getParentFile()); return; }
                    apk = prepared;
                    continueInstall();
                }
            });
        } catch (final ApkArchive.Rejected rejected) {
            deleteJob(job);
            postError(rejected.getMessage());
        } catch (SecurityException denied) {
            deleteJob(job);
            postError("File access was denied.");
        } catch (IOException | RuntimeException failure) {
            deleteJob(job);
            postError(source.url != null
                    ? "Could not fetch the GitHub file. Check your connection and try Share again."
                    : "Could not read the shared file. Try Share again.");
        }
    }

    private void continueInstall() {
        if (apk == null || !apk.isFile()) { stop("Please share the file again."); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            phase = SETTINGS;
            Toast.makeText(this, "Allow APK Catcher, then go back to install.", Toast.LENGTH_LONG).show();
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), ALLOW_SOURCE);
            } catch (ActivityNotFoundException | SecurityException blocked) {
                stop("Allow APK Catcher under Settings > Install unknown apps, then share again.");
            }
            return;
        }
        Uri uri = new Uri.Builder().scheme("content").authority(ApkProvider.AUTHORITY)
                .appendPath(apk.getParentFile().getName()).appendPath("package.apk").build();
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        install.setDataAndType(uri, ApkProvider.MIME);
        install.setClipData(ClipData.newRawUri("APK", uri));
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        phase = INSTALLER;
        try {
            startActivityForResult(install, INSTALL);
        } catch (ActivityNotFoundException | SecurityException blocked) {
            stop("Android blocked the installer. Check installation restrictions.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ALLOW_SOURCE) {
            phase = READY;
            if (getPackageManager().canRequestPackageInstalls()) continueInstall();
            else stop("Installation permission was not enabled.");
        } else if (request == INSTALL) {
            finish();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (apk != null) {
            state.putString("job", apk.getParentFile().getName());
            state.putInt("phase", phase);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    private void postError(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (!destroyed && !isFinishing()) stop(message); }
        });
    }

    private void stop(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private static void cleanOldFiles(File root) {
        File[] jobs = root.listFiles();
        if (jobs == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File job : jobs) {
            if (job.isDirectory() && job.lastModified() < cutoff) deleteJob(job);
        }
    }

    private static void deleteJob(File job) {
        if (job == null) return;
        new File(job, "incoming.bin").delete();
        new File(job, "package.apk").delete();
        job.delete();
    }
}
