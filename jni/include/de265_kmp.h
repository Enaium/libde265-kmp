/*
 * C shim for the libde265 Kotlin Multiplatform binding.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * The same implementation is compiled three ways:
 *   - into the static archive linked by every Kotlin/Native cinterop klib,
 *   - into the JNI shared library (de265_jni.cpp),
 *   - into the standalone WebAssembly module (de265_wasm_entry.c).
 *
 * All three expose this API, so argument validation and the packed output
 * format are identical on every platform.
 *
 * Conventions:
 *   - functions returning int return DK_OK when at least one picture is
 *     available in the output blob, DK_BUFFERED when the input was accepted
 *     but the decoder needs more data, and DK_ERROR on failure;
 *   - `dk_output_blob()` returns a buffer owned by the decoder, valid until the
 *     next decode/flush call; callers copy it if they keep it;
 *   - the input is an Annex-B byte stream (start codes, parameter sets may be
 *     in-band);
 *   - decoded pictures are planar 8-bit YUV, tightly packed in the blob.
 */
#ifndef DE265_KMP_H
#define DE265_KMP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** First four bytes of the blob returned by `dk_output_blob()`. */
#define DK_OUTPUT_MAGIC 0x4B503644 /* "D6PK" little-endian */

/** Layout revision of the blob. */
#define DK_OUTPUT_VERSION 1

/** At least one picture is available in the output buffer. */
#define DK_OK 1

/** The input was accepted but no picture is ready yet (or flushing is done). */
#define DK_BUFFERED 0

/** Generic failure; inspect `dk_last_error()` for the libde265 error code. */
#define DK_ERROR -1

/** Size of one frame record in the output blob. */
#define DK_FRAME_RECORD 64

/** libde265 chroma formats, exactly `de265_chroma`. */
#define DK_CHROMA_MONO 0
#define DK_CHROMA_420 1
#define DK_CHROMA_422 2
#define DK_CHROMA_444 3

typedef struct dk_decoder dk_decoder;

/* ------------------------------------------------------------------ */
/* Version                                                            */
/* ------------------------------------------------------------------ */

/** BCD-encoded libde265 version, e.g. 0x01010300 for 1.1.3. */
uint32_t dk_version_number(void);

/** Human-readable libde265 version, e.g. "1.1.3". */
const char *dk_version_string(void);

int dk_version_major(void);

int dk_version_minor(void);

int dk_version_maintenance(void);

/* ------------------------------------------------------------------ */
/* Decoder                                                            */
/* ------------------------------------------------------------------ */

/** Creates a decoder; NULL on failure. The decoder is single-threaded. */
dk_decoder *dk_decoder_create(void);

void dk_decoder_close(dk_decoder *decoder);

/**
 * Starts `threads` libde265 worker threads. Must be called before the first
 * decode; `threads <= 0` keeps the decoder single-threaded. Returns 0 on
 * success and DK_ERROR otherwise.
 */
int dk_decoder_set_threads(dk_decoder *decoder, int threads);

/**
 * Pushes one Annex-B access unit and decodes everything it completes.
 * Returns DK_OK when pictures are available, DK_BUFFERED when the decoder
 * needs more input and DK_ERROR on a fatal error.
 */
int dk_decode(dk_decoder *decoder, const uint8_t *data, int size, int64_t pts);

/** Signals end of stream and drains the remaining pictures. */
int dk_flush(dk_decoder *decoder);

/** Number of pictures in the last blob. */
int dk_frame_count(const dk_decoder *decoder);

/** Last fatal libde265 error code, 0 (`DE265_OK`) when there was none. */
int dk_last_error(const dk_decoder *decoder);

/** Last non-fatal libde265 warning code, 0 when there was none. */
int dk_last_warning(const dk_decoder *decoder);

/** Text for a `de265_error` value. */
const char *dk_error_text(int code);

/**
 * Serialises the pictures produced by the last decode/flush call; `size`
 * receives the byte count. The buffer is reused by the next call.
 *
 * Layout (all integers little-endian):
 *   int32  magic (DK_OUTPUT_MAGIC)
 *   int32  version (DK_OUTPUT_VERSION)
 *   int32  frame_count
 *   int32  payload_offset (absolute, 8-byte aligned)
 *   frame_count records of DK_FRAME_RECORD bytes:
 *     int32 width, height, chroma, bit_depth
 *     int64 pts
 *     int32 y_size, u_size, v_size
 *     int32 y_offset, u_offset, v_offset   (relative to payload_offset)
 *     int32 stride_y, stride_u, stride_v   (source strides, informational)
 *     int32 reserved
 *   uint8  payload[], planes concatenated, tightly packed
 */
const uint8_t *dk_output_blob(dk_decoder *decoder, int *size);

/* ------------------------------------------------------------------ */
/* Memory                                                             */
/* ------------------------------------------------------------------ */

void *dk_alloc(size_t size);

void dk_free(void *pointer);

#ifdef __cplusplus
}
#endif

#endif /* DE265_KMP_H */
