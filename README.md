# Java Typst

A library for rendering [Typst](https://typst.app/) markup to PDF from Java. Internally,
it exposes the Typst library as a webassembly module, which is then
executed using [Chicory](https://chicory.dev/) to eliminate any runtime
binary dependencies. It even supports online Typst packages.

This is a fork of [fatihcatalkaya/java-typst](https://github.com/fatihcatalkaya/java-typst)
published under a different Maven coordinate. It adds:

- **`sys.inputs`** — pass a `Map<String, String>` that becomes available inside the document
  as `sys.inputs`, mirroring `typst compile --input key=value`.
- **Custom fonts** — register caller-supplied font files (raw OTF/TTF/TTC bytes) alongside the
  embedded fonts; no font directory is scanned.
- **One render method, options bag** — both features (plus the existing air-gapped package
  map) are configured via a single `RenderOptions` builder, so combining them is one call and
  not a cartesian product of overloads.

## Usage

```xml
<dependency>
    <groupId>io.github.petomka</groupId>
    <artifactId>java-typst</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
// Plain render — no options
byte[] pdf = JavaTypst.render("= Hello, World!\n_Lorem_ *ipsum*");

// Inputs only
byte[] greeting = JavaTypst.render(
        "Hello, #sys.inputs.at(\"name\")!",
        RenderOptions.builder()
                .inputs(Map.of("name", "World"))
                .build());

// Custom font only
byte[] fontBytes = Files.readAllBytes(Path.of("MyFont.otf"));
byte[] styled = JavaTypst.render(
        "#set text(font: \"My Font\")\nStyled output",
        RenderOptions.builder()
                .fonts(List.of(fontBytes))
                .build());

// Everything together — inputs, fonts, and an air-gapped package map, in one call
byte[] all = JavaTypst.render(
        "#import \"@preview/testpkg:0.1.0\": hello\n"
                + "#set text(font: \"My Font\")\n"
                + "#hello() #sys.inputs.at(\"name\")",
        RenderOptions.builder()
                .inputs(Map.of("name", "Ada"))
                .fonts(List.of(fontBytes))
                .packages(Map.of("@preview/testpkg:0.1.0", testpkgTarGzBytes))
                .build());
```

`RenderOptions.builder().packages(...)` switches the engine into **air-gapped** mode: only
the supplied packages are visible to the document, and no HTTP fetch is attempted. Omit the
call (or use `RenderOptions.DEFAULT`) to let the engine resolve packages from
`packages.typst.org` and a local on-disk cache.

## Building from source

Requires a Rust toolchain with the `wasm32-wasip1` target:

```bash
rustup target add wasm32-wasip1
mvn -Prust package
```

Additionally, an installation of [wasm-opt](https://github.com/WebAssembly/binaryen) must be available.
