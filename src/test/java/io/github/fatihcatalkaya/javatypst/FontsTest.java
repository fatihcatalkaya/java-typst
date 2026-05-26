package io.github.fatihcatalkaya.javatypst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link JavaTypst#renderWithFonts} — passing custom font files (raw bytes) directly to
 * the engine, as opposed to pointing it at a search directory.
 *
 * <p>The bundled test font is TeX Gyre Cursor (GUST Font License), a monospace OTF carrying the
 * typographic family name {@code "TeX Gyre Cursor"} and the PostScript name
 * {@code "TeXGyreCursor-Regular"}. Neither is part of the typst-kit embedded font set, so its
 * appearance in a rendered PDF is unambiguous evidence the custom-fonts path is wired up.
 */
public class FontsTest {

    private static final String FONT_RESOURCE = "texgyrecursor-regular.otf";
    private static final String FONT_FAMILY = "TeX Gyre Cursor";
    private static final String FONT_PS_NAME_FRAGMENT = "TeXGyreCursor";

    private static byte[] customFont;

    @BeforeAll
    static void loadFontAndResetEngine() throws IOException {
        JavaTypst.reset();
        try (InputStream is = FontsTest.class.getResourceAsStream(FONT_RESOURCE)) {
            assertNotNull(is, FONT_RESOURCE + " missing from test resources");
            customFont = is.readAllBytes();
        }
        assertTrue(customFont.length > 0, "test font is empty");
    }

    /** Returns every PostScript font name embedded across all pages of the PDF. */
    private static Set<String> embeddedFontNames(byte[] pdf) throws IOException {
        Set<String> names = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName key : res.getFontNames()) {
                    PDFont font = res.getFont(key);
                    if (font != null && font.getName() != null) {
                        names.add(font.getName());
                    }
                }
            }
        }
        return names;
    }

    /** Strips PDFBox-extracted text — handy for assertions on rendered content. */
    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).strip();
        }
    }

    @Test
    public void customFontIsEmbeddedInOutputPdf() throws IOException {
        byte[] pdf = JavaTypst.renderWithFonts(
                "#set text(font: \"" + FONT_FAMILY + "\")\nMonospaced sample.", List.of(customFont));
        Set<String> names = embeddedFontNames(pdf);
        assertTrue(
                names.stream().anyMatch(n -> n.contains(FONT_PS_NAME_FRAGMENT)),
                "expected an embedded font containing '" + FONT_PS_NAME_FRAGMENT + "', got: " + names);
    }

    @Test
    public void customFontActuallyRendersTheRequestedGlyphs() throws IOException {
        // PDFBox text extraction round-trips the codepoints regardless of which font shaped them,
        // so this asserts the page was produced (and that the custom font did not break shaping).
        byte[] pdf =
                JavaTypst.renderWithFonts("#set text(font: \"" + FONT_FAMILY + "\")\nABC 123", List.of(customFont));
        assertEquals("ABC 123", pdfText(pdf));
    }

    @Test
    public void emptyFontListStillRendersWithEmbeddedFonts() throws IOException {
        byte[] pdf = JavaTypst.renderWithFonts("= Hello", List.of());
        assertEquals("Hello", pdfText(pdf));
        // No custom font was passed, so the TeXGyreCursor PostScript name must not appear.
        Set<String> names = embeddedFontNames(pdf);
        assertFalse(
                names.stream().anyMatch(n -> n.contains(FONT_PS_NAME_FRAGMENT)),
                "no TeXGyreCursor font should be embedded when none was passed; got: " + names);
    }

    @Test
    public void embeddedFontsRemainAvailableAlongsideCustomOnes() throws IOException {
        // "Libertinus Serif" ships with typst-kit's embedded fonts. With a custom font also passed,
        // both font sources must coexist — selecting the embedded family must still work.
        byte[] pdf = JavaTypst.renderWithFonts("#set text(font: \"Libertinus Serif\")\nlorem", List.of(customFont));
        assertEquals("lorem", pdfText(pdf));
        Set<String> names = embeddedFontNames(pdf);
        assertTrue(
                names.stream().anyMatch(n -> n.toLowerCase().contains("libertinus")),
                "expected an embedded Libertinus font, got: " + names);
    }

    @Test
    public void multipleFontsCanBePassedInOneCall() throws IOException {
        // Passing the same font file twice still produces a single registered family — proves the
        // list-encoding path handles count > 1 without corrupting subsequent reads.
        byte[] pdf = JavaTypst.renderWithFonts(
                "#set text(font: \"" + FONT_FAMILY + "\")\nXYZ", List.of(customFont, customFont));
        assertEquals("XYZ", pdfText(pdf));
        Set<String> names = embeddedFontNames(pdf);
        assertTrue(
                names.stream().anyMatch(n -> n.contains(FONT_PS_NAME_FRAGMENT)),
                "expected an embedded font containing '" + FONT_PS_NAME_FRAGMENT + "', got: " + names);
    }

    @Test
    public void plainRenderStillWorksAfterRenderWithFonts() throws IOException {
        // Verifies that the new code path does not leave the shared engine in a state that breaks
        // subsequent plain renders.
        JavaTypst.renderWithFonts("#set text(font: \"" + FONT_FAMILY + "\")\nseed", List.of(customFont));
        byte[] plain = JavaTypst.render("= Plain");
        assertEquals("Plain", pdfText(plain));
    }

    @Test
    public void invalidFontBytesDoNotCrashTheEngine() throws IOException {
        // typst's `Font::iter` yields an empty iterator for unparseable bytes — they are silently
        // skipped, so a render that does not require the missing font still succeeds.
        byte[] garbage = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] pdf = JavaTypst.renderWithFonts("plain", List.of(garbage));
        assertEquals("plain", pdfText(pdf));
    }

    @Test
    public void renderWithFontsPropagatesCompilationErrors() {
        // Hard typst syntax error — must surface as TypstRenderException through the new entry
        // point just as it does through `render`.
        TypstRenderException ex = assertThrows(
                TypstRenderException.class, () -> JavaTypst.renderWithFonts("#let x =", List.of(customFont)));
        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
    }

    @Test
    public void nullContentIsRejected() {
        assertThrows(NullPointerException.class, () -> JavaTypst.renderWithFonts(null, List.of(customFont)));
    }

    @Test
    public void nullFontListIsRejected() {
        assertThrows(NullPointerException.class, () -> JavaTypst.renderWithFonts("text", null));
    }

    @Test
    public void nullFontEntryIsRejected() {
        List<byte[]> fonts = new ArrayList<>();
        fonts.add(customFont);
        fonts.add(null);
        assertThrows(NullPointerException.class, () -> JavaTypst.renderWithFonts("text", fonts));
    }
}
