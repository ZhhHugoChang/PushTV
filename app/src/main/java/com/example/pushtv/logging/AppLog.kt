package com.example.pushtv.logging

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class AppLogLevel { INFO, WARN, ERROR }

data class AppLogEntry(
    val id: Long,
    val timestamp: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String
)

object AppLog {
    const val MAX_ENTRIES = 500

    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>(MAX_ENTRIES)
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val _entriesFlow = MutableStateFlow<List<AppLogEntry>>(emptyList())
    private val _remoteAccessEnabled = MutableStateFlow(false)
    private var logFile: File? = null
    private var pendingCrashFile: File? = null
    private var persistedLineCount = 0
    private var initialized = false

    val entriesFlow: StateFlow<List<AppLogEntry>> = _entriesFlow.asStateFlow()
    val remoteAccessEnabled: StateFlow<Boolean> = _remoteAccessEnabled.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
            logFile = File(directory, "pushtv-log.jsonl")
            pendingCrashFile = File(directory, "pending-crash.txt")
            loadEntriesLocked()
            initialized = true
        }
        val exportedPath = pendingCrashFile?.let { pending ->
            CrashReportExporter.exportPending(context, pending)?.also { pending.delete() }
        }
        info("Application", "PushTV process started")
        if (exportedPath != null) {
            info("CrashReport", "Previous crash report exported to $exportedPath")
        }
    }

    fun setRemoteAccessEnabled(enabled: Boolean) {
        _remoteAccessEnabled.value = enabled
        info("Diagnostics", if (enabled) "Remote log access enabled for this run" else "Remote log access disabled")
    }

    fun info(tag: String, message: String) = append(AppLogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = append(AppLogLevel.WARN, tag, message)

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.stackTraceToString()?.let { "\n$it" }.orEmpty()
        append(AppLogLevel.ERROR, tag, (message + detail).take(24_000))
    }

    fun recordFatalCrash(threadName: String, throwable: Throwable) {
        error("UncaughtException", "Fatal crash on thread $threadName", throwable)
        synchronized(lock) {
            val file = pendingCrashFile ?: return
            runCatching {
                val bytes = exportTextLocked().toByteArray(Charsets.UTF_8)
                FileOutputStream(file, false).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
            }
        }
    }

    fun snapshot(afterId: Long? = null): List<AppLogEntry> = synchronized(lock) {
        entries.filter { afterId == null || it.id > afterId }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            logFile?.writeText("")
            persistedLineCount = 0
            publishLocked()
        }
    }

    fun exportText(): String {
        return synchronized(lock) { exportTextLocked() }
    }

    private fun exportTextLocked(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return entries.joinToString("\n") { entry ->
            "${formatter.format(Date(entry.timestamp))} ${entry.level.name.padEnd(5)} ${entry.tag}: ${entry.message}"
        }
    }

    private fun append(level: AppLogLevel, tag: String, message: String) {
        val entry = AppLogEntry(
            id = nextId.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = LogSanitizer.sanitize(tag).take(80),
            message = LogSanitizer.sanitize(message)
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            appendToFileLocked(entry)
            publishLocked()
        }
    }

    private fun loadEntriesLocked() {
        val file = logFile ?: return
        if (!file.exists()) return
        file.useLines { lines ->
            val decoded = lines.mapNotNull(::decode).toList()
            persistedLineCount = decoded.size
            decoded.takeLast(MAX_ENTRIES).forEach(entries::addLast)
        }
        val highestId = entries.maxOfOrNull(AppLogEntry::id) ?: return
        nextId.updateAndGet { current -> maxOf(current, highestId) }
        publishLocked()
    }

    private fun appendToFileLocked(entry: AppLogEntry) {
        val file = logFile ?: return
        runCatching {
            file.appendText(encode(entry) + "\n")
            persistedLineCount++
            if (persistedLineCount > MAX_ENTRIES + 50) {
                rewriteFileLocked(file)
            }
        }
    }

    private fun rewriteFileLocked(file: File) {
        file.bufferedWriter().use { writer ->
            entries.forEach { entry -> writer.append(encode(entry)).append('\n') }
        }
        persistedLineCount = entries.size
    }

    private fun publishLocked() {
        _entriesFlow.value = entries.toList()
    }

    private fun encode(entry: AppLogEntry): String = JSONObject().apply {
        put("id", entry.id)
        put("timestamp", entry.timestamp)
        put("level", entry.level.name)
        put("tag", entry.tag)
        put("message", entry.message)
    }.toString()

    private fun decode(line: String): AppLogEntry? = runCatching {
        val json = JSONObject(line)
        AppLogEntry(
            id = json.getLong("id"),
            timestamp = json.getLong("timestamp"),
            level = AppLogLevel.valueOf(json.getString("level")),
            tag = json.getString("tag"),
            message = json.getString("message")
        )
    }.getOrNull()
}
