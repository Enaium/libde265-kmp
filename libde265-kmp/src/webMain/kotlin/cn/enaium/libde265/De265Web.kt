/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.libde265

import kotlin.io.encoding.Base64

/**
 * Web bridge shared by the Kotlin/JS and Kotlin/Wasm targets.
 *
 * The decoder is a stateful C++ library, so the JavaScript glue exposes one
 * function per shim entry point and keeps the exported function table. The
 * input access unit and the result blob cross the boundary as base64 strings,
 * because those are the two calls that carry bulk data.
 *
 * Pointers are 32-bit WebAssembly addresses and therefore `Int`s here.
 */

internal expect fun webInit()

internal expect fun webVersionNumber(): Int

internal expect fun webVersionString(): String

internal expect fun webVersionMajor(): Int

internal expect fun webVersionMinor(): Int

internal expect fun webVersionMaintenance(): Int

internal expect fun webDecoderCreate(): Int

internal expect fun webDecoderClose(decoder: Int)

internal expect fun webDecoderSetThreads(decoder: Int, threads: Int): Int

/** Returns `"<code>:<base64 blob>"`. */
internal expect fun webDecode(decoder: Int, payload: String, ptsLow: Int, ptsHigh: Int): String

/** Returns `"<code>:<base64 blob>"`. */
internal expect fun webFlush(decoder: Int): String

internal expect fun webFrameCount(decoder: Int): Int

internal expect fun webLastError(decoder: Int): Int

internal expect fun webLastWarning(decoder: Int): Int

internal expect fun webErrorText(code: Int): String

/** Splits the `"<code>:<base64>"` envelope the JavaScript glue returns. */
internal fun parseWebResult(result: String, what: String): ByteArray? {
    val separator = result.indexOf(':')
    check(separator > 0) { "malformed libde265 web result during $what: $result" }
    val code = result.substring(0, separator).toInt()
    return when (code) {
        DE265_BUFFERED -> null
        DE265_OK -> {
            val encoded = result.substring(separator + 1)
            if (encoded.isEmpty()) {
                throw De265Exception("libde265 reported output during $what but sent no payload")
            }
            Base64.decode(encoded)
        }
        else -> null
    }
}
