/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265

/**
 * JavaScript glue that owns the WebAssembly instance.
 *
 * The libde265 module is a standalone WASI reactor: it imports only a handful
 * of WASI calls (stdio, used by libde265's logging) plus the Emscripten
 * memory-growth notification. The glue provides those, exposes one function
 * per shim entry point on `globalThis.__de265Web`, and marshals strings through
 * the module's own `dk_alloc`.
 *
 * Bulk data (the access unit and the result blob) crosses the boundary as
 * base64 strings because Kotlin/JS and Kotlin/Wasm cannot share typed arrays
 * with a module compiled outside their own toolchain.
 */
internal const val DE265_WEB_INIT_JS: String = """
(base64) => {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  let wasmExports = null;
  const memory = () => new Uint8Array(wasmExports.memory.buffer);
  const dataView = () => new DataView(wasmExports.memory.buffer);
  const decoder = new TextDecoder();

  const encodeBase64 = (data) => {
    let text = '';
    const chunk = 0x8000;
    for (let i = 0; i < data.length; i += chunk) {
      text += String.fromCharCode.apply(null, data.subarray(i, i + chunk));
    }
    return btoa(text);
  };

  const decodeBase64 = (text) => {
    const raw = atob(text);
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  };

  const readString = (pointer) => {
    if (!pointer) return '';
    const data = memory();
    let end = pointer;
    while (data[end] !== 0) end++;
    return decoder.decode(data.subarray(pointer, end));
  };

  const writeBytes = (data) => {
    const pointer = wasmExports.dk_alloc(data.length);
    if (!pointer) return 0;
    memory().set(data, pointer);
    return pointer;
  };

  const resultOf = (handle, code) => {
    if (code !== 1) return code + ':';
    const sizePointer = wasmExports.dk_alloc(4);
    if (!sizePointer) return code + ':';
    const pointer = wasmExports.dk_output_blob(handle, sizePointer);
    if (!pointer) {
      wasmExports.dk_free(sizePointer);
      return code + ':';
    }
    const size = dataView().getUint32(sizePointer, true);
    const data = memory().slice(pointer, pointer + size);
    wasmExports.dk_free(sizePointer);
    return code + ':' + encodeBase64(data);
  };

  const imports = {
    env: {
      emscripten_notify_memory_growth() {},
      __syscall_unlinkat() { return 0; },
      __syscall_renameat() { return 0; },
    },
    wasi_snapshot_preview1: {
      fd_write(fd, iovs, iovsLength, written) {
        let total = 0;
        for (let i = 0; i < iovsLength; i++) {
          const pointer = dataView().getUint32(iovs + i * 8, true);
          const length = dataView().getUint32(iovs + i * 8 + 4, true);
          const text = decoder.decode(memory().subarray(pointer, pointer + length));
          if (text.trim().length > 0) {
            if (fd === 2) console.error(text.trimEnd());
            else console.log(text.trimEnd());
          }
          total += length;
        }
        dataView().setUint32(written, total, true);
        return 0;
      },
      fd_read() { return 0; },
      fd_seek() { return 0; },
      fd_close() { return 0; },
      clock_time_get(clock, precision, out) {
        const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
        dataView().setBigUint64(out, BigInt(Math.floor(now * 1e6)), true);
        return 0;
      },
      environ_sizes_get(count, size) {
        dataView().setUint32(count, 0, true);
        dataView().setUint32(size, 0, true);
        return 0;
      },
      environ_get() { return 0; },
    },
  };

  const instance = new WebAssembly.Instance(new WebAssembly.Module(bytes), imports);
  wasmExports = instance.exports;
  if (wasmExports._initialize) wasmExports._initialize();

  globalThis.__de265Web = {
    versionNumber() {
      return wasmExports.dk_version_number() >>> 0;
    },
    versionString() {
      return readString(wasmExports.dk_version_string());
    },
    versionMajor() {
      return wasmExports.dk_version_major();
    },
    versionMinor() {
      return wasmExports.dk_version_minor();
    },
    versionMaintenance() {
      return wasmExports.dk_version_maintenance();
    },
    decoderCreate() {
      return wasmExports.dk_decoder_create();
    },
    decoderClose(handle) {
      wasmExports.dk_decoder_close(handle);
    },
    decoderSetThreads(handle, threads) {
      return wasmExports.dk_decoder_set_threads(handle, threads);
    },
    decode(handle, payloadBase64, ptsLow, ptsHigh) {
      const data = decodeBase64(payloadBase64);
      const pointer = writeBytes(data);
      if (!pointer) return '-1:';
      const pts = (BigInt(ptsHigh) << BigInt(32)) | BigInt(ptsLow >>> 0);
      const code = wasmExports.dk_decode(handle, pointer, data.length, pts);
      wasmExports.dk_free(pointer);
      return resultOf(handle, code);
    },
    flush(handle) {
      return resultOf(handle, wasmExports.dk_flush(handle));
    },
    frameCount(handle) {
      return wasmExports.dk_frame_count(handle);
    },
    lastError(handle) {
      return wasmExports.dk_last_error(handle);
    },
    lastWarning(handle) {
      return wasmExports.dk_last_warning(handle);
    },
    errorText(code) {
      return readString(wasmExports.dk_error_text(code));
    },
  };
}
"""
