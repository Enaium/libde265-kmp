/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.libde265

import kotlin.JsFun

private var de265WebInitialized = false

internal actual fun webInit() {
    if (!de265WebInitialized) {
        initDe265Web(DE265_WASM_BASE64)
        de265WebInitialized = true
    }
}

@JsFun(DE265_WEB_INIT_JS)
private external fun initDe265Web(wasmBase64: String)

@JsFun("() => globalThis.__de265Web.versionNumber()")
private external fun versionNumber(): Int

@JsFun("() => globalThis.__de265Web.versionString()")
private external fun versionString(): String

@JsFun("() => globalThis.__de265Web.versionMajor()")
private external fun versionMajor(): Int

@JsFun("() => globalThis.__de265Web.versionMinor()")
private external fun versionMinor(): Int

@JsFun("() => globalThis.__de265Web.versionMaintenance()")
private external fun versionMaintenance(): Int

@JsFun("() => globalThis.__de265Web.decoderCreate()")
private external fun decoderCreate(): Int

@JsFun("(d) => globalThis.__de265Web.decoderClose(d)")
private external fun decoderClose(decoder: Int)

@JsFun("(d, t) => globalThis.__de265Web.decoderSetThreads(d, t)")
private external fun decoderSetThreads(decoder: Int, threads: Int): Int

@JsFun("(d, p, lo, hi) => globalThis.__de265Web.decode(d, p, lo, hi)")
private external fun decode(decoder: Int, payload: String, ptsLow: Int, ptsHigh: Int): String

@JsFun("(d) => globalThis.__de265Web.flush(d)")
private external fun flush(decoder: Int): String

@JsFun("(d) => globalThis.__de265Web.frameCount(d)")
private external fun frameCount(decoder: Int): Int

@JsFun("(d) => globalThis.__de265Web.lastError(d)")
private external fun lastError(decoder: Int): Int

@JsFun("(d) => globalThis.__de265Web.lastWarning(d)")
private external fun lastWarning(decoder: Int): Int

@JsFun("(c) => globalThis.__de265Web.errorText(c)")
private external fun errorText(code: Int): String

internal actual fun webVersionNumber(): Int = versionNumber()

internal actual fun webVersionString(): String = versionString()

internal actual fun webVersionMajor(): Int = versionMajor()

internal actual fun webVersionMinor(): Int = versionMinor()

internal actual fun webVersionMaintenance(): Int = versionMaintenance()

internal actual fun webDecoderCreate(): Int = decoderCreate()

internal actual fun webDecoderClose(decoder: Int) = decoderClose(decoder)

internal actual fun webDecoderSetThreads(decoder: Int, threads: Int): Int =
    decoderSetThreads(decoder, threads)

internal actual fun webDecode(decoder: Int, payload: String, ptsLow: Int, ptsHigh: Int): String =
    decode(decoder, payload, ptsLow, ptsHigh)

internal actual fun webFlush(decoder: Int): String = flush(decoder)

internal actual fun webFrameCount(decoder: Int): Int = frameCount(decoder)

internal actual fun webLastError(decoder: Int): Int = lastError(decoder)

internal actual fun webLastWarning(decoder: Int): Int = lastWarning(decoder)

internal actual fun webErrorText(code: Int): String = errorText(code)
