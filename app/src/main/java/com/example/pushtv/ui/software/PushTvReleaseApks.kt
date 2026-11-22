package com.example.pushtv.ui.software

import com.example.pushtv.utils.VersionComparator

// Official release APK names and minimum SDKs come from the flavors in app/build.gradle.kts.
private val pushTvReleaseApk = Regex(
    """PushTV_v(\d+(?:\.\d+)*)(-legacy)?_release_(arm64-v8a|armeabi-v7a)\.apk""",
    RegexOption.IGNORE_CASE
)

internal fun pushTvApksForDevice(
    assets: List<GitHubAsset>,
    sdk: Int,
    supportedAbis: List<String>,
    releaseTag: String?
): List<GitHubAsset> {
    val candidates = assets.mapNotNull { asset ->
        val match = pushTvReleaseApk.matchEntire(asset.name)
            ?: return@mapNotNull ReleaseApkCandidate(asset)
        val (version, edition, architecture) = match.destructured
        val legacy = edition.isNotEmpty()
        val abi = architecture.lowercase()
        val abiIndex = supportedAbis.indexOf(abi)
        if (legacy && abi != "armeabi-v7a") return@mapNotNull ReleaseApkCandidate(asset)
        if (abiIndex < 0 || sdk < (if (legacy) 23 else 28)) return@mapNotNull null
        if (VersionComparator.compare(version, releaseTag) != VersionComparator.Result.EQUAL) {
            return@mapNotNull null
        }
        ReleaseApkCandidate(asset, abiIndex, legacy)
    }
    val recommended = candidates.filter { it.abiIndex != null }
        .minWithOrNull(compareBy<ReleaseApkCandidate> { it.abiIndex }.thenBy { it.legacy })
    return candidates.map { it.asset.copy(isRecommended = it === recommended) }
}

private data class ReleaseApkCandidate(
    val asset: GitHubAsset,
    val abiIndex: Int? = null,
    val legacy: Boolean = false
)
