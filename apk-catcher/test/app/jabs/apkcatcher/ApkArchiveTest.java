package app.jabs.apkcatcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free tests; fake manifests only test archive routing, not Android validation. */
public final class ApkArchiveTest {
    private static int count;
    private static File root;
    private static byte[] apk;
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private static Map<String, byte[]> entries(String name, byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name, bytes);
        return entries;
    }
    private static File job() throws IOException {
        return Files.createTempDirectory(root.toPath(), "test-").toFile();
    }
    private static File prepare(byte[] input, File directory, long limit, int maxEntries) throws IOException {
        return ApkArchive.prepare(new ByteArrayInputStream(input), directory, limit, maxEntries);
    }
    private static void same(byte[] input, byte[] expected, String name) throws IOException {
        File directory = job();
        File actual = prepare(input, directory, ApkArchive.MAX_BYTES, ApkArchive.MAX_ENTRIES);
        if (!Arrays.equals(Files.readAllBytes(actual.toPath()), expected)) throw new AssertionError(name);
        if (!"package.apk".equals(actual.getName()) || !actual.getParentFile().equals(directory)) throw new AssertionError(name + " unsafe path");
        if (new File(directory, "incoming.bin").exists()) throw new AssertionError(name + " import not cleaned");
        System.out.println("PASS: " + name);
        count++;
    }
    private static void reject(byte[] bytes, long limit, int maxEntries, String contains, String name) throws IOException {
        File directory = job();
        try {
            prepare(bytes, directory, limit, maxEntries);
            throw new AssertionError(name + " incorrectly accepted");
        } catch (ApkArchive.Rejected expected) {
            if (!expected.getMessage().contains(contains)) throw new AssertionError(name + ": " + expected.getMessage());
            if (new File(directory, "incoming.bin").exists() || new File(directory, "package.apk").exists()) throw new AssertionError(name + " leftover file");
        }
        System.out.println("PASS: " + name);
        count++;
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("apk-catcher-test-").toFile();
        apk = zip(entries("AndroidManifest.xml", new byte[] {3,0,8,0}));
        same(apk, apk, "APK bytes accepted without a filename or MIME type");
        same(zip(entries("app-debug.apk", apk)), apk, "artifact ZIP with one APK");
        same(zip(entries("nested/deeper/APP.APK", apk)), apk, "nested path and uppercase APK suffix");
        same(zip(entries("../../outside.apk", apk)), apk, "entry path never used as destination");
        Map<String, byte[]> mixed = entries("notes.txt", new byte[]{1,2,3});
        mixed.put("build/output.apk", apk);
        same(zip(mixed), apk, "unrelated ZIP files ignored");
        Map<String, byte[]> bundledApk = entries("AndroidManifest.xml", new byte[]{3,0,8,0});
        bundledApk.put("assets/other.apk", apk);
        byte[] directWithAsset = zip(bundledApk);
        same(directWithAsset, directWithAsset, "APK containing an APK asset is not extracted");
        reject(new byte[0], 4096, 10, "empty", "empty file rejected");
        reject("https://github.com/test".getBytes("UTF-8"), 4096, 10, "readable", "URL text is not an APK");
        reject(zip(entries("file.txt", new byte[]{1})), 4096, 10, "not an APK", "non-APK ZIP rejected");
        Map<String, byte[]> multiple = entries("base.apk", apk);
        multiple.put("split.apk", apk);
        reject(zip(multiple), 4096, 10, "multiple APKs", "multiple and split APK bundles rejected");
        reject(zip(entries("pretend.apk", new byte[]{1,2,3})), 4096, 10, "readable", "fake nested APK rejected");
        reject(zip(entries("pretend.apk", zip(entries("not-manifest.txt", new byte[]{4})))), 4096, 10, "not an APK", "nested ZIP without manifest rejected");
        reject(apk, 8, 10, "too large", "incoming byte limit enforced");
        reject(zip(mixed), 4096, 1, "too many", "archive entry limit enforced");
        byte[] huge = new byte[500000];
        Arrays.fill(huge, (byte)42);
        reject(zip(entries("huge.apk", huge)), 4096, 10, "too large", "uncompressed ZIP size limit enforced");
        byte[] broken = Arrays.copyOf(apk, 20);
        reject(broken, 4096, 10, "readable", "truncated APK rejected");
        if (args.length > 0) {
            byte[] actual = Files.readAllBytes(new File(args[0]).toPath());
            same(actual, actual, "built APK preserved byte-for-byte");
            same(zip(entries("artifact/app.apk", actual)), actual, "built APK extracted byte-for-byte");
        }
        System.out.println("ALL " + count + " TESTS PASSED");
        // Only this test's generated temporary directory is removed.
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
