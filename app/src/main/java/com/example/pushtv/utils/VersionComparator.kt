package com.example.pushtv.utils

import java.math.BigInteger

object VersionComparator {
    enum class Result { REMOTE_NEWER, EQUAL, LOCAL_NEWER, UNKNOWN }

    private val versionPattern = Regex("(?i)(?:^|[^0-9])v?(\\d+(?:\\.\\d+)*)")

    fun isRemoteNewer(local: String, remote: String?): Boolean =
        compare(local, remote) == Result.REMOTE_NEWER

    fun canCompare(local: String, remote: String?): Boolean =
        compare(local, remote) != Result.UNKNOWN

    fun compare(local: String, remote: String?): Result {
        val localVersion = parse(local) ?: return Result.UNKNOWN
        val remoteVersion = remote?.let(::parse) ?: return Result.UNKNOWN
        val componentCount = maxOf(localVersion.size, remoteVersion.size)
        repeat(componentCount) { index ->
            val order = remoteVersion.getOrElse(index) { BigInteger.ZERO }
                .compareTo(localVersion.getOrElse(index) { BigInteger.ZERO })
            if (order > 0) return Result.REMOTE_NEWER
            if (order < 0) return Result.LOCAL_NEWER
        }
        return Result.EQUAL
    }

    private fun parse(value: String): List<BigInteger>? =
        versionPattern.find(value.trim())?.groupValues?.get(1)?.split('.')?.map { it.toBigInteger() }
}
