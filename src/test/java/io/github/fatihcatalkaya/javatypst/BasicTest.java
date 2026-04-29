package io.github.fatihcatalkaya.javatypst;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BasicTest {

  private static final String TYPST_CONTENT = "= Hello, World!\n_Lorem_ *ipsum* sit dolor amet";
  private static final String EXPECTED_PDF_TEXT = "Hello, World!\nLorem ipsum sit dolor amet\n";

  @Test
  public void test() throws IOException {
    byte[] pdfBytes = JavaTypst.render(TYPST_CONTENT);

    PDDocument doc = Loader.loadPDF(pdfBytes);
    assertEquals(1, doc.getNumberOfPages());
    PDFTextStripper stripper = new PDFTextStripper();
    String parsedPdfText = stripper.getText(doc);
    assertEquals(EXPECTED_PDF_TEXT, parsedPdfText);
  }

  @Test
  public void testInvalidMarkupThrowsException() {
    // Incomplete let expression is a hard syntax error in typst
    TypstRenderException ex = assertThrows(
        TypstRenderException.class,
        () -> JavaTypst.render("#let x =")
    );
    assertNotNull(ex.getMessage());
    assertFalse(ex.getMessage().isBlank());
  }

  @Test
  public void testPackageImportProducesError() {
    // Empty map — map-only mode, no HTTP fallback — missing package must throw
    assertThrows(
        TypstRenderException.class,
        () -> JavaTypst.render(
            "#import \"@preview/example:0.1.0\": *\n= Hello",
            Map.of()
        )
    );
  }

  @Test
  public void testSetPackageResolverIsAccepted() {
    TypstPackageResolver r = (ns, name, ver) -> new byte[0];
    JavaTypst.setPackageResolver(r);
    JavaTypst.setPackageResolver(new HttpPackageResolver()); // restore default
  }

  @Test
  public void testDiskCacheHitAvoidsDelegateCall() throws Exception {
    Path tmpDir = Files.createTempDirectory("javatypst-test-cache");
    int[] callCount = {0};
    TypstPackageResolver counting = (ns, name, ver) -> {
      callCount[0]++;
      return new byte[]{1, 2, 3};
    };
    PackageDiskCache cache = new PackageDiskCache(tmpDir);

    byte[] first  = cache.get("preview", "test", "1.0.0", counting);
    byte[] second = cache.get("preview", "test", "1.0.0", counting);

    assertArrayEquals(first, second);
    assertEquals(1, callCount[0], "resolver must be called exactly once");
  }

  @Test
  public void testPackageRenderOnline() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        "true".equalsIgnoreCase(System.getProperty("packages.online")),
        "Skipped: run with -Dpackages.online=true to enable online package test"
    );
    byte[] pdf = JavaTypst.render(
        "#import \"@preview/cetz:0.3.2\": canvas, draw\n" +
        "#canvas({ draw.circle((0,0), radius: 1) })"
    );
    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
  }

  @Test
  public void testPackageRenderOffline() throws IOException {
    byte[] tarGz;
    try (InputStream is = BasicTest.class.getResourceAsStream("testpkg-0.1.0.tar.gz")) {
      assertNotNull(is, "testpkg-0.1.0.tar.gz missing from test resources");
      tarGz = is.readAllBytes();
    }
    Map<String, byte[]> packages = Map.of("@preview/testpkg:0.1.0", tarGz);
    // Will fail until WASM is rebuilt in Task 8 — that is expected for now
    byte[] pdf = JavaTypst.render(
        "#import \"@preview/testpkg:0.1.0\": hello\n#hello()",
        packages
    );
    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
  }
}
