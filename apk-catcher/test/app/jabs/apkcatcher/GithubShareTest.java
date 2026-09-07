package app.jabs.apkcatcher;

public final class GithubShareTest {
    private static int count;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        System.out.println("PASS: " + name);
        count++;
    }

    public static void main(String[] args) throws Exception {
        String blob = "https://github.com/to-shreds/torbox/blob/main/TorBox-Drop-v2.0.2.apk";
        check(blob.equals(GithubShare.extractUrl(blob)), "plain GitHub share URL accepted");
        check(blob.equals(GithubShare.extractUrl("APK file: " + blob)), "URL extracted from share text");
        check(blob.equals(GithubShare.extractUrl("(" + blob + ")")), "share punctuation stripped");
        check(GithubShare.normalize(blob).toString().endsWith("?raw=1"), "GitHub blob view converted to raw download");
        String withQuery = blob + "?plain=1";
        check(GithubShare.normalize(withQuery).toString().endsWith("?plain=1&raw=1"), "raw flag preserves existing query");
        String raw = "https://raw.githubusercontent.com/to-shreds/torbox/main/file.apk";
        check(raw.equals(GithubShare.normalize(raw).toString()), "raw GitHub URL left unchanged");
        check(GithubShare.extractUrl("https://example.com/file.apk") == null, "non-GitHub URL rejected");
        try {
            GithubShare.normalize("http://github.com/to-shreds/torbox/blob/main/file.apk");
            throw new AssertionError("cleartext URL incorrectly accepted");
        } catch (ApkArchive.Rejected expected) {
            check(true, "cleartext URL rejected");
        }
        System.out.println("ALL " + count + " GITHUB SHARE TESTS PASSED");
    }
}
