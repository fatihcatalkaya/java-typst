package io.github.fatihcatalkaya.javatypst;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable bundle of optional inputs to {@link JavaTypst#render(String, RenderOptions)}.
 *
 * <p>Every field is optional. The intent is that adding a new render-time knob in the future
 * (e.g. an alternate PDF profile, a custom file resolver, a logging hook) only requires adding a
 * new builder method and a new accessor here — never a new overload of {@code render}, and never
 * a new combinatorial product across feature axes.
 *
 * <h2>What each field does</h2>
 * <ul>
 *   <li><b>{@code inputs}</b> — exposed inside the document as {@code sys.inputs}, mirroring
 *       {@code typst compile --input key=value}. Defaults to an empty map.</li>
 *   <li><b>{@code fonts}</b> — raw OTF/TTF/TTC byte arrays added alongside the typst-kit
 *       embedded fonts. Defaults to an empty list (only embedded fonts are used).</li>
 *   <li><b>{@code packages}</b> — switches the engine to <i>air-gapped</i> mode. When set, the
 *       supplied {@code "@namespace/name:version"} → tarball map is the <i>only</i> package
 *       source: imports not found in the map fail with {@link TypstRenderException} and no HTTP
 *       request is made. When left unset (the default), the engine falls back to fetching
 *       packages from {@code packages.typst.org} via the configured
 *       {@link TypstPackageResolver} and on-disk cache.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * byte[] pdf = JavaTypst.render(
 *         "Hello, #sys.inputs.at(\"name\")!",
 *         RenderOptions.builder()
 *                 .inputs(Map.of("name", "World"))
 *                 .fonts(List.of(myFontBytes))
 *                 .build());
 * }</pre>
 *
 * <p>For a render with no options at all, use {@link JavaTypst#render(String)} (which is just a
 * shortcut for {@code render(content, RenderOptions.DEFAULT)}).
 */
public final class RenderOptions {

    /** A no-op options instance: no inputs, no fonts, HTTP package fallback. */
    public static final RenderOptions DEFAULT = new RenderOptions(Map.of(), List.of(), null);

    private final Map<String, String> inputs;
    private final List<byte[]> fonts;
    private final Map<String, byte[]> packages;

    private RenderOptions(Map<String, String> inputs, List<byte[]> fonts, Map<String, byte[]> packages) {
        this.inputs = inputs;
        this.fonts = fonts;
        this.packages = packages;
    }

    /** Inputs exposed as {@code sys.inputs} (never null; may be empty). */
    public Map<String, String> inputs() {
        return inputs;
    }

    /** Custom font file contents added to the engine (never null; may be empty). */
    public List<byte[]> fonts() {
        return fonts;
    }

    /**
     * Air-gapped package map keyed by {@code "@namespace/name:version"}, or {@code null} when
     * the engine should fall back to its {@link TypstPackageResolver} for package fetches.
     */
    public Map<String, byte[]> packagesOrNull() {
        return packages;
    }

    /** Creates a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link RenderOptions}. Not thread-safe; use one builder per render. */
    public static final class Builder {
        private Map<String, String> inputs = Map.of();
        private List<byte[]> fonts = List.of();
        private Map<String, byte[]> packages = null;

        private Builder() {}

        /**
         * Sets the map exposed as {@code sys.inputs}. Must not be null. Calling this twice
         * replaces the previous value; not calling it leaves {@code sys.inputs} empty.
         */
        public Builder inputs(Map<String, String> inputs) {
            this.inputs = Objects.requireNonNull(inputs, "inputs");
            return this;
        }

        /**
         * Sets the list of custom font files (raw OTF/TTF/TTC bytes). Must not be null.
         * Individual entries also must not be null. Not calling this leaves the typst-kit
         * embedded fonts as the only source.
         */
        public Builder fonts(List<byte[]> fonts) {
            this.fonts = Objects.requireNonNull(fonts, "fonts");
            return this;
        }

        /**
         * Switches the engine into <i>air-gapped</i> mode for this render: only the supplied
         * packages are visible to the document, and no HTTP request will be made. Pass an empty
         * map to forbid all package imports. Must not be null — to <i>opt out</i> of air-gapped
         * mode, simply don't call this method.
         */
        public Builder packages(Map<String, byte[]> packages) {
            this.packages = Objects.requireNonNull(packages, "packages");
            return this;
        }

        public RenderOptions build() {
            return new RenderOptions(inputs, fonts, packages);
        }
    }
}
