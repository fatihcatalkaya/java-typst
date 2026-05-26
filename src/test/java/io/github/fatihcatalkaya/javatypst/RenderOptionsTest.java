package io.github.fatihcatalkaya.javatypst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * Exercises the unified {@link RenderOptions} API itself — DEFAULT, builder null safety, and
 * (most importantly) the case the old per-feature methods couldn't express: <i>multiple
 * options together in one render</i>.
 */
public class RenderOptionsTest {

    private static byte[] customFont;
    private static byte[] testPkgTarGz;

    @BeforeAll
    static void loadFixturesAndReset() throws IOException {
        JavaTypst.reset();
        try (InputStream is = RenderOptionsTest.class.getResourceAsStream("texgyrecursor-regular.otf")) {
            assertNotNull(is, "test font missing from resources");
            customFont = is.readAllBytes();
        }
        try (InputStream is = RenderOptionsTest.class.getResourceAsStream("testpkg-0.1.0.tar.gz")) {
            assertNotNull(is, "test package missing from resources");
            testPkgTarGz = is.readAllBytes();
        }
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).strip();
        }
    }

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

    @Test
    public void defaultOptionsRendersIdenticallyToTheNoOptionsOverload() throws IOException {
        String src = "= Same Output";
        byte[] viaNoOptions = JavaTypst.render(src);
        byte[] viaDefault = JavaTypst.render(src, RenderOptions.DEFAULT);
        // Compare extracted text rather than bytes, since PDFs can differ in metadata trivia.
        assertEquals(pdfText(viaNoOptions), pdfText(viaDefault));
    }

    @Test
    public void defaultIsAReusableSharedInstance() throws IOException {
        // DEFAULT is a singleton; using it twice in a row must not raise nor mutate anything.
        assertEquals("first", pdfText(JavaTypst.render("first", RenderOptions.DEFAULT)));
        assertEquals("second", pdfText(JavaTypst.render("second", RenderOptions.DEFAULT)));
    }

    /**
     * The headline test for this refactor: a single render call combines a {@code sys.inputs}
     * map AND a custom font — something the old per-feature overloads could not express without
     * adding yet another method.
     */
    @Test
    public void inputsAndFontsCombineInOneCall() throws IOException {
        RenderOptions opts = RenderOptions.builder()
                .inputs(Map.of("greeting", "Hallo"))
                .fonts(List.of(customFont))
                .build();
        byte[] pdf =
                JavaTypst.render("#set text(font: \"TeX Gyre Cursor\")\n#sys.inputs.at(\"greeting\"), Welt!", opts);

        // sys.inputs flowed through:
        assertEquals("Hallo, Welt!", pdfText(pdf));
        // and the custom font is actually embedded (proves both options were applied together):
        assertTrue(
                embeddedFontNames(pdf).stream().anyMatch(n -> n.contains("TeXGyreCursor")),
                "expected TeXGyreCursor embedded; got: " + embeddedFontNames(pdf));
    }

    /**
     * Combines every option the API currently exposes — inputs, fonts, and an air-gapped
     * package map — in a single render call. Proves all three flow through the unified options
     * blob (or the static package-map handoff, in the case of packages) without interfering.
     */
    @Test
    public void inputsFontsAndAirGappedPackagesAllCombineInOneCall() throws IOException {
        RenderOptions opts = RenderOptions.builder()
                .inputs(Map.of("name", "Benedikt"))
                .fonts(List.of(customFont))
                .packages(Map.of("@preview/testpkg:0.1.0", testPkgTarGz))
                .build();

        byte[] pdf = JavaTypst.render(
                "#import \"@preview/testpkg:0.1.0\": hello\n"
                        + "#set text(font: \"TeX Gyre Cursor\")\n"
                        + "#hello() #sys.inputs.at(\"name\")",
                opts);

        // testpkg's `hello()` emits "Hello from testpkg!" — so all four pieces (package import,
        // font selection, sys.inputs lookup, plain text concatenation) have to have worked.
        assertEquals("Hello from testpkg! Benedikt", pdfText(pdf));
        assertTrue(
                embeddedFontNames(pdf).stream().anyMatch(n -> n.contains("TeXGyreCursor")),
                "expected TeXGyreCursor embedded; got: " + embeddedFontNames(pdf));
    }

    @Test
    public void packagesBuilderSwitchesToAirGappedMode() {
        // packages(Map.of()) means "air-gapped with zero packages available" — any import must
        // fail rather than silently fetching over HTTP.
        RenderOptions opts = RenderOptions.builder().packages(Map.of()).build();
        assertThrows(
                TypstRenderException.class,
                () -> JavaTypst.render("#import \"@preview/example:0.1.0\": *\n= hi", opts));
    }

    @Test
    public void nullContentIsRejected() {
        assertThrows(NullPointerException.class, () -> JavaTypst.render(null, RenderOptions.DEFAULT));
    }

    @Test
    public void nullOptionsIsRejected() {
        assertThrows(NullPointerException.class, () -> JavaTypst.render("x", null));
    }

    @Test
    public void builderInputsNullIsRejected() {
        assertThrows(NullPointerException.class, () -> RenderOptions.builder().inputs(null));
    }

    @Test
    public void builderFontsNullIsRejected() {
        assertThrows(NullPointerException.class, () -> RenderOptions.builder().fonts(null));
    }

    @Test
    public void builderPackagesNullIsRejected() {
        // packages(null) is not how you opt out of air-gapped mode — you just don't call the
        // setter at all. Null is a misuse and must throw.
        assertThrows(NullPointerException.class, () -> RenderOptions.builder().packages(null));
    }
}
