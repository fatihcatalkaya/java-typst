package io.github.fatihcatalkaya.javatypst;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpPackageResolver implements TypstPackageResolver {

    private static final String URL_TEMPLATE =
        "https://packages.typst.org/%s/%s-%s.tar.gz";

    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public byte[] resolve(String namespace, String name, String version)
            throws TypstPackageNotFoundException {
        URI uri = URI.create(String.format(URL_TEMPLATE, namespace, name, version));
        try {
            HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) {
                throw new TypstPackageNotFoundException(namespace, name, version);
            }
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + ": " + uri);
            }
            return resp.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Package fetch failed: " + uri, e);
        }
    }
}
