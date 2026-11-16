package com.example.pushtv.logging

import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CrashReportExporter {
    private const val MAX_PUBLIC_REPORTS = 5
    private const val REPORT_PREFIX = "PushTV-crash-"

    fun exportPending(context: Context, pendingFile: File): String? {
        if (!pendingFile.exists() || pendingFile.length() == 0L) return null
        val timestamp = pendingFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        val fileName = REPORT_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp)) + ".txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(context, pendingFile, fileName)
        } else {
            exportLegacy(context, pendingFile, fileName)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun exportWithMediaStore(context: Context, source: File, fileName: String): String? {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/PushTV/"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not open Downloads output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            pruneMediaStoreReports(context, collection, relativePath)
            "$relativePath$fileName"
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun pruneMediaStoreReports(context: Context, collection: android.net.Uri, relativePath: String) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf(relativePath, "$REPORT_PREFIX%")
        resolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            var index = 0
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                if (index++ >= MAX_PUBLIC_REPORTS) {
                    val itemUri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    runCatching { resolver.delete(itemUri, null, null) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(context: Context, source: File, fileName: String): String? {
        val publicDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PushTV"
        )
        val publicResult = runCatching {
            publicDirectory.mkdirs()
            val destination = File(publicDirectory, fileName)
            source.copyTo(destination, overwrite = true)
            pruneLegacyReports(publicDirectory)
            destination.absolutePath
        }.getOrNull()
        if (publicResult != null) return publicResult

        val fallbackRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val fallbackDirectory = File(fallbackRoot, "diagnostics").apply { mkdirs() }
        return runCatching {
            val destination = File(fallbackDirectory, fileName)
            source.copyTo(destination, overwrite = true)
            pruneLegacyReports(fallbackDirectory)
            destination.absolutePath
        }.getOrNull()
    }

    private fun pruneLegacyReports(directory: File) {
        directory.listFiles { file -> file.isFile && file.name.startsWith(REPORT_PREFIX) }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_PUBLIC_REPORTS)
            ?.forEach { oldFile -> runCatching { oldFile.delete() } }
    }
}
