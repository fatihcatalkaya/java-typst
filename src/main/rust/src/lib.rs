use std::alloc::Layout;
use std::borrow::Cow;
use std::cell::RefCell;
use std::collections::HashMap;
use std::io::Read;
use std::path::PathBuf;
use std::ptr;
use flate2::read::GzDecoder;
use tar::Archive;
use typst::diag::{FileError, FileResult};
use typst::foundations::Bytes;
use typst::syntax::{FileId, Source};
use typst_as_lib::file_resolver::FileResolver;
use typst_as_lib::typst_kit_options::TypstKitFontOptions;
use typst_as_lib::TypstEngine;
use typst_pdf::PdfOptions;

// ── WASM import from Java host ───────────────────────────────────────────────

#[link(wasm_import_module = "java_typst_host")]
extern "C" {
    /// Two-call protocol: first call with out_buf=null returns required byte count (>=0) or negative on error;
    /// second call with a real buffer writes the bytes and returns actual count or negative on error.
    fn host_fetch_url(
        url_ptr: *const u8,
        url_len: u32,
        out_buf: *mut u8,
        out_buf_cap: u32,
    ) -> i32;
}

// ── Thread-local state ───────────────────────────────────────────────────────

thread_local! {
    static LAST_ERROR: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
    // spec_key "namespace/name/version" → { relative_path → raw_bytes }
    static PACKAGE_CACHE: RefCell<HashMap<String, HashMap<PathBuf, Vec<u8>>>> =
        RefCell::new(HashMap::new());
}

// ── Memory management exports ────────────────────────────────────────────────

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
    if len == 0 { return; }
    let layout = Layout::array::<u8>(len as usize).unwrap();
    std::alloc::dealloc(ptr, layout);
}

// ── Render export ────────────────────────────────────────────────────────────

/// # Safety
/// - `in_ptr` must point to `in_len` valid bytes in linear memory
/// - `out_len` must point to a 4-byte writable location (allocated via `alloc(4)`)
/// - The returned pointer, if non-null, must be freed via `dealloc(ptr, *out_len)`
/// - The error string at `last_error_ptr()` is valid only until the next `render()` call
#[no_mangle]
pub unsafe extern "C" fn render(in_ptr: *const u8, in_len: u32, out_len: *mut u32) -> *mut u8 {
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

// ── Compilation ──────────────────────────────────────────────────────────────

fn compile(source: String) -> Result<Vec<u8>, String> {
    let font_options = TypstKitFontOptions::new()
        .include_system_fonts(false)
        .include_embedded_fonts(true);
    let engine = TypstEngine::builder()
        .main_file(source)
        .search_fonts_with(font_options)
        .add_file_resolver(HostFetchResolver)
        .build();

    let doc = engine
        .compile()
        .output
        .map_err(|e| format!("{e}"))?;

    typst_pdf::pdf(&doc, &PdfOptions::default())
        .map_err(|errors| format!("{errors:?}"))
}

// ── HostFetchResolver ────────────────────────────────────────────────────────

struct HostFetchResolver;

impl FileResolver for HostFetchResolver {
    fn resolve_binary(&self, id: FileId) -> FileResult<Cow<'_, Bytes>> {
        if id.package().is_none() {
            return Err(file_not_found(id));
        }
        let key = ensure_package_cached(id)?;
        let vpath = id.vpath().as_rootless_path().to_path_buf();
        PACKAGE_CACHE.with(|cache| {
            cache.borrow()
                .get(&key)
                .and_then(|files| files.get(&vpath).cloned())
                .map(|b| Cow::Owned(Bytes::new(b)))
                .ok_or_else(|| file_not_found(id))
        })
    }

    fn resolve_source(&self, id: FileId) -> FileResult<Cow<'_, Source>> {
        if id.package().is_none() {
            return Err(file_not_found(id));
        }
        let key = ensure_package_cached(id)?;
        let vpath = id.vpath().as_rootless_path().to_path_buf();
        PACKAGE_CACHE.with(|cache| {
            let borrowed = cache.borrow();
            let bytes = borrowed
                .get(&key)
                .and_then(|files| files.get(&vpath))
                .ok_or_else(|| file_not_found(id))?;
            let text = std::str::from_utf8(bytes)
                .map_err(|_| FileError::InvalidUtf8)?;
            let text = text.trim_start_matches('\u{feff}');
            Ok(Cow::Owned(Source::new(id, text.to_owned())))
        })
    }
}

fn file_not_found(id: FileId) -> FileError {
    FileError::NotFound(id.vpath().as_rootless_path().to_path_buf())
}

/// Returns the cache key for `id`'s package, fetching the archive if not yet cached.
fn ensure_package_cached(id: FileId) -> FileResult<String> {
    let spec = id.package().expect("caller verified package is Some");
    let key = format!("{}/{}/{}", spec.namespace, spec.name, spec.version);
    if PACKAGE_CACHE.with(|c| c.borrow().contains_key(&key)) {
        return Ok(key);
    }
    let url = format!(
        "https://packages.typst.org/{}/{}-{}.tar.gz",
        spec.namespace, spec.name, spec.version
    );
    call_host_fetch(&url)
        .and_then(|data| unpack_tar_gz_into_cache(&key, &data))
        .map(|()| key)
        .map_err(|_| file_not_found(id))
}

fn call_host_fetch(url: &str) -> Result<Vec<u8>, String> {
    // First call: null buffer → Java fetches, caches internally, returns required size
    let required = unsafe {
        host_fetch_url(url.as_ptr(), url.len() as u32, ptr::null_mut(), 0)
    };
    if required < 0 {
        return Err(format!("host_fetch_url size-query failed: {required}"));
    }
    let mut buf = vec![0u8; required as usize];
    // Second call: real buffer → Java writes bytes
    let n = unsafe {
        host_fetch_url(url.as_ptr(), url.len() as u32, buf.as_mut_ptr(), buf.len() as u32)
    };
    if n < 0 {
        return Err(format!("host_fetch_url data-write failed: {n}"));
    }
    buf.truncate(n as usize);
    Ok(buf)
}

fn unpack_tar_gz_into_cache(spec_key: &str, data: &[u8]) -> Result<(), String> {
    let gz = GzDecoder::new(std::io::Cursor::new(data));
    let mut archive = Archive::new(gz);
    for entry in archive.entries().map_err(|e| e.to_string())? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        let path: PathBuf = entry.path().map_err(|e| e.to_string())?.into_owned();
        // Archive entries are "name-version/file.typ"; skip the leading component.
        let file_path: PathBuf = path.components().skip(1).collect();
        if file_path.as_os_str().is_empty() {
            continue; // skip the directory entry itself
        }
        let mut bytes = Vec::new();
        entry.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
        PACKAGE_CACHE.with(|cache| {
            cache.borrow_mut()
                .entry(spec_key.to_owned())
                .or_default()
                .insert(file_path, bytes);
        });
    }
    Ok(())
}
