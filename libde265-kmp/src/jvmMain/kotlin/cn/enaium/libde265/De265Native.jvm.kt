/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265

// =========================================================================
// JNI bridge: declarations are shared by the JVM and Android targets.
// =========================================================================

internal object JniDe265 {
    init {
        NativeLoader.load()
    }

    external fun nativeVersionNumber(): Int

    external fun nativeVersionString(): String

    external fun nativeVersionMajor(): Int

    external fun nativeVersionMinor(): Int

    external fun nativeVersionMaintenance(): Int

    external fun nativeDecoderCreate(): Long

    external fun nativeDecoderClose(handle: Long)

    external fun nativeDecoderSetThreads(handle: Long, threads: Int): Int

    external fun nativeDecode(handle: Long, data: ByteArray, pts: Long): ByteArray?

    external fun nativeFlush(handle: Long): ByteArray?

    external fun nativeFrameCount(handle: Long): Int

    external fun nativeLastError(handle: Long): Int

    external fun nativeLastWarning(handle: Long): Int

    external fun nativeErrorText(code: Int): String
}

internal actual class De265Handle actual constructor() {
    internal var pointer: Long = 0
}

internal actual object De265Native {
    actual fun versionNumber(): Int = JniDe265.nativeVersionNumber()

    actual fun versionString(): String = JniDe265.nativeVersionString()

    actual fun versionMajor(): Int = JniDe265.nativeVersionMajor()

    actual fun versionMinor(): Int = JniDe265.nativeVersionMinor()

    actual fun versionMaintenance(): Int = JniDe265.nativeVersionMaintenance()

    actual fun decoderCreate(): De265Handle? {
        val pointer = JniDe265.nativeDecoderCreate()
        if (pointer == 0L) return null
        return De265Handle().also { it.pointer = pointer }
    }

    actual fun decoderClose(handle: De265Handle) {
        if (handle.pointer != 0L) {
            JniDe265.nativeDecoderClose(handle.pointer)
            handle.pointer = 0
        }
    }

    actual fun decoderSetThreads(handle: De265Handle, threads: Int): Int =
        JniDe265.nativeDecoderSetThreads(handle.pointer, threads)

    actual fun decode(handle: De265Handle, data: ByteArray, pts: Long): ByteArray? =
        JniDe265.nativeDecode(handle.pointer, data, pts)

    actual fun flush(handle: De265Handle): ByteArray? = JniDe265.nativeFlush(handle.pointer)

    actual fun lastError(handle: De265Handle): Int = JniDe265.nativeLastError(handle.pointer)

    actual fun lastWarning(handle: De265Handle): Int = JniDe265.nativeLastWarning(handle.pointer)

    actual fun errorText(code: Int): String = JniDe265.nativeErrorText(code)
}
