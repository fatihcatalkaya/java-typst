# [2.2.0](https://github.com/fatihcatalkaya/java-typst/compare/2.1.0...2.2.0) (2026-07-27)


### Bug Fixes

* Android compatibility (duplicate chicory classes, java.net.http, minSdk) ([#12](https://github.com/fatihcatalkaya/java-typst/issues/12)) ([0c52869](https://github.com/fatihcatalkaya/java-typst/commit/0c528693c341bfa0df3bd2795bfcc599651a1f47))


### Features

* upgrade Typst to 0.15.1 ([d411d5b](https://github.com/fatihcatalkaya/java-typst/commit/d411d5bd2c8789a60d4f4197866bf32828735f6c))

# [2.1.0](https://github.com/fatihcatalkaya/java-typst/compare/2.0.0...2.1.0) (2026-05-06)


### Features

* use Chicory AOT compilation of Typst module ([#8](https://github.com/fatihcatalkaya/java-typst/issues/8)) ([d6c8fe5](https://github.com/fatihcatalkaya/java-typst/commit/d6c8fe5e24154d50f7c04f12c6821429592e53fe))

# [2.0.0](https://github.com/fatihcatalkaya/java-typst/compare/1.4.0...2.0.0) (2026-05-01)


### Bug Fixes

* **ci:** fix deploy job being skipped on semantic-release tags ([b79df0f](https://github.com/fatihcatalkaya/java-typst/commit/b79df0f5e57632e9c9fd37cf36d1323b07596668))


### Features

* utilize Chicory and Typst as webassembly module for full portability ([#7](https://github.com/fatihcatalkaya/java-typst/issues/7)) ([9a84f33](https://github.com/fatihcatalkaya/java-typst/commit/9a84f338c4f1d3dddfdb5c6e48aa8c7931d6f583))


### BREAKING CHANGES

* Swap architecture-dependent Typst binary for Chicory webassembly runtime and Typst webassembly module.
