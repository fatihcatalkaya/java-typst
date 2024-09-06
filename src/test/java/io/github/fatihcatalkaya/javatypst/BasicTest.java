package io.github.fatihcatalkaya.javatypst;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
