use std::alloc::Layout;
use std::cell::RefCell;
use typst_as_lib::typst_kit_options::TypstKitFontOptions;
use typst_as_lib::TypstEngine;
use typst_pdf::PdfOptions;

thread_local! {
    static LAST_ERROR: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

#[no_mangle]
pub extern "C" fn alloc(len: u32) -> *mut u8 {
    if len == 0 {
        return std::ptr::NonNull::dangling().as_ptr();
    }
    let layout = Layout::array::<u8>(len as usize).unwrap();
    unsafe { std::alloc::alloc(layout) }
}

/// # Safety
/// `ptr` must have been returned by `alloc(len)` and not yet freed.
#[no_mangle]
pub unsafe extern "C" fn dealloc(ptr: *mut u8, len: u32) {
    if len == 0 {
        return;
    }
    let layout = Layout::array::<u8>(len as usize).unwrap();
    std::alloc::dealloc(ptr, layout);
}

/// # Safety
/// - `in_ptr` must point to `in_len` valid bytes in linear memory
/// - `out_len` must point to a 4-byte writable location (allocated via `alloc(4)`)
/// - The returned pointer, if non-null, must be freed via `dealloc(ptr, *out_len)`
/// - The error string at `last_error_ptr()` is valid only until the next `render()` call
#[no_mangle]
pub unsafe extern "C" fn render(
    in_ptr: *const u8,
    in_len: u32,
    out_len: *mut u32,
) -> *mut u8 {
    let source = {
        let slice = std::slice::from_raw_parts(in_ptr, in_len as usize);
        match String::from_utf8(slice.to_vec()) {
            Ok(s) => s,
            Err(e) => {
                LAST_ERROR.with(|err| *err.borrow_mut() = e.to_string().into_bytes());
                return std::ptr::null_mut();
            }
        }
    };

    match compile(source) {
        Ok(pdf) => {
            let pdf_len = pdf.len();
            *out_len = pdf_len as u32;
            let ptr = alloc(pdf_len as u32);
            if !ptr.is_null() {
                std::ptr::copy_nonoverlapping(pdf.as_ptr(), ptr, pdf_len);
            }
            ptr // pdf Vec drops here, freeing its original memory via Vec's allocator
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
    let font_options = TypstKitFontOptions::new()
        .include_system_fonts(false)
        .include_embedded_fonts(true);
    let engine = TypstEngine::builder()
        .main_file(source)
        .search_fonts_with(font_options)
        .build();

    let doc = engine
        .compile()
        .output
        .map_err(|e| format!("{e}"))?;

    typst_pdf::pdf(&doc, &PdfOptions::default())
        .map_err(|errors| format!("{errors:?}"))
}
