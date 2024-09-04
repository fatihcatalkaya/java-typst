mod typst_as_lib;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use typst::eval::Tracer;
use typst::foundations::Smart;
use crate::typst_as_lib::TypstWrapperWorld;

#[no_mangle]
pub extern "system" fn Java_io_github_fatihcatalkaya_javatypst_JavaTypst_render<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JString<'local>
) -> JByteArray<'local> {

    let input: String = env.get_string(&content).expect("Could not get java string!").into();
    let world = TypstWrapperWorld::new("./".to_owned(), input);

    // Render document
    let mut tracer = Tracer::default();
    let document = typst::compile(&world, &mut tracer).expect("Error compiling typst");

    // Output to pdf
    let pdf = typst_pdf::pdf(&document, Smart::Auto, None);

    let java_bytes = env.byte_array_from_slice(pdf.as_slice())
        .expect("Failed to create new java byte array");

    java_bytes
}