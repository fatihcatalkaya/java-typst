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

The main artifact is a thin jar; Chicory is resolved as a normal transitive dependency.
An all-in-one jar is also published under the `jar-with-dependencies` classifier.

## Android

Requires `minSdkVersion 26` (Android 8.0). Below that, `assembleDebug` fails while dexing
Chicory:

```
MethodHandle.invoke and MethodHandle.invokeExact are only supported starting with Android O
(--min-api 26): Lcom/dylibso/chicory/runtime/ByteArrayMemory;->writeI32(II)V
```

`ByteArrayMemory` accesses memory through polymorphic `VarHandle` methods, which D8 cannot
desugar. The class belongs to Chicory rather than to this library, so the floor cannot be
lowered here.

No dependency exclusions are needed — the published main artifact contains only this
library's own classes.

Two runtime notes:

```java
// Android's user.home is "/", so the default cache directory is not writable.
JavaTypst.setPackageCacheDirectory(context.getCacheDir().toPath());
```

Downloading `@preview` packages needs `android.permission.INTERNET`. If you would rather not
resolve anything over the network, pass the archives yourself and no HTTP request is made:

```java
byte[] pdf = JavaTypst.render(content, Map.of("@preview/cetz:0.3.2", archiveBytes));
```

## Building from source

Requires a Rust toolchain with the `wasm32-wasip1` target:

```bash
rustup target add wasm32-wasip1
mvn -Prust package
```

Additionally, an installation of [wasm-opt](https://github.com/WebAssembly/binaryen) must be available.
