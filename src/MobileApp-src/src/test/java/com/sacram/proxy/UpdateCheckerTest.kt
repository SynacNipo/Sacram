package com.sacram.proxy

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [UpdateChecker] — version parsing, newness checks,
 * and release-info parsing. Pure Kotlin, no Android dependencies.
 */
class UpdateCheckerTest {

    // ── parseVersion (via isNewer internals) ────────────────────────────

    @Test
    fun `isNewer returns true when major version is higher`() {
        assertTrue(UpdateChecker.isNewer("v2.0", "v1.99"))
    }

    @Test
    fun `isNewer returns true when minor version is higher`() {
        assertTrue(UpdateChecker.isNewer("v1.100", "v1.99"))
    }

    @Test
    fun `isNewer returns false when versions are equal`() {
        assertFalse(UpdateChecker.isNewer("v1.99", "v1.99"))
    }

    @Test
    fun `isNewer returns false when current is newer`() {
        assertFalse(UpdateChecker.isNewer("v1.98", "v1.99"))
    }

    @Test
    fun `isNewer handles v-prefixed tags`() {
        assertTrue(UpdateChecker.isNewer("v2.1", "v1.99"))
    }

    @Test
    fun `isNewer handles bare numeric tags`() {
        assertTrue(UpdateChecker.isNewer("2.1", "1.99"))
    }

    @Test
    fun `isNewer returns false for garbage input`() {
        assertFalse(UpdateChecker.isNewer("not-a-version", "v1.0"))
        assertFalse(UpdateChecker.isNewer("v1.0", "also-bad"))
    }

    // ── channel-aware isNewer ──────────────────────────────────────────

    @Test
    fun `beta channel - same version networkingpatch is newer than stable`() {
        assertTrue(UpdateChecker.isNewer("v1.99-networkingpatch", "v1.99", "beta"))
    }

    @Test
    fun `beta channel - same version networkingpatch is NOT newer than another patch`() {
        assertFalse(UpdateChecker.isNewer("v1.99-networkingpatch", "v1.99-patch", "beta"))
    }

    @Test
    fun `beta channel - same version networkingpatch is NOT newer than nightly`() {
        assertFalse(UpdateChecker.isNewer("v1.99-networkingpatch", "v1.99-nightly", "beta"))
    }

    @Test
    fun `stable channel - same version is never newer`() {
        assertFalse(UpdateChecker.isNewer("v1.99", "v1.99", "stable"))
    }

    @Test
    fun `stable channel - higher version is newer`() {
        assertTrue(UpdateChecker.isNewer("v2.0", "v1.99", "stable"))
    }

    // ── ReleaseInfo data class ─────────────────────────────────────────

    @Test
    fun `ReleaseInfo version extracts from tag`() {
        val r = UpdateChecker.ReleaseInfo(
            tag = "v1.100-networkingpatch",
            name = "v1.100 - Networking Patch (beta)",
            publishedAt = "2026-09-13T12:29:35Z",
            isBeta = true,
            apkSize = 15_000_000,
            apkUrl = "https://example.com/sacram.apk"
        )
        assertEquals("1.100", r.version)
        assertTrue(r.isBeta)
    }

    @Test
    fun `ReleaseInfo sizeLabel formats bytes correctly`() {
        val small = UpdateChecker.ReleaseInfo("v1.0", "", "", false, 512, "")
        assertEquals("512 B", small.sizeLabel)

        val mid = UpdateChecker.ReleaseInfo("v1.0", "", "", false, 5_120, "")
        assertEquals("5 KB", mid.sizeLabel)

        val large = UpdateChecker.ReleaseInfo("v1.0", "", "", false, 15_000_000, "")
        assertEquals("14.3 MB", large.sizeLabel)

        val zero = UpdateChecker.ReleaseInfo("v1.0", "", "", false, 0, "")
        assertEquals("—", zero.sizeLabel)
    }

    @Test
    fun `ReleaseInfo dateLabel formats ISO timestamp`() {
        val r = UpdateChecker.ReleaseInfo(
            tag = "v1.0", name = "", publishedAt = "2026-09-13T12:29:35Z",
            isBeta = false, apkSize = 0, apkUrl = ""
        )
        // Should contain the date components (timezone may vary by locale)
        assertTrue(r.dateLabel.contains("2026"))
        assertTrue(r.dateLabel.contains("Sep"))
        assertTrue(r.dateLabel.contains("13"))
    }

    @Test
    fun `ReleaseInfo label falls back to tag when name is empty`() {
        val r = UpdateChecker.ReleaseInfo("v1.0", "", "", false, 0, "")
        assertEquals("v1.0", r.label)
    }

    @Test
    fun `ReleaseInfo label uses name when non-empty`() {
        val r = UpdateChecker.ReleaseInfo("v1.0", "My Release", "", false, 0, "")
        assertEquals("My Release", r.label)
    }
}
