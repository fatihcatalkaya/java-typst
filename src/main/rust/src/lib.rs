use std::cell::RefCell;
use typst_as_lib::TypstEngine;
use typst_pdf::PdfOptions;

thread_local! {
    static LAST_ERROR: RefCell<Vec<u8>> = RefCell::new(Vec::new());
}

#[no_mangle]
pub extern "C" fn alloc(len: u32) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(len as usize);
    unsafe { buf.set_len(len as usize) };
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr
}

#[no_mangle]
pub unsafe extern "C" fn dealloc(ptr: *mut u8, len: u32) {
    drop(Vec::from_raw_parts(ptr, len as usize, len as usize));
}

#[no_mangle]
pub unsafe extern "C" fn render(
    in_ptr: *const u8,
    in_len: u32,
    out_len: *mut u32,
) -> *mut u8 {
    let source = {
        let slice = std::slice::from_raw_parts(in_ptr, in_len as usize);
        String::from_utf8_lossy(slice).into_owned()
    };

    match compile(source) {
        Ok(mut pdf) => {
            *out_len = pdf.len() as u32;
            let ptr = pdf.as_mut_ptr();
            std::mem::forget(pdf);
            ptr
        }
        Err(msg) => {
            LAST_ERROR.with(|e| *e.borrow_mut() = msg.into_bytes());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn last_error_ptr() -> *mut u8 {
    LAST_ERROR.with(|e| e.borrow().as_ptr() as *mut u8)
}

#[no_mangle]
pub extern "C" fn last_error_len() -> u32 {
    LAST_ERROR.with(|e| e.borrow().len() as u32)
}

fn compile(source: String) -> Result<Vec<u8>, String> {
    let engine = TypstEngine::builder()
        .main_file(source)
        .build();

    let doc: typst::layout::PagedDocument = engine
        .compile()
        .output
        .map_err(|e| format!("{e}"))?;

    typst_pdf::pdf(&doc, &PdfOptions::default())
        .map_err(|errors| format!("{errors:?}"))
}
