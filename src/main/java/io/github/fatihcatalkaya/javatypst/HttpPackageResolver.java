package io.github.fatihcatalkaya.javatypst;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Downloads package archives over HTTP.
 *
 * <p>Deliberately built on {@link HttpURLConnection} rather than {@code java.net.http.HttpClient}:
 * the latter does not exist on Android at any API level, while {@link HttpURLConnection} is
 * available on every supported JVM and Android runtime.
 */
public final class HttpPackageResolver implements TypstPackageResolver {

    private static final String DEFAULT_URL_TEMPLATE = "https://packages.typst.org/%s/%s-%s.tar.gz";
    private static final int TIMEOUT_MS = 15_000;

    private final String urlTemplate;

    /** Resolves packages from the official Typst package registry. */
    public HttpPackageResolver() {
        this(DEFAULT_URL_TEMPLATE);
    }

    /**
     * Resolves packages from a custom registry or mirror.
     *
     * @param urlTemplate a {@link String#format} template receiving namespace, name and version,
     *     e.g. {@code "https://mirror.example.com/%s/%s-%s.tar.gz"}
     */
    public HttpPackageResolver(String urlTemplate) {
        if (urlTemplate == null) throw new NullPointerException("urlTemplate");
        this.urlTemplate = urlTemplate;
    }

    @Override
    public byte[] resolve(String namespace, String name, String version) throws TypstPackageNotFoundException {
        String url = String.format(urlTemplate, namespace, name, version);
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);

                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new TypstPackageNotFoundException(namespace, name, version);
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + status + ": " + url);
                }
                try (InputStream body = connection.getInputStream()) {
                    return readAll(body);
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new RuntimeException("Package fetch failed: " + url, e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
