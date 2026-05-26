package io.github.fatihcatalkaya.javatypst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code sys.inputs} feature via the unified {@link JavaTypst#render(String,
 * RenderOptions)} entry point and {@link RenderOptions.Builder#inputs}.
 */
public class InputsTest {

    /**
     * Forces a clean interpreter-mode engine so these tests do not depend on whichever machine a
     * previously executed test class happened to leave installed in the shared static state.
     */
    @BeforeAll
    static void freshEngine() {
        JavaTypst.reset();
    }

    /** Renders {@code content} with {@code inputs} and returns the trimmed extracted PDF text. */
    private static String renderText(String content, Map<String, String> inputs) throws IOException {
        byte[] pdf =
                JavaTypst.render(content, RenderOptions.builder().inputs(inputs).build());
        assertNotNull(pdf, "render returned no PDF");
        assertTrue(pdf.length > 0, "render produced an empty PDF");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).strip();
        }
    }

    @Test
    public void singleStringInputIsExposed() throws IOException {
        assertEquals("World", renderText("#sys.inputs.at(\"name\")", Map.of("name", "World")));
    }

    @Test
    public void multipleInputsAreAllExposed() throws IOException {
        Map<String, String> inputs = Map.of("first", "Ada", "last", "Lovelace");
        assertEquals("Ada Lovelace", renderText("#sys.inputs.at(\"first\") #sys.inputs.at(\"last\")", inputs));
    }

    @Test
    public void emptyInputsMapBehavesLikeNoInputs() throws IOException {
        // With the unified API an empty inputs map is encoded as "no inputs field" in the TLV
        // blob; `sys.inputs` therefore stays at its default empty dictionary.
        assertEquals("0", renderText("#sys.inputs.len()", Map.of()));
    }

    @Test
    public void emptyInputsMapStillRendersOrdinaryMarkup() throws IOException {
        assertEquals("Heading", renderText("= Heading", Map.of()));
    }

    @Test
    public void numericStringInputCanBeParsedInsideTypst() throws IOException {
        assertEquals("42", renderText("#(int(sys.inputs.at(\"count\")) + 1)", Map.of("count", "41")));
    }

    @Test
    public void importSysInputsSyntaxWorks() throws IOException {
        assertEquals("imported", renderText("#import sys: inputs\n#inputs.at(\"via\")", Map.of("via", "imported")));
    }

    @Test
    public void unicodeKeyRoundTrips() throws IOException {
        // The key carries accented letters, a CJK character and a 4-byte emoji.
        String key = "Ünïcödé_键_🔑";
        assertEquals("ok", renderText("#sys.inputs.at(\"" + key + "\")", Map.of(key, "ok")));
    }

    @Test
    public void unicodeValueRoundTrips() throws IOException {
        // Comparing the value inside Typst keeps the assertion independent of the glyph
        // coverage of the embedded fonts in the rendered PDF.
        assertEquals("true", renderText("#(sys.inputs.at(\"city\") == \"café\")", Map.of("city", "café")));
    }

    @Test
    public void valueWithSpacesAndPunctuationRoundTrips() throws IOException {
        String value = "Hello, World! (v2)";
        assertEquals(value, renderText("#sys.inputs.at(\"title\")", Map.of("title", value)));
    }

    @Test
    public void valueWithNewlineRoundTrips() throws IOException {
        assertEquals(
                "true",
                renderText("#(sys.inputs.at(\"block\") == \"line1\\nline2\")", Map.of("block", "line1\nline2")));
    }

    @Test
    public void emptyStringValueIsPreserved() throws IOException {
        assertEquals("true", renderText("#(sys.inputs.at(\"blank\") == \"\")", Map.of("blank", "")));
    }

    @Test
    public void manyInputsAreAllPreserved() throws IOException {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            inputs.put("k" + i, Integer.toString(i));
        }
        String template =
                "#(sys.inputs.at(\"k0\") + \"/\" + sys.inputs.at(\"k49\")" + " + \"/\" + str(sys.inputs.len()))";
        assertEquals("0/49/50", renderText(template, inputs));
    }

    @Test
    public void inputsDoNotLeakBetweenRenders() throws IOException {
        assertEquals("alpha", renderText("#sys.inputs.at(\"v\")", Map.of("v", "alpha")));
        assertEquals("omega", renderText("#sys.inputs.at(\"v\")", Map.of("v", "omega")));
    }

    @Test
    public void renderWithInputsKeepsWorkingAlongsidePlainRender() throws IOException {
        byte[] plain = JavaTypst.render("plain");
        try (PDDocument doc = Loader.loadPDF(plain)) {
            assertEquals("plain", new PDFTextStripper().getText(doc).strip());
        }
        assertEquals("y", renderText("#sys.inputs.at(\"x\")", Map.of("x", "y")));
    }

    @Test
    public void missingKeyAccessRaisesRenderException() {
        TypstRenderException ex = assertThrows(
                TypstRenderException.class,
                () -> JavaTypst.render(
                        "#sys.inputs.at(\"absent\")",
                        RenderOptions.builder().inputs(Map.of("present", "1")).build()));
        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
    }

    @Test
    public void nullInputValueIsRejected() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("key", null);
        // Builder accepts the map (it does no per-entry validation); the encoder catches the
        // null value at render time and surfaces it as an NPE.
        RenderOptions opts = RenderOptions.builder().inputs(inputs).build();
        assertThrows(NullPointerException.class, () -> JavaTypst.render("#sys.inputs.at(\"key\")", opts));
    }
}
