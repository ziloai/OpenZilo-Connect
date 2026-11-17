package com.openzilo.connect

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel

class FirmwareCatalogTest {
    @Test fun loadingRefreshingAndSelectingNeverDownloadFirmware() {
        val releases = listOf(
            FirmwareRelease(1, "V2.001.0002.0002-prod-f333a6db", "2026-11-11", "", "", 100),
            FirmwareRelease(2, "V2.001.0002.0001-prod-0e37d864", "2026-10-22", "", "", 100),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<Long>()
        val catalog = FirmwareCatalog(scope, { releases }, { requested += it.id; awaitCancellation() })
        try {
            catalog.load()
            assertEquals(releases.first(), catalog.state.value.selected)
            catalog.select(releases.last())
            catalog.refresh()
            assertEquals(releases.last(), catalog.state.value.selected)
            assertTrue(requested.isEmpty())
            catalog.download()
            catalog.download()
            assertEquals(listOf(2L), requested)
            assertTrue(catalog.state.value.downloading)
            catalog.select(releases.first())
            assertFalse(catalog.state.value.downloading)
            assertEquals(listOf(2L), requested)
            catalog.download()
            assertEquals(listOf(2L, 1L), requested)
        } finally { scope.cancel() }
    }

    @Test fun downloadFailureWaitsForExplicitRetry() {
        val release = FirmwareRelease(1, "V2.001.0002.0002-prod-f333a6db", "2026-11-11", "", "", 100)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val catalog = FirmwareCatalog(scope, { listOf(release) }, { attempts++; throw java.io.IOException("synthetic failure") })
        try {
            catalog.load()
            assertEquals(0, attempts)
            catalog.download()
            assertEquals(FirmwareFailure.Download, catalog.state.value.failure)
            assertFalse(catalog.state.value.downloading)
            catalog.load()
            assertEquals(1, attempts)
            catalog.retry()
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }

    @Test fun detectsNewerVersionsNumerically() {
        assertTrue(firmwareVersionIsNewer("V2.001.0002.0010-prod-f333a6db", "V2.001.0002.0009"))
        assertTrue(firmwareVersionIsNewer("V2.001.0002.0002-prod-f333a6db", "V2.001.0002.0001-prod-0e37d864"))
        assertFalse(firmwareVersionIsNewer("V2.001.0002.0001-prod-0e37d864", "V2.001.0002.0002-prod-f333a6db"))
        assertFalse(firmwareVersionIsNewer("V2.001.0002.0002-prod-f333a6db", "V2.001.0002.0002-prod-f333a6db"))
    }

    @Test fun doesNotGuessVersionOrderForUnknownVersionsOrRevisionHashes() {
        assertFalse(firmwareVersionIsNewer("V2.001.0002.0002-prod-f333a6db", "unknown"))
        assertFalse(firmwareVersionIsNewer("V2.001.0002.0002-prod-f333a6db", "V2.001.0002.0002-prod-eeeeeeee"))
        assertFalse(firmwareVersionIsNewer("V9223372036854775808.0.0.0", "V1.0.0.0"))
        assertFalse(firmwareVersionIsNewer("latest", "V1.0.0.0"))
    }

    @Test fun pinsTheOfficialProductionSigningKey() {
        val encoded = FIRMWARE_PUBLIC_KEY.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        val bytes = Base64.getDecoder().decode(encoded)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        assertEquals("EC", key.algorithm)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("40be2e249e62dd3b2d97bd09c037038a63327da1f84ae8a17369b82684936ec4", fingerprint)
    }
}
