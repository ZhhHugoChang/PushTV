package com.example.pushtv.ui.backup

import com.example.pushtv.data.AppBackupStatus
import com.example.pushtv.data.BackupAppItem
import com.example.pushtv.data.CloudApkItem
import com.example.pushtv.data.CloudAppInstallStatus
import com.example.pushtv.data.WebDavRemoteFile
import java.io.File
import java.util.Locale

internal data class MatchedBackupLists(
    val apps: List<BackupAppItem>,
    val cloudApks: List<CloudApkItem>
)

internal object BackupListMatcher {
    private val invalidFileNameChars = Regex("[\\\\/:*?\"<>|\\s]")
    private val backupFilePattern = Regex("^(.*)_v([^_]+)\\.apk$", RegexOption.IGNORE_CASE)

    fun match(
        installedApps: List<BackupAppItem>,
        remoteFiles: List<WebDavRemoteFile>,
        localIncomingFiles: List<File>
    ): MatchedBackupLists {
        val localByFileName = firstBy(localIncomingFiles) { normalize(it.name) }
        val localByAppKey = firstBy(localIncomingFiles) { backupAppKey(it.name) }
        val remoteByFileName = firstBy(remoteFiles) { normalize(it.name) }
        val remoteByAppKey = firstBy(remoteFiles) { backupAppKey(it.name) }
        val installedBySanitizedName = firstBy(installedApps) { normalize(sanitize(it.name)) }
        val installedByDisplayName = firstBy(installedApps) { normalize(it.name) }

        val combined = installedApps.map { item ->
            val cleanName = sanitize(item.name)
            val exactFileName = "${cleanName}_v${item.versionName}.apk"
            val appKey = normalize(cleanName)
            val matchedLocalApk = localByFileName[normalize(exactFileName)] ?: localByAppKey[appKey]
            val exactMatch = remoteByFileName[normalize(exactFileName)]

            if (exactMatch != null) {
                item.copy(
                    backupStatus = AppBackupStatus.BACKED_UP,
                    remoteFileName = exactMatch.name,
                    remoteVersion = item.versionName,
                    localApkFile = matchedLocalApk
                )
            } else {
                val anyMatch = remoteByAppKey[appKey]
                if (anyMatch != null) {
                    item.copy(
                        backupStatus = AppBackupStatus.VERSION_MISMATCH,
                        remoteFileName = anyMatch.name,
                        remoteVersion = versionForApp(anyMatch.name, cleanName),
                        localApkFile = matchedLocalApk
                    )
                } else {
                    item.copy(
                        backupStatus = AppBackupStatus.NOT_BACKED_UP,
                        localApkFile = matchedLocalApk
                    )
                }
            }
        }

        val cloudItems = remoteFiles.map { remoteFile ->
            val fileName = remoteFile.name
            val parsed = parseBackupFileName(fileName)
            val parsedName = parsed?.first ?: fileName.removeSuffix(".apk").removeSuffix(".APK").replace("_", " ")
            val parsedVersion = parsed?.second
            val matchedLocalApk = localByFileName[normalize(fileName)]
            val matchedInstalledApp = installedBySanitizedName[normalize(sanitize(parsedName))]
                ?: installedByDisplayName[normalize(parsedName)]

            val installStatus = when {
                matchedInstalledApp == null -> CloudAppInstallStatus.NOT_INSTALLED
                parsedVersion != null && matchedInstalledApp.versionName == parsedVersion -> {
                    CloudAppInstallStatus.INSTALLED_SAME
                }
                else -> CloudAppInstallStatus.INSTALLED_DIFFERENT
            }

            CloudApkItem(
                fileName = fileName,
                appName = matchedInstalledApp?.name ?: parsedName,
                versionName = parsedVersion,
                size = remoteFile.size,
                installStatus = installStatus,
                installedVersion = matchedInstalledApp?.versionName,
                localApkFile = matchedLocalApk,
                installedPackageName = matchedInstalledApp?.packageName,
                icon = matchedInstalledApp?.icon,
                isSelected = false
            )
        }.sortedBy { it.appName }

        return MatchedBackupLists(apps = combined, cloudApks = cloudItems)
    }

    private fun parseBackupFileName(fileName: String): Pair<String, String>? {
        val match = backupFilePattern.find(fileName) ?: return null
        return match.groupValues[1].replace("_", " ") to match.groupValues[2]
    }

    private fun backupAppKey(fileName: String): String? {
        if (!fileName.endsWith(".apk", ignoreCase = true)) return null
        val stem = fileName.dropLast(4)
        val separatorIndex = stem.lastIndexOf("_v", ignoreCase = true)
        if (separatorIndex <= 0) return null
        return normalize(stem.substring(0, separatorIndex))
    }

    private fun versionForApp(fileName: String, cleanAppName: String): String? {
        val prefix = "${cleanAppName}_v"
        if (!fileName.startsWith(prefix, ignoreCase = true) || !fileName.endsWith(".apk", ignoreCase = true)) {
            return null
        }
        return fileName.substring(prefix.length, fileName.length - 4)
    }

    private fun sanitize(value: String): String = value.replace(invalidFileNameChars, "_")

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)

    private inline fun <T> firstBy(items: List<T>, keySelector: (T) -> String?): Map<String, T> {
        return buildMap {
            items.forEach { item ->
                keySelector(item)?.let { key -> putIfAbsent(key, item) }
            }
        }
    }
}
