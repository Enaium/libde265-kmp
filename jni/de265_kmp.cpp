/*
 * C shim for the libde265 Kotlin Multiplatform binding.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * See de265_kmp.h for the contract. Only the public libde265 API is used, so
 * the same translation unit compiles for every target. The shim owns the
 * packed output format: the Kotlin side then has a single parser for JNI,
 * cinterop and WebAssembly.
 */
#include "de265_kmp.h"

#include <stdlib.h>
#include <string.h>

#include "de265.h"

struct dk_decoder {
  de265_decoder_context *ctx;

  /* Frames produced by the last decode/flush call, copied out of libde265's
   * picture buffers before they are recycled. */
  int frame_count;
  int frame_capacity;
  struct dk_frame *frames;

  /* Packed blob handed to the Kotlin side. */
  uint8_t *blob;
  size_t blob_capacity;

  int last_error;
  int last_warning;

  /* de265_start_worker_threads() may only run before decoding starts. */
  int started;
  int threads;
};

struct dk_frame {
  int width;
  int height;
  int chroma;
  int bit_depth;
  int64_t pts;
  int plane_width[3];
  int plane_height[3];
  int stride[3];
  uint8_t *plane[3];
};

/* ------------------------------------------------------------------------- */
/* Memory                                                                     */
/* ------------------------------------------------------------------------- */

void *dk_alloc(size_t size) {
  /* malloc(0) may return NULL, which the callers would report as OOM. */
  return malloc(size ? size : 1);
}

void dk_free(void *pointer) { free(pointer); }

/* ------------------------------------------------------------------------- */
/* Version                                                                    */
/* ------------------------------------------------------------------------- */

uint32_t dk_version_number(void) { return de265_get_version_number(); }

const char *dk_version_string(void) { return de265_get_version(); }

int dk_version_major(void) { return de265_get_version_number_major(); }

int dk_version_minor(void) { return de265_get_version_number_minor(); }

int dk_version_maintenance(void) {
  return de265_get_version_number_maintenance();
}

const char *dk_error_text(int code) {
  return de265_get_error_text((de265_error)code);
}

/* ------------------------------------------------------------------------- */
/* Decoder                                                                    */
/* ------------------------------------------------------------------------- */

dk_decoder *dk_decoder_create(void) {
  dk_decoder *decoder = (dk_decoder *)calloc(1, sizeof(*decoder));
  if (!decoder) return NULL;
  decoder->ctx = de265_new_decoder();
  if (!decoder->ctx) {
    free(decoder);
    return NULL;
  }
  decoder->last_error = DE265_OK;
  decoder->last_warning = DE265_OK;
  return decoder;
}

void dk_decoder_close(dk_decoder *decoder) {
  if (!decoder) return;
  if (decoder->frames) {
    for (int index = 0; index < decoder->frame_count; index++) {
      for (int plane = 0; plane < 3; plane++) free(decoder->frames[index].plane[plane]);
    }
    free(decoder->frames);
  }
  free(decoder->blob);
  if (decoder->ctx) de265_free_decoder(decoder->ctx);
  free(decoder);
}

int dk_decoder_set_threads(dk_decoder *decoder, int threads) {
  if (!decoder || !decoder->ctx) return DK_ERROR;
  if (decoder->started) return DK_ERROR;
  if (threads <= 0) {
    decoder->threads = 0;
    return 0;
  }
  de265_error error = de265_start_worker_threads(decoder->ctx, threads);
  if (error != DE265_OK) {
    decoder->last_error = error;
    return DK_ERROR;
  }
  decoder->threads = threads;
  return 0;
}

static void dk_frames_reset(dk_decoder *decoder) {
  for (int index = 0; index < decoder->frame_count; index++) {
    for (int plane = 0; plane < 3; plane++) {
      free(decoder->frames[index].plane[plane]);
      decoder->frames[index].plane[plane] = NULL;
    }
  }
  decoder->frame_count = 0;
}

static struct dk_frame *dk_frame_append(dk_decoder *decoder) {
  if (decoder->frame_count == decoder->frame_capacity) {
    int capacity = decoder->frame_capacity ? decoder->frame_capacity * 2 : 4;
    struct dk_frame *grown = (struct dk_frame *)realloc(
        decoder->frames, (size_t)capacity * sizeof(struct dk_frame));
    if (!grown) return NULL;
    memset(grown + decoder->frame_capacity, 0,
           (size_t)(capacity - decoder->frame_capacity) * sizeof(struct dk_frame));
    decoder->frames = grown;
    decoder->frame_capacity = capacity;
  }
  struct dk_frame *frame = &decoder->frames[decoder->frame_count++];
  memset(frame, 0, sizeof(*frame));
  return frame;
}

/* Copies one plane out of libde265's picture buffer. The source stride is
 * usually larger than the visible width (padding for the motion-compensation
 * kernels), so every row is copied separately. */
static int dk_copy_plane(struct dk_frame *frame, int channel, const uint8_t *source,
                         int width, int height, int stride) {
  if (width <= 0 || height <= 0 || !source) {
    frame->plane[channel] = NULL;
    frame->plane_width[channel] = 0;
    frame->plane_height[channel] = 0;
    frame->stride[channel] = 0;
    return 0;
  }
  uint8_t *copy = (uint8_t *)malloc((size_t)width * (size_t)height);
  if (!copy) return -1;
  for (int row = 0; row < height; row++) {
    memcpy(copy + (size_t)row * (size_t)width,
           source + (size_t)row * (size_t)stride, (size_t)width);
  }
  frame->plane[channel] = copy;
  frame->plane_width[channel] = width;
  frame->plane_height[channel] = height;
  frame->stride[channel] = stride;
  return 0;
}

static int dk_capture_image(dk_decoder *decoder, const de265_image *image) {
  struct dk_frame *frame = dk_frame_append(decoder);
  if (!frame) return -1;

  frame->width = de265_get_image_width(image, 0);
  frame->height = de265_get_image_height(image, 0);
  frame->chroma = (int)de265_get_chroma_format(image);
  frame->bit_depth = 8;
  frame->pts = (int64_t)de265_get_image_PTS(image);

  int channels = frame->chroma == DK_CHROMA_MONO ? 1 : 3;
  for (int channel = 0; channel < channels; channel++) {
    int stride = 0;
    const uint8_t *plane = de265_get_image_plane(image, channel, &stride);
    int width = de265_get_image_width(image, channel);
    int height = de265_get_image_height(image, channel);
    if (dk_copy_plane(frame, channel, plane, width, height, stride) < 0) return -1;
  }
  return 0;
}

/* de265_get_next_picture() also releases the picture from the output queue;
 * calling de265_release_next_picture() afterwards would pop the next one. */
static int dk_drain(dk_decoder *decoder) {
  int guard = 0;
  for (;;) {
    int more = 0;
    de265_error error = de265_decode(decoder->ctx, &more);
    if (error == DE265_ERROR_WAITING_FOR_INPUT_DATA) {
      /* Not a failure: the decoder simply has no complete frame yet. */
      error = DE265_OK;
      more = 0;
    } else if (error >= DE265_WARNING_NO_WPP_CANNOT_USE_MULTITHREADING) {
      /* Warnings are reported through dk_last_warning() and do not stop the
       * decode loop. */
      decoder->last_warning = error;
      error = DE265_OK;
    }
    if (error != DE265_OK) {
      decoder->last_error = error;
      return DK_ERROR;
    }

    const de265_image *image = de265_get_next_picture(decoder->ctx);
    if (image) {
      if (dk_capture_image(decoder, image) < 0) {
        decoder->last_error = DE265_ERROR_OUT_OF_MEMORY;
        return DK_ERROR;
      }
      /* A picture was produced, so ask for more even when libde265 reported
       * that it has nothing else pending right now. */
      more = 1;
    }
    if (!more) break;
    if (++guard > 100000) break;
  }

  for (;;) {
    de265_error warning = de265_get_warning(decoder->ctx);
    if (warning == DE265_OK) break;
    decoder->last_warning = warning;
  }

  return decoder->frame_count > 0 ? DK_OK : DK_BUFFERED;
}

int dk_decode(dk_decoder *decoder, const uint8_t *data, int size, int64_t pts) {
  if (!decoder || !decoder->ctx || !data || size <= 0) return DK_ERROR;
  dk_frames_reset(decoder);
  decoder->last_error = DE265_OK;
  decoder->last_warning = DE265_OK;

  de265_error error = de265_push_data(decoder->ctx, data, size, (de265_PTS)pts, NULL);
  if (error != DE265_OK) {
    decoder->last_error = error;
    return DK_ERROR;
  }
  decoder->started = 1;
  de265_push_end_of_frame(decoder->ctx);
  return dk_drain(decoder);
}

int dk_flush(dk_decoder *decoder) {
  if (!decoder || !decoder->ctx) return DK_ERROR;
  dk_frames_reset(decoder);
  decoder->last_error = DE265_OK;
  decoder->last_warning = DE265_OK;

  de265_error error = de265_flush_data(decoder->ctx);
  if (error != DE265_OK) {
    decoder->last_error = error;
    return DK_ERROR;
  }
  decoder->started = 1;
  return dk_drain(decoder);
}

int dk_frame_count(const dk_decoder *decoder) {
  return decoder ? decoder->frame_count : 0;
}

int dk_last_error(const dk_decoder *decoder) {
  return decoder ? decoder->last_error : 0;
}

int dk_last_warning(const dk_decoder *decoder) {
  return decoder ? decoder->last_warning : 0;
}

/* ------------------------------------------------------------------------- */
/* Output                                                                     */
/* ------------------------------------------------------------------------- */

static int dk_blob_ensure(dk_decoder *decoder, size_t capacity) {
  if (decoder->blob_capacity >= capacity) return 0;
  uint8_t *grown = (uint8_t *)realloc(decoder->blob, capacity);
  if (!grown) return -1;
  decoder->blob = grown;
  decoder->blob_capacity = capacity;
  return 0;
}

static size_t dk_align8(size_t value) { return (value + 7u) & ~(size_t)7u; }

static void dk_write_i32(uint8_t *destination, int value) {
  uint32_t bits = (uint32_t)value;
  destination[0] = (uint8_t)(bits);
  destination[1] = (uint8_t)(bits >> 8);
  destination[2] = (uint8_t)(bits >> 16);
  destination[3] = (uint8_t)(bits >> 24);
}

static void dk_write_i64(uint8_t *destination, int64_t value) {
  uint64_t bits = (uint64_t)value;
  dk_write_i32(destination, (int)(uint32_t)bits);
  dk_write_i32(destination + 4, (int)(uint32_t)(bits >> 32));
}

const uint8_t *dk_output_blob(dk_decoder *decoder, int *size) {
  if (size) *size = 0;
  if (!decoder) return NULL;

  int count = decoder->frame_count;
  size_t header = 16;
  size_t metadata = (size_t)count * DK_FRAME_RECORD;
  size_t payload_base = dk_align8(header + metadata);

  size_t payload_size = 0;
  for (int index = 0; index < count; index++) {
    const struct dk_frame *frame = &decoder->frames[index];
    for (int plane = 0; plane < 3; plane++) {
      payload_size += (size_t)frame->plane_width[plane] * (size_t)frame->plane_height[plane];
    }
  }

  size_t total = payload_base + payload_size;
  if (dk_blob_ensure(decoder, total ? total : 1) < 0) return NULL;

  uint8_t *blob = decoder->blob;
  memset(blob, 0, payload_base);
  dk_write_i32(blob, (int)DK_OUTPUT_MAGIC);
  dk_write_i32(blob + 4, DK_OUTPUT_VERSION);
  dk_write_i32(blob + 8, count);
  dk_write_i32(blob + 12, (int)payload_base);

  size_t offset = 0;
  for (int index = 0; index < count; index++) {
    const struct dk_frame *frame = &decoder->frames[index];
    uint8_t *record = blob + header + (size_t)index * DK_FRAME_RECORD;
    dk_write_i32(record, frame->width);
    dk_write_i32(record + 4, frame->height);
    dk_write_i32(record + 8, frame->chroma);
    dk_write_i32(record + 12, frame->bit_depth);
    dk_write_i64(record + 16, frame->pts);
    dk_write_i32(record + 24, frame->plane_width[0] * frame->plane_height[0]);
    dk_write_i32(record + 28, frame->plane_width[1] * frame->plane_height[1]);
    dk_write_i32(record + 32, frame->plane_width[2] * frame->plane_height[2]);
    dk_write_i32(record + 36, (int)offset);
    dk_write_i32(record + 40, (int)(offset + (size_t)frame->plane_width[0] * frame->plane_height[0]));
    dk_write_i32(record + 44,
                 (int)(offset + (size_t)frame->plane_width[0] * frame->plane_height[0] +
                       (size_t)frame->plane_width[1] * frame->plane_height[1]));
    dk_write_i32(record + 48, frame->stride[0]);
    dk_write_i32(record + 52, frame->stride[1]);
    dk_write_i32(record + 56, frame->stride[2]);
    dk_write_i32(record + 60, 0);

    for (int plane = 0; plane < 3; plane++) {
      size_t plane_size = (size_t)frame->plane_width[plane] * frame->plane_height[plane];
      if (plane_size == 0) continue;
      memcpy(blob + payload_base + offset, frame->plane[plane], plane_size);
      offset += plane_size;
    }
  }

  if (size) *size = (int)total;
  return blob;
}
