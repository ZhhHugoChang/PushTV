package com.example.pushtv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class GitHubProxyNode(
    val id: String,
    val name: String,
    val prefix: String
) {
    fun downloadUrl(assetUrl: String): String = prefix + assetUrl
}

data class GitHubProxySettings(
    val selection: String = DIRECT,
    val customNodes: List<GitHubProxyNode> = emptyList()
) {
    val nodes: List<GitHubProxyNode> get() = builtInNodes + customNodes

    fun hasSelection(id: String): Boolean = id == DIRECT || id == AUTO || nodes.any { it.id == id }

    val selectedNode: GitHubProxyNode? get() = nodes.firstOrNull { it.id == selection }

    companion object {
        const val DIRECT = "direct"
        const val AUTO = "auto"
        const val MAX_CUSTOM_NODES = 10

        val builtInNodes = listOf(
            GitHubProxyNode("builtin-cdn", "cdn.gh-proxy.org", "https://cdn.gh-proxy.org/"),
            GitHubProxyNode("builtin-ghfast", "ghfast.top", "https://ghfast.top/"),
            GitHubProxyNode("builtin-standard", "gh-proxy.org", "https://gh-proxy.org/"),
            GitHubProxyNode("builtin-edgeone", "edgeone.gh-proxy.org", "https://edgeone.gh-proxy.org/"),
            GitHubProxyNode("builtin-chjina", "gh.chjina.com", "https://gh.chjina.com/"),
            GitHubProxyNode("builtin-monlor", "gh.monlor.com", "https://gh.monlor.com/"),
            GitHubProxyNode("builtin-ednovas", "github.ednovas.xyz", "https://github.ednovas.xyz/"),
            GitHubProxyNode("builtin-fastgit", "fastgit.cc", "https://fastgit.cc/"),
            GitHubProxyNode("builtin-wget", "wget.la", "https://wget.la/")
        )

        fun normalizePrefix(input: String): String {
            val raw = input.trim()
            val url = raw.toHttpUrlOrNull()
            require(
                raw.length <= 300 && raw.startsWith("https://", ignoreCase = true) &&
                    raw.none { it.isWhitespace() || it == '\\' || it == '@' } &&
                    url != null && url.host.contains('.') && url.host.any { it.isLetter() } &&
                    !url.host.endsWith(".local") && url.query == null && url.fragment == null &&
                    !url.encodedPath.contains("://")
            ) { "请输入 HTTPS 代理域名前缀，例如 https://proxy.example.com/" }
            return url.toString().trimEnd('/') + "/"
        }

        fun isReleaseApk(url: String): Boolean {
            val asset = url.toHttpUrlOrNull() ?: return false
            val path = asset.pathSegments
            return asset.isHttps && asset.port == 443 && asset.host in setOf("github.com", "www.github.com") &&
                asset.username.isEmpty() && asset.password.isEmpty() && asset.query == null && asset.fragment == null &&
                path.size == 6 && path.take(2).all { it.isNotBlank() } && path[2] == "releases" &&
                ((path[3] == "download" && path[4].isNotBlank()) || (path[3] == "latest" && path[4] == "download")) &&
                path.last().endsWith(".apk", ignoreCase = true)
        }

        fun sha256Digest(value: String): String? {
            return value.takeIf { it.matches(Regex("sha256:[0-9a-f]{64}", RegexOption.IGNORE_CASE)) }
                ?.substringAfter(':')?.lowercase()
        }
    }
}
