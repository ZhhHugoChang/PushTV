package com.example.pushtv.ui.backup

import com.example.pushtv.data.AppBackupStatus
import com.example.pushtv.data.BackupAppItem
import com.example.pushtv.data.CloudAppInstallStatus
import com.example.pushtv.data.WebDavRemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class BackupListMatcherTest {
    private fun app(name: String, version: String = "1.0") = BackupAppItem(
        name = name,
        packageName = "pkg.${name.lowercase().replace(' ', '.')}",
        icon = null,
        versionName = version,
        apkFileLength = 1L,
        sourceDir = "/source/$name.apk"
    )

    @Test
    fun matchesExactAndDifferentRemoteVersionsUsingIndexes() {
        val exactApp = app("Exact App", "2.0")
        val oldApp = app("Old App", "2.0")
        val localExact = File("Exact_App_v2.0.apk")
        val localOtherVersion = File("Old_App_v1.0.apk")
        val remoteFiles = listOf(
            WebDavRemoteFile("Exact_App_v2.0.apk", 20L),
            WebDavRemoteFile("Old_App_v1.0.apk", 10L)
        )

        val result = BackupListMatcher.match(
            installedApps = listOf(exactApp, oldApp),
            remoteFiles = remoteFiles,
            localIncomingFiles = listOf(localExact, localOtherVersion)
        )

        assertEquals(AppBackupStatus.BACKED_UP, result.apps[0].backupStatus)
        assertSame(localExact, result.apps[0].localApkFile)
        assertEquals(AppBackupStatus.VERSION_MISMATCH, result.apps[1].backupStatus)
        assertEquals("1.0", result.apps[1].remoteVersion)
        assertSame(localOtherVersion, result.apps[1].localApkFile)
    }

    @Test
    fun cloudItemsMatchInstalledAppsAndLocalFilesCaseInsensitively() {
        val installed = app("Living Room", "3.0")
        val local = File("living_room_v3.0.APK")

        val result = BackupListMatcher.match(
            installedApps = listOf(installed),
            remoteFiles = listOf(WebDavRemoteFile("Living_Room_v3.0.apk", 42L)),
            localIncomingFiles = listOf(local)
        )

        val cloud = result.cloudApks.single()
        assertEquals("Living Room", cloud.appName)
        assertEquals(CloudAppInstallStatus.INSTALLED_SAME, cloud.installStatus)
        assertEquals(installed.packageName, cloud.installedPackageName)
        assertSame(local, cloud.localApkFile)
    }

    @Test
    fun versionMismatchKeepsVersionsContainingUnderscores() {
        val result = BackupListMatcher.match(
            installedApps = listOf(app("Preview App", "2.0")),
            remoteFiles = listOf(WebDavRemoteFile("Preview_App_v1.0_beta.apk", 42L)),
            localIncomingFiles = emptyList()
        )

        assertEquals(AppBackupStatus.VERSION_MISMATCH, result.apps.single().backupStatus)
        assertEquals("1.0_beta", result.apps.single().remoteVersion)
    }
}
