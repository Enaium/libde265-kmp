/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265

import kotlin.js.js

// The glue is instantiated lazily: decoding and compiling the embedded module
// is the only expensive step, and it should not run for a process that never
// calls libde265.
private val de265WebInitialized: Unit by lazy {
    js(DE265_WEB_INIT_JS)(DE265_WASM_BASE64)
}

internal actual fun webInit() {
    de265WebInitialized
}

internal actual fun webVersionNumber(): Int =
    js("() => globalThis.__de265Web.versionNumber()")() as Int

internal actual fun webVersionString(): String =
    js("() => globalThis.__de265Web.versionString()")() as String

internal actual fun webVersionMajor(): Int =
    js("() => globalThis.__de265Web.versionMajor()")() as Int

internal actual fun webVersionMinor(): Int =
    js("() => globalThis.__de265Web.versionMinor()")() as Int

internal actual fun webVersionMaintenance(): Int =
    js("() => globalThis.__de265Web.versionMaintenance()")() as Int

internal actual fun webDecoderCreate(): Int =
    js("() => globalThis.__de265Web.decoderCreate()")() as Int

internal actual fun webDecoderClose(decoder: Int) {
    js("(d) => globalThis.__de265Web.decoderClose(d)")(decoder)
}

internal actual fun webDecoderSetThreads(decoder: Int, threads: Int): Int =
    js("(d, t) => globalThis.__de265Web.decoderSetThreads(d, t)")(decoder, threads) as Int

internal actual fun webDecode(decoder: Int, payload: String, ptsLow: Int, ptsHigh: Int): String =
    js("(d, p, lo, hi) => globalThis.__de265Web.decode(d, p, lo, hi)")(
        decoder,
        payload,
        ptsLow,
        ptsHigh,
    ) as String

internal actual fun webFlush(decoder: Int): String =
    js("(d) => globalThis.__de265Web.flush(d)")(decoder) as String

internal actual fun webFrameCount(decoder: Int): Int =
    js("(d) => globalThis.__de265Web.frameCount(d)")(decoder) as Int

internal actual fun webLastError(decoder: Int): Int =
    js("(d) => globalThis.__de265Web.lastError(d)")(decoder) as Int

internal actual fun webLastWarning(decoder: Int): Int =
    js("(d) => globalThis.__de265Web.lastWarning(d)")(decoder) as Int

internal actual fun webErrorText(code: Int): String =
    js("(c) => globalThis.__de265Web.errorText(c)")(code) as String
