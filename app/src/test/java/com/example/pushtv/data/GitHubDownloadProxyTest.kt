package com.example.pushtv.data

import com.example.pushtv.network.GitHubProxyClient
import com.example.pushtv.network.GitHubProxyClient.Probe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDownloadProxyTest {
    private val apk = "https://github.com/owner/repo/releases/download/v1.0/Test.apk"

    @Test
    fun probeToneDistinguishesLatencyBoundariesAndFailureStates() {
        assertEquals("fast", Probe("available", 999).tone)
        assertEquals("moderate", Probe("available", 1000).tone)
        assertEquals("moderate", Probe("available", 1999).tone)
        assertEquals("slow", Probe("available", 2000).tone)
        assertEquals("timeout", Probe("timeout").tone)
        assertEquals("unavailable", Probe("unavailable").tone)
    }

    @Test
    fun onlyMatchingGitHubReleaseApksAreEligible() {
        assertTrue(GitHubProxySettings.isReleaseApk(apk))
        assertTrue(GitHubProxySettings.isReleaseApk(apk.replace("download/v1.0", "latest/download")))
        assertFalse(GitHubProxySettings.isReleaseApk(apk.replace("github.com", "github.com.example.org")))
        assertFalse(GitHubProxySettings.isReleaseApk(apk.replace(".apk", ".zip")))
        assertFalse(GitHubProxySettings.isReleaseApk(apk.replace("releases/download", "raw/main")))
        assertFalse(GitHubProxySettings.isReleaseApk("$apk?token=secret"))
    }

    @Test
    fun automaticModeStartsWithTheFirstVerifiedRouteAndKeepsOtherRoutesForFallback() = runBlocking {
        val custom = GitHubProxyNode("custom", "Custom", "https://custom.example.org/")
        val duplicate = custom.copy(id = "same-prefix")
        val settings = GitHubProxySettings(GitHubProxySettings.AUTO, listOf(custom, duplicate))
        val routes = GitHubProxyClient.selectRoutes(settings) { node ->
            if (node == custom) true else awaitCancellation()
        }
        assertEquals(custom, routes.first())
        assertTrue(null in routes)
        assertFalse(duplicate in routes)
    }

    @Test
    fun manualMirrorIsProbedFirstAndAFailedSelectionCanFallBackToDirect() = runBlocking {
        val picked = GitHubProxySettings.builtInNodes.first()
        val settings = GitHubProxySettings(selection = picked.id)
        val checked = mutableListOf<GitHubProxyNode?>()

        val preferred = GitHubProxyClient.selectRoutes(settings) { node -> checked.add(node); true }
        assertEquals(listOf(picked), checked)
        assertEquals(picked, preferred.first())
        assertTrue(null in preferred)

        checked.clear()
        val fallback = GitHubProxyClient.selectRoutes(settings) { node -> checked.add(node); node == null }
        assertEquals(picked, checked.first())
        assertNull(fallback.first())
        assertFalse(picked in fallback)
        assertEquals(listOf<GitHubProxyNode?>(null), GitHubProxyClient.selectRoutes(GitHubProxySettings()) {
            error("Direct mode should skip the probe")
        })
    }

    @Test
    fun httpsPrefixIsNormalizedAndJoinedWithoutEncodingAsset() {
        val prefix = GitHubProxySettings.normalizePrefix(" https://Proxy.Example.org/download// ")
        assertEquals("https://proxy.example.org/download/", prefix)
        assertEquals(prefix + apk, GitHubProxyNode("custom", "Custom", prefix).downloadUrl(apk))
        for (invalid in listOf("http://example.org", "https://127.0.0.1", "https://box.local",
            "https://name@example.org", "https://example.org/?token=123", "https://example.org/#part")) {
            assertTrue(runCatching { GitHubProxySettings.normalizePrefix(invalid) }.isFailure)
        }
    }

    @Test
    fun digestOnlyAcceptsSha256() {
        val hash = "AB".repeat(32)
        assertEquals(hash.lowercase(), GitHubProxySettings.sha256Digest("sha256:$hash"))
        assertNull(GitHubProxySettings.sha256Digest("sha1:$hash"))
        assertNull(GitHubProxySettings.sha256Digest("sha256:123"))
    }
}
