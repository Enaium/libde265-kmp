/*
 * WebAssembly entry point.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * The exported functions themselves live in de265_kmp.cpp. This translation
 * unit takes their addresses so the linker definitely pulls that object out of
 * the static archive; without a reference the archive member would be dropped
 * and the exports listed in CMakeLists.txt would be missing.
 */
#include "de265_kmp.h"

typedef void (*dk_wasm_export)(void);

static const dk_wasm_export dk_wasm_exports[] = {
    (dk_wasm_export)dk_version_number,
    (dk_wasm_export)dk_version_string,
    (dk_wasm_export)dk_version_major,
    (dk_wasm_export)dk_version_minor,
    (dk_wasm_export)dk_version_maintenance,
    (dk_wasm_export)dk_decoder_create,
    (dk_wasm_export)dk_decoder_close,
    (dk_wasm_export)dk_decoder_set_threads,
    (dk_wasm_export)dk_decode,
    (dk_wasm_export)dk_flush,
    (dk_wasm_export)dk_frame_count,
    (dk_wasm_export)dk_last_error,
    (dk_wasm_export)dk_last_warning,
    (dk_wasm_export)dk_error_text,
    (dk_wasm_export)dk_output_blob,
    (dk_wasm_export)dk_alloc,
    (dk_wasm_export)dk_free,
};

/* Keep the table observable so no optimiser removes it. */
const dk_wasm_export *dk_wasm_keep_exports(void);

const dk_wasm_export *dk_wasm_keep_exports(void) { return dk_wasm_exports; }
