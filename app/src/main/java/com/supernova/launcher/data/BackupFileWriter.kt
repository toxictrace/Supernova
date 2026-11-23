package com.supernova.launcher.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Writes a complete snapshot beside its destination, then atomically publishes it.
internal object BackupFileWriter {
    fun writeAtomically(target: File, contents: String) {
        val directory = checkNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "can't create backup folder" }
        val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }
}
