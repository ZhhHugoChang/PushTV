package com.example.pushtv.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun detectsNewerNumericVersion() {
        assertTrue(VersionComparator.isRemoteNewer("1.9.9", "v1.10.0"))
    }

    @Test
    fun doesNotTreatOlderOrEquivalentVersionAsUpdate() {
        assertFalse(VersionComparator.isRemoteNewer("2.0", "1.9.9"))
        assertFalse(VersionComparator.isRemoteNewer("1.2", "v1.2.0"))
    }

    @Test
    fun releaseIsNewerThanPreRelease() {
        assertTrue(VersionComparator.isRemoteNewer("1.0.0-rc.1", "1.0.0"))
        assertFalse(VersionComparator.isRemoteNewer("1.0.0", "1.0.0-beta.2"))
    }

    @Test
    fun supportsCommonReleaseAndAndroidVersionLabels() {
        assertTrue(VersionComparator.isRemoteNewer("1.2.2 (build 42)", "release-v1.2.3"))
        assertTrue(VersionComparator.isRemoteNewer("1.2.2", "app_1.2.3"))
        assertFalse(VersionComparator.isRemoteNewer("1.2.3 (42)", "release-v1.2.3"))
    }

    @Test
    fun rejectsStatusTextAndUnsupportedVersionNames() {
        assertFalse(VersionComparator.isRemoteNewer("1.0.0", null))
        assertFalse(VersionComparator.isRemoteNewer("1.0.0", "未发布版本"))
        assertFalse(VersionComparator.isRemoteNewer("unknown", "1.0.0"))
        assertFalse(VersionComparator.canCompare("unknown", "1.0.0"))
        assertTrue(VersionComparator.canCompare("1.0.0 (42)", "release-v1.1.0"))
    }
}
