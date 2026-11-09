package com.ymagenuz.snipdotranslate.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replaces [file] all at once, so a crash can't leave it half written. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    val directory = checkNotNull(file.absoluteFile.parentFile)
    directory.mkdirs()
    val temporary = File.createTempFile("${file.name}.", ".tmp", directory)
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.absoluteFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (failure: Exception) {
        temporary.delete()
        throw failure
    }
}
