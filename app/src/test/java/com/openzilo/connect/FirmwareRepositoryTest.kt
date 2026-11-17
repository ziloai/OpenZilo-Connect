package com.openzilo.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareRepositoryTest {
    private val tag = "firmware-V1.2.3.4-prod-aBcD1234"
    private val base = "https://github.com/ziloai/OpenZilo-Hardware/releases/download"

    private fun asset(tag: String = this.tag, size: Int = 123): JSONObject = JSONObject()
        .put("name", "openzilo.ota.zip")
        .put("size", size)
        .put("browser_download_url", "$base/$tag/openzilo.ota.zip")

    private fun release(
        tag: String = this.tag,
        id: Long = 42,
        publishedAt: String = "2026-11-16T12:00:00Z",
    ): JSONObject = JSONObject()
        .put("id", id).put("tag_name", tag)
        .put("draft", false).put("prerelease", false)
        .put("published_at", publishedAt).put("body", "# Changes\n- Fixed audio")
        .put("assets", JSONArray().put(asset(tag)))

    private fun parse(vararg releases: JSONObject): List<FirmwareRelease> =
        FirmwareRepository.parseReleases(JSONArray(releases.toList()).toString())

    @Test fun acceptsOnlyOfficialProductionOtaAndPreservesMetadata() {
        val result = parse(release()).single()
        assertEquals(42L, result.id)
        assertEquals("V1.2.3.4-prod-aBcD1234", result.version)
        assertEquals("2026-11-16T12:00:00Z", result.publishedAt)
        assertEquals("# Changes\n- Fixed audio", result.notes)
        assertEquals("$base/$tag/openzilo.ota.zip", result.downloadUrl)
        assertEquals(123, result.size)
    }

    @Test fun rejectsDraftPrereleaseAndNonBooleanFlags() {
        assertTrue(parse(
            release().put("draft", true), release().put("prerelease", true),
            release().put("draft", "false"), release().apply { remove("prerelease") },
        ).isEmpty())
    }

    @Test fun rejectsSdkSourceAndTestTags() {
        for (invalid in listOf(
            "v1.2.3", "sdk-V1.2.3.4-prod-abcd1234", "firmware-V1.2.3.4-test-abcd1234",
            "firmware-V1.2.3-prod-abcd1234", "firmware-V1.2.3.4-prod-abcd123",
            "firmware-V1.2.3.4-prod-abcd123g", "firmware-V1.2.3.4-prod-abcd1234-beta",
            "firmware-V1.2.3.4-prod-abcd1234\n",
        )) assertTrue(invalid, parse(release(invalid)).isEmpty())
        for (name in listOf("sdk.zip", "source.zip", "firmware.bin", "firmware.OTA.ZIP")) {
            assertTrue(parse(release().put("assets", JSONArray().put(asset().put("name", name)))).isEmpty())
        }
    }

    @Test fun requiresExactlyOneOtaEvenWhenAnotherCandidateIsInvalid() {
        assertTrue(parse(release().put("assets", JSONArray())).isEmpty())
        assertTrue(parse(release().put("assets", JSONArray().put(asset()).put(asset()))).isEmpty())
        assertTrue(parse(release().put("assets", JSONArray().put(asset()).put(asset(size = 0)))).isEmpty())
        val unrelated = JSONObject().put("name", "sdk.zip").put("size", 10)
        assertEquals(1, parse(release().put("assets", JSONArray().put(unrelated).put(asset()))).size)
    }

    @Test fun enforcesAssetSizeBoundariesAndIntegerTypes() {
        for (size in listOf(-1, 0, 614401, Int.MAX_VALUE)) {
            assertTrue(parse(release().put("assets", JSONArray().put(asset(size = size)))).isEmpty())
        }
        for (size in listOf(1, 614400)) {
            assertEquals(size, parse(release().put("assets", JSONArray().put(asset(size = size)))).single().size)
        }
        for (size in listOf<Any>("123", 1.5, 4294967297L, JSONObject.NULL)) {
            assertTrue(parse(release().put("assets", JSONArray().put(asset().put("size", size)))).isEmpty())
        }
    }

    @Test fun validatesStrictOfficialAssetUrls() {
        val good = "$base/$tag/openzilo.ota.zip"
        assertTrue(FirmwareRepository.validateDownloadUrl(good, tag))
        for (bad in listOf(
            good.replace("https:", "http:"),
            good.replace("github.com", "github.com.evil.example"),
            good.replace("github.com", "evil.example@github.com"),
            good.replace("github.com", "user:password@github.com"),
            good.replace("github.com", "github.com:443"),
            good.replace("github.com", "github.com:"),
            good.replace("github.com", "github.com."),
            good.replace("github.com", "release-assets.githubusercontent.com"),
            good.replace("OpenZilo-Hardware", "OpenZilo-SDK"),
            good.replace("/ziloai/", "/someone/"),
            good.replace(tag, "firmware-V1.2.3.5-prod-abcd1234"),
            "$good?download=1", "$good#fragment", "$good?", "$good#",
            "$base/$tag/../openzilo.ota.zip", "$base/$tag/%2e%2e/openzilo.ota.zip",
            "$base/$tag/subdir/openzilo.ota.zip", "$base/$tag/openzilo%2f.ota.zip",
            "$base/$tag/openzilo\\evil.ota.zip", "$base/$tag/source.zip",
            " https://github.com/ziloai/OpenZilo-Hardware/releases/download/$tag/file.ota.zip",
        )) {
            assertFalse(bad, FirmwareRepository.validateDownloadUrl(bad, tag))
            assertTrue(bad, parse(release().put("assets", JSONArray().put(
                asset().put("browser_download_url", bad),
            ))).isEmpty())
        }
        assertFalse(FirmwareRepository.validateDownloadUrl(good, "../invalid"))
    }

    @Test fun sortsFourNumericComponentsThenPublicationDateDescending() {
        val versions = listOf("1.2.3.9", "1.2.10.0", "1.10.0.0", "2.0.0.0", "1.2.3.10")
        val releases = versions.mapIndexed { index, version ->
            release("firmware-V$version-prod-abcd1234", index + 1L)
        } + release("firmware-V1.2.3.10-prod-eeeeeeee", 99, "2026-11-17T00:00:00Z")
        assertEquals(listOf(4L, 3L, 2L, 99L, 5L, 1L), parse(*releases.toTypedArray()).map { it.id })
    }

    @Test fun numericVersionComparisonCannotOverflow() {
        assertEquals(2L, parse(
            release("firmware-V9.0.0.0-prod-abcd1234", 1),
            release("firmware-V9223372036854775807.0.0.0-prod-abcd1234", 2),
        ).first().id)
        for (invalid in listOf(
            "firmware-V9223372036854775808.0.0.0-prod-abcd1234",
            "firmware-V${"0".repeat(64)}.0.0.0-prod-abcd1234",
        )) {
            assertTrue(parse(release(invalid)).isEmpty())
            assertFalse(FirmwareRepository.validateDownloadUrl("$base/$invalid/a.ota.zip", invalid))
        }
    }

    @Test fun acceptsOfficialZeroPaddedVersionWithoutNormalizingIt() {
        val official = "firmware-V2.001.0002.0002-prod-f333a6db"
        assertEquals("V2.001.0002.0002-prod-f333a6db", parse(release(official)).single().version)
        assertEquals(listOf(2L, 1L), parse(
            release(official, 1, "2026-11-15T00:00:00Z"),
            release("firmware-V2.1.2.2-prod-abcd1234", 2),
        ).map { it.id })
    }

    @Test fun extractsBothLanguagesWithoutDroppingUpdateItems() {
        val firmware = parse(release().put("body", """
            # Changes
            - Fix audio
            * Improve battery
            1. Keep connection
            ---
            ## 更新
            - 修复音频
            + 改善续航
            1) 保持连接
        """.trimIndent())).single()
        assertEquals("Changes\nFix audio\nImprove battery\nKeep connection", firmware.releaseNotes(false))
        assertEquals("更新\n修复音频\n改善续航\n保持连接", firmware.releaseNotes(true))
    }

    @Test fun missingTranslationFallsBackToOriginalNotes() {
        for (body in listOf("# Changes\n- Fix audio", "# Changes\n- Fix audio\n---\n")) {
            val firmware = parse(release().put("body", body)).single()
            assertEquals("Changes\nFix audio", firmware.releaseNotes(true))
            assertEquals("Changes\nFix audio", firmware.releaseNotes(false))
        }
        val chineseOnly = parse(release().put("body", "\n---\n## 更新\n- 修复音频")).single()
        assertEquals("更新\n修复音频", chineseOnly.releaseNotes(false))
        assertEquals("更新\n修复音频", chineseOnly.releaseNotes(true))
        assertEquals("", parse(release().put("body", JSONObject.NULL)).single().releaseNotes(true))
    }

    @Test fun handlesCrLfAndKeepsContinuationLines() {
        val firmware = parse(release().put("body", "# English\r\n- First\r\n  continued\r\n---\r\n# 中文\r\n- 第一项")).single()
        assertEquals("English\nFirst\ncontinued", firmware.releaseNotes(false))
        assertEquals("中文\n第一项", firmware.releaseNotes(true))
    }

    @Test fun ignoresIncompleteRecordsAndRejectsOversizedJson() {
        assertEquals(emptyList<FirmwareRelease>(), FirmwareRepository.parseReleases("[null,17,{}]"))
        assertTrue(parse(release().apply { remove("id") }, release().put("id", "42"),
            release().apply { remove("published_at") }).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            FirmwareRepository.parseReleases(" ".repeat(2 * 1024 * 1024 + 1))
        }
    }
}
