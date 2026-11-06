/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265

/**
 * libde265 itself: version information for diagnostics and logging.
 */
public object Libde265 {
    /** Human-readable version, e.g. "1.1.3". */
    public val version: String get() = De265Native.versionString()

    /** BCD-encoded version, e.g. 0x01010300 for 1.1.3. */
    public val versionNumber: Int get() = De265Native.versionNumber()

    public val versionMajor: Int get() = De265Native.versionMajor()

    public val versionMinor: Int get() = De265Native.versionMinor()

    public val versionMaintenance: Int get() = De265Native.versionMaintenance()
}

/**
 * A stateful HEVC (H.265) decoder.
 *
 * Feed Annex-B access units to [decode] and read the pictures it produces;
 * call [flush] once after the last access unit to drain the pictures libde265
 * still holds for reordering. The decoder is not thread-safe: call it from one
 * thread at a time.
 *
 * ```kotlin
 * Libde265Decoder.create().use { decoder ->
 *     while (accessUnits.hasNext()) {
 *         decoder.decode(accessUnit, pts = presentationTimeUs)
 *             .forEach { render(it) }
 *     }
 *     decoder.flush().forEach { render(it) }
 * }
 * ```
 */
public class Libde265Decoder private constructor(
    private val handle: De265Handle,
) : AutoCloseable {
    private var closed = false

    /**
     * libde265 worker threads. Must be set before the first [decode] call;
     * the web targets always decode on the calling thread.
     */
    public var workerThreads: Int = 0
        set(value) {
            checkOpen()
            require(value >= 0) { "workerThreads must not be negative" }
            if (De265Native.decoderSetThreads(handle, value) < 0) {
                throw De265Exception(
                    "libde265 could not start $value worker thread(s); " +
                        "the decoder may already have started",
                )
            }
            field = value
        }

    /**
     * Decodes one Annex-B access unit. Parameter sets may be in-band (the
     * usual case for a live encoder) or pushed in an earlier access unit.
     *
     * Returns every picture the decoder completed, which may be empty when
     * libde265 needs more input (B-frame reordering, for example).
     */
    public fun decode(accessUnit: ByteArray, pts: Long = 0L): List<De265Frame> {
        checkOpen()
        require(accessUnit.isNotEmpty()) { "accessUnit must not be empty" }
        val blob = De265Native.decode(handle, accessUnit, pts)
        if (blob == null) {
            failIfError("decode")
            return emptyList()
        }
        return De265Output.parse(blob)
    }

    /** Drains the pictures libde265 still holds. Call once after the last access unit. */
    public fun flush(): List<De265Frame> {
        checkOpen()
        val blob = De265Native.flush(handle)
        if (blob == null) {
            failIfError("flush")
            return emptyList()
        }
        return De265Output.parse(blob)
    }

    /** libde265 warning code of the last call, 0 when there was none. */
    public val lastWarning: Int
        get() = if (closed) 0 else De265Native.lastWarning(handle)

    /** Human-readable form of [lastWarning], or `null` when there was none. */
    public val lastWarningText: String?
        get() = lastWarning.takeIf { it != 0 }?.let { De265Native.errorText(it) }

    override fun close() {
        if (closed) return
        closed = true
        De265Native.decoderClose(handle)
    }

    override fun toString(): String = "Libde265Decoder(${Libde265.version})"

    private fun checkOpen() {
        check(!closed) { "Libde265Decoder is closed" }
    }

    private fun failIfError(what: String) {
        val code = De265Native.lastError(handle)
        if (code != 0) {
            throw De265Exception("libde265 failed to $what: ${De265Native.errorText(code)} ($code)")
        }
    }

    public companion object {
        /** Creates a decoder. [workerThreads] starts libde265's thread pool up front. */
        public fun create(workerThreads: Int = 0): Libde265Decoder {
            val handle = De265Native.decoderCreate()
                ?: throw De265Exception("libde265 failed to allocate a decoder")
            val decoder = Libde265Decoder(handle)
            if (workerThreads > 0) {
                try {
                    decoder.workerThreads = workerThreads
                } catch (e: Throwable) {
                    decoder.close()
                    throw e
                }
            }
            return decoder
        }
    }
}
