package com.example.pushtv.utils

import java.io.File
import java.io.IOException

/** File replacement that works on Android 6 without java.nio.file. */
internal object CompatibleFileMover {
    fun moveReplacing(source: File, destination: File) {
        require(source.isFile) { "Source file does not exist: ${source.absolutePath}" }
        if (source.absoluteFile == destination.absoluteFile) return

        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Could not create destination directory: ${parent.absolutePath}")
            }
        }

        // Android/Linux normally performs this as an atomic rename on the same filesystem.
        if (source.renameTo(destination)) return

        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not replace destination file: ${destination.absolutePath}")
        }
        if (source.renameTo(destination)) return

        try {
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }

        if (!source.delete()) {
            destination.delete()
            throw IOException("Could not remove source file after copying: ${source.absolutePath}")
        }
    }
}
