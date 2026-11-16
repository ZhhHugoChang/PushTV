package com.example.pushtv.logging

internal object LogSanitizer {
    private val authorization = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic)\\s+[^\\s,;]+")
    private val githubToken = Regex("(?i)\\b(?:ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b")
    private val urlCredentials = Regex("(https?://)([^/@\\s:]+):([^/@\\s]+)@", RegexOption.IGNORE_CASE)
    private val sensitiveParameter = Regex("(?i)([?&](?:token|access_token|password|passwd|secret|authorization)=)[^&#\\s]+")
    private val namedSecret = Regex("(?i)(\\b(?:token|password|passwd|secret)\\s*[:=]\\s*)[^\\s,;]+")

    fun sanitize(value: String): String = value
        .replace(authorization, "$1<redacted>")
        .replace(githubToken, "<redacted-github-token>")
        .replace(urlCredentials, "$1<redacted>@")
        .replace(sensitiveParameter, "$1<redacted>")
        .replace(namedSecret, "$1<redacted>")
}
