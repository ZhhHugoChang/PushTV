package com.example.pushtv.utils

import java.math.BigInteger

object VersionComparator {
    private val versionPattern = Regex(
        "^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
    )
    private val embeddedVersionPattern = Regex(
        "(?i)(?:^|[^0-9])v?(\\d+(?:\\.\\d+)*)(?:[-_]([0-9A-Za-z.-]+))?"
    )

    fun isRemoteNewer(local: String, remote: String?): Boolean {
        val localVersion = parse(local) ?: return false
        val remoteVersion = remote?.let(::parse) ?: return false
        return compare(remoteVersion, localVersion) > 0
    }

    fun canCompare(local: String, remote: String?): Boolean {
        return parse(local) != null && remote?.let(::parse) != null
    }

    private fun parse(value: String): ParsedVersion? {
        val normalized = value.trim()
        val match = versionPattern.matchEntire(normalized)
            ?: embeddedVersionPattern.find(normalized)
            ?: return null
        val numbers = match.groupValues[1].split('.').map { it.toBigInteger() }
        val preRelease = match.groupValues[2]
            .takeIf(String::isNotEmpty)
            ?.split('.')
            .orEmpty()
        return ParsedVersion(numbers, preRelease)
    }

    private fun compare(left: ParsedVersion, right: ParsedVersion): Int {
        val componentCount = maxOf(left.numbers.size, right.numbers.size)
        repeat(componentCount) { index ->
            val comparison = left.numbers.getOrElse(index) { BigInteger.ZERO }
                .compareTo(right.numbers.getOrElse(index) { BigInteger.ZERO })
            if (comparison != 0) return comparison
        }

        if (left.preRelease.isEmpty() && right.preRelease.isNotEmpty()) return 1
        if (left.preRelease.isNotEmpty() && right.preRelease.isEmpty()) return -1

        val preReleaseCount = maxOf(left.preRelease.size, right.preRelease.size)
        repeat(preReleaseCount) { index ->
            val leftPart = left.preRelease.getOrNull(index) ?: return -1
            val rightPart = right.preRelease.getOrNull(index) ?: return 1
            val comparison = comparePreReleasePart(leftPart, rightPart)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun comparePreReleasePart(left: String, right: String): Int {
        val leftNumber = left.toBigIntegerOrNull()
        val rightNumber = right.toBigIntegerOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    private data class ParsedVersion(
        val numbers: List<BigInteger>,
        val preRelease: List<String>
    )
}
