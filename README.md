# Java Typst

A library for rendering [Typst](https://typst.app/) markup to PDF from Java.

## Requirements

- **Java 17+** (upgraded from Java 8 in v2.0.0)
- No native libraries or Rust toolchain needed at runtime — the library ships a
  pre-built WebAssembly binary executed by [Chicory](https://chicory.dev/).

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

## Breaking changes in 2.0.0

| Change | Detail |
|--------|--------|
| Java baseline | Java 17+ required |
| Remote packages | `#import "@preview/..."` is **not supported** and throws `TypstRenderException`. |
| Exceptions | Compilation errors throw `TypstRenderException` (was `RuntimeException`). |

## Performance

Render times are ~2–3× slower than the JNI build due to WASM interpretation.
The Maven `rust` profile builds the WASM module from source.
Chicory's build-time AOT compiler was evaluated but cannot be applied to this
module due to JVM method-size limits on the compiled typst font code.

## Building from source

Requires a Rust toolchain with the `wasm32-wasip1` target:

```bash
rustup target add wasm32-wasip1
mvn -Prust package
```
