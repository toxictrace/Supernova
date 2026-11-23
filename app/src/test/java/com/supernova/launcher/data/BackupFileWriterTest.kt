package com.supernova.launcher.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupFileWriterTest {
    @Test
    fun publishesCompleteUtf8SnapshotAndRemovesTemporaryFile() {
        val directory = Files.createTempDirectory("supernova-backup-test").toFile()
        try {
            val target = File(directory, "layout-1.json")
            val payload = """{"label":"Проверка","items":[1,2,3]}"""

            BackupFileWriter.writeAtomically(target, payload)

            assertEquals(payload, target.readText(Charsets.UTF_8))
            assertEquals(listOf(target.name), directory.list()?.toList())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
