package app.jabs.apkcatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Bounded file handling; never uses a sender's filename as a destination path. */
final class ApkArchive {
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ENTRIES = 10000;

    static final class Rejected extends IOException {
        Rejected(String message) { super(message); }
    }

    private ApkArchive() {}

    static void copy(InputStream in, File target, long limit, CRC32 crc) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Cancelled");
                }
                if (n == 0) continue;
                if (total > limit - n) throw new Rejected("This file is too large (limit: 1 GB).");
                total += n;
                out.write(buffer, 0, n);
                if (crc != null) crc.update(buffer, 0, n);
            }
            if (total == 0) throw new Rejected("The shared file is empty.");
        }
    }

    /** Returns a new package.apk in the caller's private, unique job directory. */
    static File prepare(InputStream in, File job) throws IOException {
        return prepare(in, job, MAX_BYTES, MAX_ENTRIES);
    }

    static File prepare(InputStream in, File job, long limit, int entryLimit) throws IOException {
        File incoming = new File(job, "incoming.bin");
        File apk = new File(job, "package.apk");
        try {
            copy(in, incoming, limit, null);
            boolean direct;
            try (ZipFile zip = new ZipFile(incoming)) {
                ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
                direct = manifest != null && !manifest.isDirectory();
                if (!direct) {
                    if (zip.size() > entryLimit) throw new Rejected("This ZIP has too many files.");
                    ZipEntry candidate = null;
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                        if (candidate != null) {
                            throw new Rejected("This ZIP has multiple APKs. Share just the APK you want.");
                        }
                        candidate = entry;
                    }
                    if (candidate == null) throw new Rejected("This file is not an APK or a ZIP containing one.");
                    if (candidate.getSize() > limit) throw new Rejected("The APK in this ZIP is too large (limit: 1 GB).");
                    CRC32 crc = new CRC32();
                    try (InputStream content = zip.getInputStream(candidate)) {
                        copy(content, apk, limit, crc);
                    }
                    if (candidate.getCrc() >= 0 && candidate.getCrc() != crc.getValue()) {
                        throw new Rejected("The APK in this ZIP is damaged. Download it again.");
                    }
                    try (ZipFile inner = new ZipFile(apk)) {
                        ZipEntry innerManifest = inner.getEntry("AndroidManifest.xml");
                        if (innerManifest == null || innerManifest.isDirectory()) {
                            throw new Rejected("The file inside this ZIP is not an APK.");
                        }
                    }
                }
            }
            if (direct && !incoming.renameTo(apk)) throw new IOException("Could not prepare the APK");
            return apk;
        } catch (ZipException badZip) {
            apk.delete();
            throw new Rejected("This is not a readable APK or ZIP. Download the actual file first.");
        } catch (IOException | RuntimeException failure) {
            apk.delete();
            throw failure;
        } finally {
            incoming.delete();
        }
    }
}
