/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.libde265

import kotlin.io.encoding.Base64

internal actual class De265Handle actual constructor() {
    /** WebAssembly pointer, always 32-bit. */
    internal var pointer: Int = 0
}

internal actual object De265Native {
    private var initialised = false

    private fun ensureInitialised() {
        if (!initialised) {
            webInit()
            initialised = true
        }
    }

    actual fun versionNumber(): Int {
        ensureInitialised()
        return webVersionNumber()
    }

    actual fun versionString(): String {
        ensureInitialised()
        return webVersionString()
    }

    actual fun versionMajor(): Int {
        ensureInitialised()
        return webVersionMajor()
    }

    actual fun versionMinor(): Int {
        ensureInitialised()
        return webVersionMinor()
    }

    actual fun versionMaintenance(): Int {
        ensureInitialised()
        return webVersionMaintenance()
    }

    actual fun decoderCreate(): De265Handle? {
        ensureInitialised()
        val pointer = webDecoderCreate()
        if (pointer == 0) return null
        return De265Handle().also { it.pointer = pointer }
    }

    actual fun decoderClose(handle: De265Handle) {
        ensureInitialised()
        if (handle.pointer != 0) {
            webDecoderClose(handle.pointer)
            handle.pointer = 0
        }
    }

    actual fun decoderSetThreads(handle: De265Handle, threads: Int): Int {
        ensureInitialised()
        return webDecoderSetThreads(handle.pointer, threads)
    }

    actual fun decode(handle: De265Handle, data: ByteArray, pts: Long): ByteArray? {
        ensureInitialised()
        val result = webDecode(
            handle.pointer,
            Base64.encode(data),
            pts.toInt(),
            (pts ushr 32).toInt(),
        )
        return parseWebResult(result, "decode")
    }

    actual fun flush(handle: De265Handle): ByteArray? {
        ensureInitialised()
        return parseWebResult(webFlush(handle.pointer), "flush")
    }

    actual fun lastError(handle: De265Handle): Int {
        ensureInitialised()
        return webLastError(handle.pointer)
    }

    actual fun lastWarning(handle: De265Handle): Int {
        ensureInitialised()
        return webLastWarning(handle.pointer)
    }

    actual fun errorText(code: Int): String {
        ensureInitialised()
        return webErrorText(code)
    }
}
