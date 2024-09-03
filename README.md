# Java Typst
This library allows to render [Typst](https://typst.app/) templates
using native Java functions. The intention, which lead to the development of
this library was, to use Typst as a templating engine for PDF file generation.

This library heavily relies on previous work from 
[Timo Bachmann's typst-as-a-library](https://github.com/tfachmann/typst-as-library).
I have modified it, such that it uses the embedded fonts from 
the [typst-assets](https://crates.io/crates/typst-assets) crate.