package xyz.sakulik.d20.app.domain.common.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256DigestTest {
    @Test
    fun calculatesKnownDigestAcrossChunks() {
        val accumulator = Sha256Accumulator()
        accumulator.update("a".toByteArray(), 0, 1)
        accumulator.update("bc".toByteArray(), 0, 2)

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223" +
                "b00361a396177a9cb410ff61f20015ad",
            accumulator.finish().toHex()
        )
    }

    @Test
    fun parsesUppercaseAndMatchesBinaryDigest() {
        val expected = Sha256Digest.parseHex(
            "BA7816BF8F01CFEA414140DE5DAE2223" +
                "B00361A396177A9CB410FF61F20015AD"
        )
        val accumulator = Sha256Accumulator()
        val content = "abc".toByteArray()
        accumulator.update(content, 0, content.size)

        assertNotNull(expected)
        assertTrue(requireNotNull(expected).matches(accumulator.finish()))
    }

    @Test
    fun rejectsMalformedOrWhitespaceWrappedDigest() {
        assertNull(Sha256Digest.parseHex("abc"))
        assertNull(Sha256Digest.parseHex("g".repeat(64)))
        assertNull(Sha256Digest.parseHex(" ${"0".repeat(64)}"))
        assertNull(Sha256Digest.parseHex("${"0".repeat(64)}\n"))
    }

    @Test
    fun rejectsDifferentDigest() {
        val zeroDigest = requireNotNull(Sha256Digest.parseHex("0".repeat(64)))
        val accumulator = Sha256Accumulator()
        val content = "abc".toByteArray()
        accumulator.update(content, 0, content.size)

        assertFalse(zeroDigest.matches(accumulator.finish()))
    }

    @Test
    fun acceptsOnlyPathSafePluginIds() {
        assertTrue(isSafePluginId("dnd_5e"))
        assertFalse(isSafePluginId("../dnd_5e"))
        assertFalse(isSafePluginId("DND_5E"))
        assertFalse(isSafePluginId("a"))
        assertFalse(isSafePluginId("a".repeat(65)))
    }

    @Test(expected = IllegalStateException::class)
    fun cannotFinishAccumulatorTwice() {
        val accumulator = Sha256Accumulator()
        accumulator.finish()
        accumulator.finish()
    }
}
