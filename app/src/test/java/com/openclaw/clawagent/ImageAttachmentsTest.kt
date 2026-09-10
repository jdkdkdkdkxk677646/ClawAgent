package com.openclaw.clawagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ImageAttachments]. No Robolectric needed: toDataUrl is
 * built on java.util.Base64, so everything here runs on the plain JUnit
 * runner (android.util.Base64 is unavailable on the JVM — that is exactly
 * why the production code uses java.util.Base64 instead).
 */
class ImageAttachmentsTest {

    // ── toDataUrl: correctness ────────────────────────────────────

    @Test
    fun `toDataUrl wraps bytes as base64 data url`() {
        val bytes = "hello".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "data:image/jpeg;base64,aGVsbG8=",
            ImageAttachments.toDataUrl("image/jpeg", bytes)
        )
    }

    @Test
    fun `toDataUrl matches RFC4648 test vectors`() {
        // RFC 4648 §10: "foobar" -> "Zm9vYmFy"
        assertEquals(
            "data:image/jpeg;base64,Zm9vYmFy",
            ImageAttachments.toDataUrl("image/jpeg", "foobar".toByteArray(Charsets.US_ASCII))
        )
        // 0xFB 0xFF 0xBF -> "4//+" (exercises the `+` and `/` alphabet chars)
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        assertEquals(
            "data:image/png;base64,4//+",
            ImageAttachments.toDataUrl("image/png", bytes)
        )
    }

    @Test
    fun `toDataUrl on empty bytes yields bare prefix`() {
        assertEquals(
            "data:image/jpeg;base64,",
            ImageAttachments.toDataUrl("image/jpeg", ByteArray(0))
        )
    }

    // ── toDataUrl: NO_WRAP equivalence & mime format ─────────────

    @Test
    fun `toDataUrl never emits line breaks (NO_WRAP equivalence)`() {
        // Long enough that a MIME-style encoder would insert CRLF; the basic
        // encoder (like android Base64.NO_WRAP) must not.
        val bytes = ByteArray(512) { (it * 31 % 256).toByte() }
        val url = ImageAttachments.toDataUrl("image/jpeg", bytes)
        assertFalse(url.contains('\n'))
        assertFalse(url.contains('\r'))
    }

    @Test
    fun `toDataUrl formats data-mime-base64 prefix`() {
        val url = ImageAttachments.toDataUrl("image/webp", byteArrayOf(0x89.toByte()))
        assertTrue(url.startsWith("data:image/webp;base64,"))
    }

    @Test
    fun `toDataUrl payload round-trips through base64 decode`() {
        // 257 is not a multiple of 3, so the payload carries '=' padding.
        val bytes = ByteArray(257) { (it * 7 % 256).toByte() }
        val url = ImageAttachments.toDataUrl("image/jpeg", bytes)
        val payload = url.substringAfter("base64,")
        val decoded = java.util.Base64.getDecoder().decode(payload)
        assertTrue(decoded.contentEquals(bytes))
    }

    // ── isTooLarge: boundaries ────────────────────────────────────

    @Test
    fun `isTooLarge is false at exact limit`() {
        val maxBytes = 4 * 1024 * 1024
        assertFalse(ImageAttachments.isTooLarge(ByteArray(maxBytes), maxBytes))
    }

    @Test
    fun `isTooLarge is true when one byte beyond limit`() {
        val maxBytes = 4 * 1024 * 1024
        assertTrue(ImageAttachments.isTooLarge(ByteArray(maxBytes + 1), maxBytes))
    }

    @Test
    fun `isTooLarge is false for empty array`() {
        assertFalse(ImageAttachments.isTooLarge(ByteArray(0), 0))
        assertFalse(ImageAttachments.isTooLarge(ByteArray(0), 4 * 1024 * 1024))
    }

    @Test
    fun `isTooLarge uses strict comparison with zero limit`() {
        assertTrue(ImageAttachments.isTooLarge(ByteArray(1), 0))
        assertFalse(ImageAttachments.isTooLarge(ByteArray(0), 0))
    }
}
