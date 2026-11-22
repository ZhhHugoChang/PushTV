package com.example.pushtv.ui.software

import com.example.pushtv.utils.VersionComparator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledAppUpdateTest {
    private val legacyApk = GitHubAsset(
        name = "PushTV_v1.6-legacy_release_armeabi-v7a.apk",
        downloadUrl = "https://example.org/legacy.apk",
        isRecommended = true
    )
    private val pushTv = InstalledApp(
        name = "PushTV",
        packageName = "com.example.pushtv",
        versionName = "1.6-legacy",
        remoteVersion = "v1.6.0",
        remoteStatus = "连接成功",
        assets = listOf(legacyApk)
    )

    @Test
    fun installedPushTvLegacyWithTheSameReleaseCanSelectApkWithoutUpgradeRecommendation() {
        assertEquals(Result.EQUAL, pushTv.releaseVersionOrder)
        assertNull(pushTv.recommendedUpdateAsset)
        assertEquals(listOf(legacyApk), pushTv.manualReleaseAssets)
    }

    @Test
    fun newerReleaseWithoutACompatibleApkDoesNotOfferOneClickUpdate() {
        val app = pushTv.copy(
            remoteVersion = "v1.7.0",
            assets = listOf(legacyApk.copy(isRecommended = false))
        )
        assertEquals(Result.REMOTE_NEWER, app.releaseVersionOrder)
        assertNull(app.recommendedUpdateAsset)
    }

    @Test
    fun recommendsACompatibleApkFromANewerRelease() {
        val newApk = apk("PushTV_v1.7-legacy_release_armeabi-v7a.apk").copy(isRecommended = true)
        val otherApk = apk("PushTV_v1.7_release_armeabi-v7a.apk")
        val app = pushTv.copy(remoteVersion = "v1.7.0", assets = listOf(newApk, otherApk))
        assertEquals(newApk, app.recommendedUpdateAsset)
        assertEquals(listOf(otherApk), app.manualReleaseAssets)
    }

    @Test
    fun treatsAPreReleaseAndItsStableReleaseAsEqual() {
        val app = pushTv.copy(versionName = "1.6-rc.1-legacy")
        assertEquals(Result.EQUAL, app.releaseVersionOrder)
        assertNull(app.recommendedUpdateAsset)
        assertEquals(listOf(legacyApk), app.manualReleaseAssets)
    }

    @Test
    fun detectsHigherVersionsWithCustomSuffixes() {
        val app = pushTv.copy(
            versionName = "1.6-custom",
            remoteVersion = "v1.7.0"
        )
        assertEquals(Result.REMOTE_NEWER, app.releaseVersionOrder)
    }

    @Test
    fun showsNoRecommendationFromAPreviousCheckWhenTheNextCheckFails() {
        val app = pushTv.copy(remoteVersion = "v1.7.0", remoteStatus = "连接失败")
        assertEquals(Result.UNKNOWN, app.releaseVersionOrder)
        assertNull(app.recommendedUpdateAsset)
        assertEquals(emptyList<GitHubAsset>(), app.manualReleaseAssets)
    }

    @Test
    fun offersManualSelectionAfterCheckingOlderOrUncomparableReleases() {
        val olderRelease = pushTv.copy(versionName = "1.7-legacy")
        val uncomparableRelease = pushTv.copy(versionName = "unknown")

        assertNull(olderRelease.recommendedUpdateAsset)
        assertNull(uncomparableRelease.recommendedUpdateAsset)
        assertEquals(listOf(legacyApk), olderRelease.manualReleaseAssets)
        assertEquals(listOf(legacyApk), uncomparableRelease.manualReleaseAssets)
    }

    @Test
    fun recommendsLegacyOnOlderAndroidEvenWhenAStandardBuildIsListedFirst() {
        val standard = apk("PushTV_v1.7_release_armeabi-v7a.apk")
        val legacy = apk("PushTV_v1.7-legacy_release_armeabi-v7a.apk")
        val assets = pushTvApksForDevice(listOf(standard, legacy), 27, listOf("armeabi-v7a"), "v1.7.0")
        assertEquals(legacy.copy(isRecommended = true), assets.single())
    }

    @Test
    fun recommendsPreferredAbiAndStandardBuildOnSupportedAndroid() {
        val legacy = apk("PushTV_v1.7-legacy_release_armeabi-v7a.apk")
        val arm32 = apk("PushTV_v1.7_release_armeabi-v7a.apk")
        val arm64 = apk("PushTV_v1.7_release_arm64-v8a.apk")
        val assets = listOf(legacy, arm32, arm64)

        val arm64Assets = pushTvApksForDevice(assets, 28, listOf("arm64-v8a", "armeabi-v7a"), "v1.7.0")
        val arm32Assets = pushTvApksForDevice(assets, 28, listOf("armeabi-v7a"), "v1.7.0")
        assertEquals(arm64.name, arm64Assets.single { it.isRecommended }.name)
        assertEquals(arm32.name, arm32Assets.single { it.isRecommended }.name)
    }

    @Test
    fun filtersOutKnownUnsupportedPushTvPackages() {
        val arm64 = apk("PushTV_v1.7_release_arm64-v8a.apk")
        val legacy = apk("PushTV_v1.7-legacy_release_armeabi-v7a.apk")
        val x86Assets = pushTvApksForDevice(listOf(arm64, legacy), 28, listOf("x86_64", "x86"), "v1.7.0")
        val olderAndroidAssets = pushTvApksForDevice(listOf(legacy), 22, listOf("armeabi-v7a"), "v1.7.0")
        assertEquals(emptyList<GitHubAsset>(), x86Assets)
        assertEquals(emptyList<GitHubAsset>(), olderAndroidAssets)
    }

    @Test
    fun filtersOutAnOlderApkAttachedToANewerRelease() {
        val assets = pushTvApksForDevice(listOf(legacyApk), 27, listOf("armeabi-v7a"), "v1.7.0")
        assertEquals(emptyList<GitHubAsset>(), assets)
    }

    private fun apk(name: String) = GitHubAsset(name, "https://example.org/$name")
}
