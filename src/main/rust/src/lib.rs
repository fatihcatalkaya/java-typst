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
use typst::foundations::{Bytes, Dict, Value};
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

/// Renders Typst markup to a PDF, with optional `sys.inputs` and custom fonts supplied via the
/// TLV-encoded options blob (see [`parse_options`]).
///
/// # Safety
/// - `src_ptr` must point to `src_len` valid UTF-8 bytes (the Typst source) in linear memory
/// - `opts_ptr` must point to `opts_len` valid bytes encoding the render options;
///   `[0,0,0,0]` (or any buffer with a leading u32 field-count of 0) means "no options"
/// - `out_len` must point to a 4-byte writable location (allocated via `alloc(4)`)
/// - The returned pointer, if non-null, must be freed via `dealloc(ptr, *out_len)`
/// - The error string at `last_error_ptr()` is valid only until the next `render()` call
#[no_mangle]
pub unsafe extern "C" fn render(
    src_ptr: *const u8,
    src_len: u32,
    opts_ptr: *const u8,
    opts_len: u32,
    out_len: *mut u32,
) -> *mut u8 {
    let source = {
        let slice = std::slice::from_raw_parts(src_ptr, src_len as usize);
        match String::from_utf8(slice.to_vec()) {
            Ok(s) => s,
            Err(e) => return fail(e.to_string()),
        }
    };

    let opts = {
        let slice = std::slice::from_raw_parts(opts_ptr, opts_len as usize);
        match parse_options(slice) {
            Ok(o) => o,
            Err(e) => return fail(e),
        }
    };

    match compile(source, opts.inputs, opts.fonts) {
        Ok(pdf) => write_pdf_output(pdf, out_len),
        Err(msg) => fail(msg),
    }
}

/// Copies the PDF bytes into a freshly-allocated WASM buffer and writes its length to `out_len`.
///
/// # Safety
/// Same preconditions as the `out_len` parameter of [`render`].
unsafe fn write_pdf_output(pdf: Vec<u8>, out_len: *mut u32) -> *mut u8 {
    let pdf_len = pdf.len();
    *out_len = pdf_len as u32;
    let ptr = alloc(pdf_len as u32);
    if !ptr.is_null() {
        std::ptr::copy_nonoverlapping(pdf.as_ptr(), ptr, pdf_len);
    }
    ptr
}

/// Stashes `msg` in thread-local `LAST_ERROR` and returns the null pointer that signals
/// failure to the Java host.
fn fail(msg: String) -> *mut u8 {
    LAST_ERROR.with(|e| *e.borrow_mut() = msg.into_bytes());
    std::ptr::null_mut()
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

fn compile(
    source: String,
    inputs: Option<Dict>,
    fonts: Vec<Vec<u8>>,
) -> Result<Vec<u8>, String> {
    let font_options = TypstKitFontOptions::new()
        .include_system_fonts(false)
        .include_embedded_fonts(true);
    // `.fonts(...)` accepts any `IntoIterator<Item: IntoFonts>`; passing an empty
    // vec leaves the typst-kit embedded fonts as the only source.
    let engine = TypstEngine::builder()
        .main_file(source)
        .search_fonts_with(font_options)
        .add_file_resolver(HostFetchResolver)
        .fonts(fonts)
        .build();

    // `compile_with_input` accepts anything implementing `Into<Dict>`; a `Dict`
    // converts into itself. Without inputs, `sys.inputs` keeps its default value.
    let doc = match inputs {
        Some(dict) => engine.compile_with_input(dict),
        None => engine.compile(),
    }
    .output
    .map_err(|e| format!("{e}"))?;

    typst_pdf::pdf(&doc, &PdfOptions::default())
        .map_err(|errors| format!("{errors:?}"))
}

// ── Options decoding ─────────────────────────────────────────────────────────

/// Tag values for the TLV options blob. Adding a new option means picking a new tag here
/// (and teaching `parse_options` how to dispatch it) — never a new WASM export.
const TAG_INPUTS: u32 = 1;
const TAG_FONTS: u32 = 2;

/// Decoded render options. Fields absent from the blob keep their natural defaults
/// (no `sys.inputs` injection, no custom fonts).
struct RenderOptions {
    inputs: Option<Dict>,
    fonts: Vec<Vec<u8>>,
}

/// Decodes the render-options blob produced by the Java host:
///
/// ```text
/// field_count: u32                            then, repeated `field_count` times:
///   tag: u32      1 = inputs, 2 = fonts
///   len: u32      length of `payload` in bytes
///   payload: bytes   tag-specific (see `parse_inputs` / `parse_fonts`)
/// ```
///
/// An empty buffer is treated identically to a buffer whose first u32 is zero — both mean
/// "no options". Unknown tags are an error so a stale WASM never silently drops a field
/// from a newer host.
fn parse_options(bytes: &[u8]) -> Result<RenderOptions, String> {
    let mut opts = RenderOptions {
        inputs: None,
        fonts: Vec::new(),
    };
    if bytes.is_empty() {
        return Ok(opts);
    }
    let mut pos = 0usize;
    let field_count = read_u32(bytes, &mut pos)?;
    for _ in 0..field_count {
        let tag = read_u32(bytes, &mut pos)?;
        let len = read_u32(bytes, &mut pos)? as usize;
        let end = pos
            .checked_add(len)
            .ok_or_else(|| "options: length overflow".to_string())?;
        let payload = bytes
            .get(pos..end)
            .ok_or_else(|| "options: unexpected end of buffer".to_string())?;
        match tag {
            TAG_INPUTS => opts.inputs = Some(parse_inputs(payload)?),
            TAG_FONTS => opts.fonts = parse_fonts(payload)?,
            other => return Err(format!("options: unknown tag {other}")),
        }
        pos = end;
    }
    Ok(opts)
}

// ── Inputs decoding ──────────────────────────────────────────────────────────

/// Decodes the `sys.inputs` dictionary from its wire format:
///
/// ```text
/// count: u32                          then, repeated `count` times:
///   key_len: u32   key:   key_len UTF-8 bytes
///   val_len: u32   value: val_len UTF-8 bytes
/// ```
///
/// All integers are little-endian. Every value becomes a Typst string, mirroring the
/// `--input key=value` semantics of the Typst CLI.
fn parse_inputs(bytes: &[u8]) -> Result<Dict, String> {
    let mut dict = Dict::new();
    let mut pos = 0usize;
    let count = read_u32(bytes, &mut pos)?;
    for _ in 0..count {
        let key = read_str(bytes, &mut pos)?;
        let value = read_str(bytes, &mut pos)?;
        dict.insert(key.into(), Value::Str(value.into()));
    }
    Ok(dict)
}

// ── Fonts decoding ───────────────────────────────────────────────────────────

/// Decodes the custom fonts list from its wire format:
///
/// ```text
/// count: u32                          then, repeated `count` times:
///   font_len: u32   font_bytes: font_len raw OTF/TTF/TTC bytes
/// ```
///
/// All integers are little-endian. Each entry is a complete font file as accepted by
/// typst's `Font::iter` (single-face OTF/TTF or a multi-face TTC).
fn parse_fonts(bytes: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    let mut pos = 0usize;
    let count = read_u32(bytes, &mut pos)?;
    let mut fonts = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let len = read_u32(bytes, &mut pos)? as usize;
        let end = pos
            .checked_add(len)
            .ok_or_else(|| "fonts: length overflow".to_string())?;
        let slice = bytes
            .get(pos..end)
            .ok_or_else(|| "fonts: unexpected end of buffer".to_string())?;
        fonts.push(slice.to_vec());
        pos = end;
    }
    Ok(fonts)
}

// ── Shared decode primitives ─────────────────────────────────────────────────

/// Reads a little-endian `u32` at `*pos`, advancing `pos` past it.
fn read_u32(bytes: &[u8], pos: &mut usize) -> Result<u32, String> {
    let end = pos
        .checked_add(4)
        .ok_or_else(|| "buffer: length overflow".to_string())?;
    let slice = bytes
        .get(*pos..end)
        .ok_or_else(|| "buffer: unexpected end of buffer".to_string())?;
    let value = u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]);
    *pos = end;
    Ok(value)
}

/// Reads a length-prefixed UTF-8 string at `*pos`, advancing `pos` past it.
fn read_str(bytes: &[u8], pos: &mut usize) -> Result<String, String> {
    let len = read_u32(bytes, pos)? as usize;
    let end = pos
        .checked_add(len)
        .ok_or_else(|| "buffer: length overflow".to_string())?;
    let slice = bytes
        .get(*pos..end)
        .ok_or_else(|| "buffer: unexpected end of buffer".to_string())?;
    let value = std::str::from_utf8(slice)
        .map_err(|e| format!("buffer: invalid UTF-8: {e}"))?
        .to_string();
    *pos = end;
    Ok(value)
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
    // Derive expected leading dir ("name-version") from spec_key "ns/name/version"
    let leading_dir: String = {
        let mut parts = spec_key.split('/');
        parts.next(); // namespace
        let name    = parts.next().unwrap_or("");
        let version = parts.next().unwrap_or("");
        format!("{name}-{version}")
    };

    let gz = GzDecoder::new(std::io::Cursor::new(data));
    let mut archive = Archive::new(gz);
    for entry in archive.entries().map_err(|e| e.to_string())? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        let path: PathBuf = entry.path().map_err(|e| e.to_string())?.into_owned();

        // Strip the leading component when the archive is rooted (e.g. "name-version/")
        // or starts with "." (CurDir). Flat archives (packages.typst.org) are used as-is.
        let file_path: PathBuf = match path.components().next() {
            Some(std::path::Component::CurDir) => path.components().skip(1).collect(),
            Some(std::path::Component::Normal(c)) if c == leading_dir.as_str() => {
                path.components().skip(1).collect()
            }
            _ => path.clone(),
        };

        if file_path.as_os_str().is_empty() {
            continue;
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
