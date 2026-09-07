package app.jabs.apkcatcher;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the URL that GitHub's Android app shares into the actual public file stream. */
final class GithubShare {
    private static final Pattern URL = Pattern.compile("https://[^\\s<>\\\"']+");
    private static final int MAX_REDIRECTS = 6;

    private GithubShare() {}

    static String extractUrl(CharSequence text) {
        if (text == null) return null;
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String candidate = trimTrailingPunctuation(matcher.group());
            try {
                URI uri = new URI(candidate);
                if ("https".equalsIgnoreCase(uri.getScheme()) && allowedHost(uri.getHost())) return candidate;
            } catch (URISyntaxException ignored) {
                // Keep looking if the share text contains some other malformed URL first.
            }
        }
        return null;
    }

    private static String trimTrailingPunctuation(String value) {
        while (!value.isEmpty()) {
            char c = value.charAt(value.length() - 1);
            if (c == ')' || c == ']' || c == '}' || c == ',' || c == ';') value = value.substring(0, value.length() - 1);
            else break;
        }
        return value;
    }

    static boolean allowedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return "github.com".equals(h) || "www.github.com".equals(h)
                || "githubusercontent.com".equals(h) || h.endsWith(".githubusercontent.com");
    }

    static URI normalize(String sharedUrl) throws ApkArchive.Rejected {
        try {
            URI uri = new URI(sharedUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHost(uri.getHost())) {
                throw new ApkArchive.Rejected("APK Catcher only opens GitHub HTTPS links.");
            }
            String value = uri.toASCIIString();
            int hash = value.indexOf('#');
            if (hash >= 0) value = value.substring(0, hash);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (("github.com".equals(host) || "www.github.com".equals(host)) && path.contains("/blob/")) {
                if (!value.matches(".*[?&](raw|download)=([^&]*).*")) {
                    value += value.contains("?") ? "&raw=1" : "?raw=1";
                }
            }
            return new URI(value);
        } catch (URISyntaxException bad) {
            throw new ApkArchive.Rejected("GitHub shared an invalid link.");
        }
    }

    static InputStream open(String sharedUrl) throws IOException {
        URI current = normalize(sharedUrl);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!"https".equalsIgnoreCase(current.getScheme()) || !allowedHost(current.getHost())) {
                throw new ApkArchive.Rejected("GitHub redirected to an unexpected address.");
            }
            final HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestProperty("User-Agent", "APK-Catcher/1.1");
            connection.setRequestProperty("Accept", "application/octet-stream,*/*;q=0.8");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new ApkArchive.Rejected("GitHub returned an incomplete redirect.");
                current = current.resolve(location);
                continue;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                if (status == 401 || status == 403 || status == 404) {
                    throw new ApkArchive.Rejected("GitHub could not provide this file. APK Catcher can fetch public GitHub files only.");
                }
                throw new ApkArchive.Rejected("GitHub returned HTTP " + status + " for this file.");
            }
            long length = connection.getContentLengthLong();
            if (length > ApkArchive.MAX_BYTES) {
                connection.disconnect();
                throw new ApkArchive.Rejected("This GitHub file is too large (limit: 1 GB).");
            }
            InputStream stream;
            try {
                stream = connection.getInputStream();
            } catch (IOException failure) {
                connection.disconnect();
                throw failure;
            }
            return new FilterInputStream(stream) {
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally { connection.disconnect(); }
                }
            };
        }
        throw new ApkArchive.Rejected("GitHub redirected too many times.");
    }
}
