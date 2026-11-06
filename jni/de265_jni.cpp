/*
 * JNI bridge between cn.enaium.libde265.JniDe265 and the C shim.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * The declarations on the Kotlin side live in
 * libde265-kmp/src/jvmMain/kotlin/cn/enaium/libde265/De265Native.jvm.kt.
 *
 * Handles are the raw dk_decoder pointers stored in a jlong. Byte arrays are
 * copied into/out of the shim; the shim owns the decoded pictures only for the
 * duration of a call, which matches the binding's contract (the Kotlin side
 * copies the blob immediately).
 */
#include <jni.h>
#include <stdint.h>

#include "de265_kmp.h"

static dk_decoder *dk_decoder_from(jlong handle) {
  return (dk_decoder *)(intptr_t)handle;
}

/* Copies the packed blob of the last decode/flush call into a Java array.
 * Returns NULL when the shim produced no blob (buffered input or error). */
static jbyteArray dk_take_blob(JNIEnv *env, dk_decoder *decoder) {
  int size = 0;
  const uint8_t *blob = dk_output_blob(decoder, &size);
  if (!blob || size <= 0) return NULL;
  jbyteArray result = env->NewByteArray(size);
  if (!result) return NULL;
  env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(blob));
  return result;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeVersionNumber(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return (jint)dk_version_number();
}

JNIEXPORT jstring JNICALL
Java_cn_enaium_libde265_JniDe265_nativeVersionString(JNIEnv *env, jobject self) {
  (void)self;
  const char *version = dk_version_string();
  return env->NewStringUTF(version ? version : "");
}

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeVersionMajor(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return dk_version_major();
}

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeVersionMinor(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return dk_version_minor();
}

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeVersionMaintenance(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return dk_version_maintenance();
}

JNIEXPORT jlong JNICALL
Java_cn_enaium_libde265_JniDe265_nativeDecoderCreate(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return (jlong)(intptr_t)dk_decoder_create();
}

JNIEXPORT void JNICALL
Java_cn_enaium_libde265_JniDe265_nativeDecoderClose(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  dk_decoder_close(dk_decoder_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeDecoderSetThreads(JNIEnv *env, jobject self,
                                                         jlong handle, jint threads) {
  (void)env;
  (void)self;
  return dk_decoder_set_threads(dk_decoder_from(handle), threads);
}

JNIEXPORT jbyteArray JNICALL
Java_cn_enaium_libde265_JniDe265_nativeDecode(JNIEnv *env, jobject self, jlong handle,
                                              jbyteArray data, jlong pts) {
  (void)self;
  dk_decoder *decoder = dk_decoder_from(handle);
  if (!decoder || !data) return NULL;
  jsize size = env->GetArrayLength(data);
  if (size <= 0) return NULL;
  jbyte *bytes = env->GetByteArrayElements(data, NULL);
  if (!bytes) return NULL;
  int result = dk_decode(decoder, reinterpret_cast<const uint8_t *>(bytes), size,
                         (int64_t)pts);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  if (result != DK_OK) return NULL;
  return dk_take_blob(env, decoder);
}

JNIEXPORT jbyteArray JNICALL
Java_cn_enaium_libde265_JniDe265_nativeFlush(JNIEnv *env, jobject self, jlong handle) {
  (void)self;
  dk_decoder *decoder = dk_decoder_from(handle);
  if (!decoder) return NULL;
  if (dk_flush(decoder) != DK_OK) return NULL;
  return dk_take_blob(env, decoder);
}

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeFrameCount(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return dk_frame_count(dk_decoder_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeLastError(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return dk_last_error(dk_decoder_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_libde265_JniDe265_nativeLastWarning(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return dk_last_warning(dk_decoder_from(handle));
}

JNIEXPORT jstring JNICALL
Java_cn_enaium_libde265_JniDe265_nativeErrorText(JNIEnv *env, jobject self, jint code) {
  (void)self;
  const char *text = dk_error_text(code);
  return env->NewStringUTF(text ? text : "");
}

}  // extern "C"
