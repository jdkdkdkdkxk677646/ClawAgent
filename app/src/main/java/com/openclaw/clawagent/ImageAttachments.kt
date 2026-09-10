package com.openclaw.clawagent

import java.util.Base64

/**
 * Pure-JVM helpers shared by the gallery and camera image paths.
 *
 * `toDataUrl` deliberately uses java.util.Base64 instead of
 * android.util.Base64 so the exact same code runs — and is unit-testable —
 * on a plain JVM. Both encoders agree bit-for-bit for the no-line-wrap
 * (RFC 4648, `NO_WRAP`) flavor used here, so converging the old inline
 * string building onto this helper does not change any data URL the app
 * has ever produced.
 */
object ImageAttachments {

    /**
     * Wrap raw image bytes as a `data:<mime>;base64,<payload>` URL
     * (no line breaks, matching the previous `Base64.NO_WRAP` behavior).
     */
    fun toDataUrl(mime: String, bytes: ByteArray): String =
        "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** True when [bytes] strictly exceeds the per-image cap [maxBytes]. */
    fun isTooLarge(bytes: ByteArray, maxBytes: Int): Boolean = bytes.size > maxBytes
}
