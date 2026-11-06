/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.libde265

import cnames.structs.dk_decoder
import de265_kmp.dk_decode
import de265_kmp.dk_decoder_close
import de265_kmp.dk_decoder_create
import de265_kmp.dk_decoder_set_threads
import de265_kmp.dk_error_text
import de265_kmp.dk_flush
import de265_kmp.dk_last_error
import de265_kmp.dk_last_warning
import de265_kmp.dk_output_blob
import de265_kmp.dk_version_maintenance
import de265_kmp.dk_version_major
import de265_kmp.dk_version_minor
import de265_kmp.dk_version_number
import de265_kmp.dk_version_string
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

internal actual class De265Handle actual constructor() {
    internal var pointer: CPointer<dk_decoder>? = null
}

internal actual object De265Native {
    actual fun versionNumber(): Int = dk_version_number().toInt()

    actual fun versionString(): String = dk_version_string()?.toKString() ?: ""

    actual fun versionMajor(): Int = dk_version_major()

    actual fun versionMinor(): Int = dk_version_minor()

    actual fun versionMaintenance(): Int = dk_version_maintenance()

    actual fun decoderCreate(): De265Handle? {
        val pointer = dk_decoder_create() ?: return null
        return De265Handle().also { it.pointer = pointer }
    }

    actual fun decoderClose(handle: De265Handle) {
        val pointer = handle.pointer ?: return
        dk_decoder_close(pointer)
        handle.pointer = null
    }

    actual fun decoderSetThreads(handle: De265Handle, threads: Int): Int {
        val pointer = handle.pointer ?: return DE265_ERROR
        return dk_decoder_set_threads(pointer, threads)
    }

    actual fun decode(handle: De265Handle, data: ByteArray, pts: Long): ByteArray? {
        val pointer = handle.pointer ?: return null
        val code = data.usePinned {
            dk_decode(pointer, it.addressOf(0).reinterpret(), data.size, pts)
        }
        return if (code == DE265_OK) readBlob(pointer) else null
    }

    actual fun flush(handle: De265Handle): ByteArray? {
        val pointer = handle.pointer ?: return null
        return if (dk_flush(pointer) == DE265_OK) readBlob(pointer) else null
    }

    actual fun lastError(handle: De265Handle): Int {
        val pointer = handle.pointer ?: return DE265_ERROR
        return dk_last_error(pointer)
    }

    actual fun lastWarning(handle: De265Handle): Int {
        val pointer = handle.pointer ?: return 0
        return dk_last_warning(pointer)
    }

    actual fun errorText(code: Int): String = dk_error_text(code)?.toKString() ?: ""

    private fun readBlob(pointer: CPointer<dk_decoder>): ByteArray? = memScoped {
        val size = alloc<IntVar>()
        val blob = dk_output_blob(pointer, size.ptr) ?: return@memScoped null
        if (size.value <= 0) return@memScoped null
        blob.readBytes(size.value)
    }
}

private const val DE265_ERROR = -1
