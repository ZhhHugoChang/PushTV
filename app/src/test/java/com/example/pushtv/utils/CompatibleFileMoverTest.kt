package com.example.pushtv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CompatibleFileMoverTest {
    @Test
    fun moveReplacing_replacesExistingDestinationAndRemovesSource() {
        val directory = Files.createTempDirectory("pushtv-file-mover").toFile()
        try {
            val source = directory.resolve("download.part").apply { writeText("new apk") }
            val destination = directory.resolve("download.apk").apply { writeText("old apk") }

            CompatibleFileMover.moveReplacing(source, destination)

            assertFalse(source.exists())
            assertTrue(destination.exists())
            assertEquals("new apk", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
