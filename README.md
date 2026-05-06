# Java Typst

A library for rendering [Typst](https://typst.app/) markup to PDF from Java. Internally,
it exposes the Typst library as a webassembly module, which is then
executed using [Chicory](https://chicory.dev/) to eliminate any runtime
binary dependencies. It even supports online Typst packages.

## Usage

```xml
<dependency>
    <groupId>io.github.fatihcatalkaya</groupId>
    <artifactId>java-typst</artifactId>
    <version>2.0.0</version>
</dependency>
```

```java
byte[] pdf = JavaTypst.render("= Hello, World!\n_Lorem_ *ipsum*");
```

## Building from source

Requires a Rust toolchain with the `wasm32-wasip1` target:

```bash
rustup target add wasm32-wasip1
mvn -Prust package
```

Additionally, an installation of [wasm-opt](https://github.com/WebAssembly/binaryen) must be available.
