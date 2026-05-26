# Java Typst

A library for rendering [Typst](https://typst.app/) markup to PDF from Java. Internally,
it exposes the Typst library as a webassembly module, which is then
executed using [Chicory](https://chicory.dev/) to eliminate any runtime
binary dependencies. It even supports online Typst packages.

This is a fork of [fatihcatalkaya/java-typst](https://github.com/fatihcatalkaya/java-typst)
published under a different Maven coordinate. It adds:

- `JavaTypst.renderWithInputs(content, Map<String, String> inputs)` — exposes the map as
  `sys.inputs` inside the Typst document, mirroring `typst compile --input key=value`.
- `JavaTypst.renderWithFonts(content, List<byte[]> fonts)` — registers caller-supplied font
  files (raw OTF/TTF/TTC bytes) alongside the embedded fonts; no font directory is scanned.

## Usage

```xml
<dependency>
    <groupId>io.github.petomka</groupId>
    <artifactId>java-typst</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
// Basic render
byte[] pdf = JavaTypst.render("= Hello, World!\n_Lorem_ *ipsum*");

// With inputs exposed as `sys.inputs`
byte[] greeting = JavaTypst.renderWithInputs(
        "Hello, #sys.inputs.at(\"name\")!",
        Map.of("name", "World"));

// With a custom font passed as raw bytes
byte[] fontBytes = Files.readAllBytes(Path.of("MyFont.otf"));
byte[] styled = JavaTypst.renderWithFonts(
        "#set text(font: \"My Font\")\nStyled output",
        List.of(fontBytes));
```

## Building from source

Requires a Rust toolchain with the `wasm32-wasip1` target:

```bash
rustup target add wasm32-wasip1
mvn -Prust package
```

Additionally, an installation of [wasm-opt](https://github.com/WebAssembly/binaryen) must be available.
