/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */
package com.metrolist.music.podcast.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class MoonshineNativePackagingTest {
    @Test
    fun `vendored arm64 onnxruntime is 16KB aligned v1_23_2 without kleidiai`() {
        val libraryFile = locateOnnxruntimeLibrary()
        val bytes = libraryFile.readBytes()
        val actualSha256 = sha256Hex(bytes)

        assertEquals(EXPECTED_NO_KLEIDIAI_SHA256, actualSha256)
        assertNotEquals(BROKEN_AAR_KLEIDIAI_SHA256, actualSha256)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x7F.toByte(), buffer.get(0))
        assertEquals('E'.code.toByte(), buffer.get(1))
        assertEquals('L'.code.toByte(), buffer.get(2))
        assertEquals('F'.code.toByte(), buffer.get(3))
        assertEquals(ELF_CLASS_64, buffer.get(4).toInt() and 0xFF)
        assertEquals(ELF_DATA_LSB, buffer.get(5).toInt() and 0xFF)
        assertEquals(ET_DYN, buffer.getShort(16).toInt() and 0xFFFF)
        assertEquals(EM_AARCH64, buffer.getShort(18).toInt() and 0xFFFF)

        val phOff = buffer.getLong(32).toInt()
        val phEntSize = buffer.getShort(54).toInt() and 0xFFFF
        val phNum = buffer.getShort(56).toInt() and 0xFFFF
        assertTrue("Expected ELF64 Phdr entry size >= 56", phEntSize >= 56)
        assertTrue("Expected program headers", phNum > 0)

        var loadSegmentCount = 0
        for (i in 0 until phNum) {
            val offset = phOff + i * phEntSize
            val pType = buffer.getInt(offset)
            if (pType == PT_LOAD) {
                loadSegmentCount++
                val pAlign = buffer.getLong(offset + 48)
                assertTrue(
                    "PT_LOAD segment $i alignment $pAlign must be >= 16 KB (0x4000)",
                    pAlign >= 0x4000L,
                )
            }
        }
        assertTrue("Expected at least one PT_LOAD segment", loadSegmentCount > 0)

        val dynstr = readNullTerminatedSection(bytes, buffer, ".dynstr")
        assertTrue("Missing VERS_1.23.2 symbol version", dynstr.contains("VERS_1.23.2"))
        assertTrue("Missing OrtGetApiBase symbol", dynstr.contains("OrtGetApiBase"))
        assertTrue(
            "Missing OrtSessionOptionsAppendExecutionProvider_CPU symbol",
            dynstr.contains("OrtSessionOptionsAppendExecutionProvider_CPU"),
        )
        assertFalse("libonnxruntime.so must not require libc++_shared.so", dynstr.contains("libc++_shared.so"))
    }

    private fun locateOnnxruntimeLibrary(): File {
        val candidates = listOf(
            File("src/moonshine/ort/jni/arm64-v8a/libonnxruntime.so"),
            File("app/src/moonshine/ort/jni/arm64-v8a/libonnxruntime.so"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate libonnxruntime.so from ${File(".").absolutePath}")
    }

    private fun readNullTerminatedSection(bytes: ByteArray, buffer: ByteBuffer, targetName: String): String {
        val shOff = buffer.getLong(40).toInt()
        val shEntSize = buffer.getShort(58).toInt() and 0xFFFF
        val shNum = buffer.getShort(60).toInt() and 0xFFFF
        val shStrNdx = buffer.getShort(62).toInt() and 0xFFFF
        assertTrue("Invalid shstrtab index", shStrNdx in 0 until shNum)

        val strTabHdrOff = shOff + shStrNdx * shEntSize
        val strTabOffset = buffer.getLong(strTabHdrOff + 24).toInt()
        val strTabSize = buffer.getLong(strTabHdrOff + 32).toInt()

        for (i in 0 until shNum) {
            val hdr = shOff + i * shEntSize
            val nameIdx = buffer.getInt(hdr)
            val name = readCString(bytes, strTabOffset + nameIdx, strTabOffset + strTabSize)
            if (name == targetName) {
                val secOffset = buffer.getLong(hdr + 24).toInt()
                val secSize = buffer.getLong(hdr + 32).toInt()
                return String(bytes, secOffset, secSize, Charsets.ISO_8859_1)
            }
        }
        error("ELF section $targetName not found")
    }

    private fun readCString(bytes: ByteArray, start: Int, limit: Int): String {
        var end = start
        while (end < limit && end < bytes.size && bytes[end] != 0.toByte()) {
            end++
        }
        return String(bytes, start, end - start, Charsets.US_ASCII)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ELF_CLASS_64 = 2
        const val ELF_DATA_LSB = 1
        const val ET_DYN = 3
        const val EM_AARCH64 = 183
        const val PT_LOAD = 1
        const val EXPECTED_NO_KLEIDIAI_SHA256 =
            "8cad9eba519db70b9da8ff6d31044ff48dd02d939dba90bc557d0f467e955464"
        const val BROKEN_AAR_KLEIDIAI_SHA256 =
            "86e85022fc539963177e478c8e9a8a8219bc3b0d7515bf5297a62986dbc45e7a"
    }
}
