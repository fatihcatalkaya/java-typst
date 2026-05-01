# [2.0.0](https://github.com/fatihcatalkaya/java-typst/compare/1.4.0...2.0.0) (2026-05-01)


### Bug Fixes

* **ci:** fix deploy job being skipped on semantic-release tags ([b79df0f](https://github.com/fatihcatalkaya/java-typst/commit/b79df0f5e57632e9c9fd37cf36d1323b07596668))


### Features

* utilize Chicory and Typst as webassembly module for full portability ([#7](https://github.com/fatihcatalkaya/java-typst/issues/7)) ([9a84f33](https://github.com/fatihcatalkaya/java-typst/commit/9a84f338c4f1d3dddfdb5c6e48aa8c7931d6f583))


### BREAKING CHANGES

* Swap architecture-dependent Typst binary for Chicory webassembly runtime and Typst webassembly module.
