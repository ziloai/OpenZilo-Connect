package com.openzilo.connect

import org.junit.Assert.*
import org.junit.Test

class GrantPolicyTest {
    @Test fun packageAndCertificateMustBothMatch() {
        val grant = AppGrant("creative.app", "Creative", "cert-one", setOf("info", "sensors"))
        assertTrue(GrantPolicy.matches(grant, AppPrincipal(1001, "creative.app", "Creative", "cert-one"), "sensors"))
        assertFalse(GrantPolicy.matches(grant, AppPrincipal(1001, "other.app", "Creative", "cert-one"), "sensors"))
        assertFalse(GrantPolicy.matches(grant, AppPrincipal(1001, "creative.app", "Creative", "cert-two"), "sensors"))
        assertFalse(GrantPolicy.matches(grant, AppPrincipal(1001, "creative.app", "Creative", "cert-one"), "firmware"))
    }
    @Test fun revokingAudioLeavesDeviceInfoAllowed() {
        val app = AppPrincipal(1001, "creative.app", "Creative", "cert-one")
        val grant = AppGrant(app.packageName, app.label, app.certificate, setOf("info", "audio"))
        val changed = grant.copy(scopes = GrantPolicy.withScope(grant.scopes, "audio", false))
        assertFalse(GrantPolicy.matches(changed, app, GrantPolicy.topicScope("live")))
        assertTrue(GrantPolicy.matches(changed, app, GrantPolicy.operationScope("system.info")))
        assertTrue(GrantPolicy.withScope(changed.scopes, "info", false).isEmpty())
    }
    @Test fun allOperationsAreExplicitlyScoped() {
        assertEquals(21, GrantPolicy.operationScopes.size)
        assertEquals("firmware", GrantPolicy.operationScope("firmware.upgrade"))
        assertEquals("audio", GrantPolicy.operationScope("audio.clear"))
        assertEquals("recording", GrantPolicy.operationScope("recording.start"))
        GrantPolicy.operationScopes.values.forEach { assertTrue(it in GrantPolicy.scopes) }
    }
    @Test fun unsupportedOperationsAndScopesFailClosed() {
        for (operation in listOf("raw", "send", "", "SYSTEM.INFO")) {
            assertThrows(IllegalArgumentException::class.java) { GrantPolicy.operationScope(operation) }
        }
        assertThrows(IllegalArgumentException::class.java) { GrantPolicy.requireScopes(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { GrantPolicy.requireScopes(setOf("info", "everything")) }
        GrantPolicy.requireScopes(GrantPolicy.scopes)
    }
    @Test fun unsolicitedPacketsCannotEscapeTheirPermissionDomain() {
        assertEquals("imu", GrantPolicy.topic(0x605))
        assertEquals("gesture", GrantPolicy.topic(0x701))
        assertEquals("gesture", GrantPolicy.topic(0x704))
        for (command in 0x50f..0x511) assertEquals("live", GrantPolicy.topic(command))
        assertEquals("recording", GrantPolicy.topic(0x50a))
        for (command in listOf(0x504, 0x505, 0x508)) assertEquals("audio", GrantPolicy.topic(command))
        assertEquals("time", GrantPolicy.topic(0x401))
        for (command in listOf(0x102, 0x108, 0x1ff, 0x304, 0xffff)) assertNull(GrantPolicy.topic(command))
        assertEquals("sensors", GrantPolicy.topicScope("gesture"))
        assertEquals("audio", GrantPolicy.topicScope("live"))
        assertThrows(IllegalArgumentException::class.java) { GrantPolicy.topicScope("all") }
    }
}
