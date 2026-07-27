package io.github.fatihcatalkaya.javatypst;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HttpPackageResolverTest {

    private static final byte[] ARCHIVE = "not-really-a-tarball".getBytes(UTF_8);

    private HttpServer server;
    private HttpPackageResolver resolver;

    @BeforeEach
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            int status = 200;
            if (exchange.getRequestURI().getPath().contains("/missing-")) {
                status = 404;
            } else if (exchange.getRequestURI().getPath().contains("/broken-")) {
                status = 500;
            }
            byte[] body = status == 200 ? ARCHIVE : new byte[0];
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        resolver = new HttpPackageResolver("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/%s/%s-%s.tar.gz");
    }

    @AfterEach
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void resolveReturnsTheArchiveBytes() throws Exception {
        assertArrayEquals(ARCHIVE, resolver.resolve("preview", "cetz", "0.3.2"));
    }

    @Test
    public void resolveThrowsPackageNotFoundOnHttp404() {
        TypstPackageNotFoundException ex = assertThrows(
                TypstPackageNotFoundException.class, () -> resolver.resolve("preview", "missing", "1.0.0"));

        assertTrue(ex.getMessage().contains("@preview/missing:1.0.0"), ex.getMessage());
    }

    @Test
    public void resolveThrowsOnUnexpectedHttpStatus() {
        RuntimeException ex =
                assertThrows(RuntimeException.class, () -> resolver.resolve("preview", "broken", "1.0.0"));

        assertTrue(ex.getCause().getMessage().contains("500"), String.valueOf(ex.getCause()));
    }
}
