package io.github.fatihcatalkaya.javatypst;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BasicTest {

  private static final String TYPST_CONTENT = """
      = Hello, World!
      _Lorem_ *ipsum* sit dolor amet
      """;
  private static final String EXPECTED_PDF_TEXT = """
      Hello, World!
      Lorem ipsum sit dolor amet
      """;

  @Test
  public void test() throws IOException {
    byte[] pdfBytes = JavaTypst.render(TYPST_CONTENT);

    File pdfFile = File.createTempFile("test_", ".pdf");
    FileOutputStream fos = new FileOutputStream(pdfFile);
    fos.write(pdfBytes);
    fos.close();

    PDDocument doc = Loader.loadPDF(pdfFile);
    assertEquals(1, doc.getNumberOfPages());
    PDFTextStripper stripper = new PDFTextStripper();
    String parsedPdfText = stripper.getText(doc);
    assertTrue(EXPECTED_PDF_TEXT.contentEquals(parsedPdfText));
  }
}
