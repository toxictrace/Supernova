package com.supernova.launcher.data.imports

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFilesTest {

    @Test
    fun expandedLimitIncludesEntriesTheImporterWouldSkip() {
        val bytes = zip(
            "unrecognized.bin" to byteArrayOf(1, 2, 3, 4, 5),
            "layout.xml" to "<map/>".toByteArray()
        )
        val input = BoundedZipInputStream(ByteArrayInputStream(bytes), 4)
        var rejected = false
        try {
            input.nextEntry
            input.nextEntry
        } catch (_: UnreadableBackupException) {
            rejected = true
        } finally {
            runCatching { input.close() }
        }
        assertTrue("unrecognized ZIP entries must count toward the expansion limit", rejected)
    }

    @Test
    fun acceptsArchiveAtTheExactExpansionLimit() {
        val bytes = zip("layout.xml" to byteArrayOf(1, 2, 3, 4))
        BoundedZipInputStream(ByteArrayInputStream(bytes), 4).use { input ->
            assertEquals("layout.xml", input.nextEntry.name)
            assertEquals(4, input.readBytes().size)
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
