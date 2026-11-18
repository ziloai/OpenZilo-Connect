package com.openzilo.connect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AppGrant(val packageName: String, val label: String, val certificate: String, val scopes: Set<String>)
data class AppPrincipal(val uid: Int, val packageName: String, val label: String, val certificate: String)
data class GrantRequest(val token: String, val principal: AppPrincipal, val scopes: Set<String>, val expiresAt: Long)

object GrantPolicy {
    val scopes = setOf("info", "recording", "audio", "sensors", "logs", "time", "firmware")
    val operationScopes = mapOf(
        "system.info" to "info", "capabilities" to "info", "connection.rssi" to "info",
        "recording.config" to "recording", "recording.configure" to "recording",
        "recording.start" to "recording", "recording.stop" to "recording", "recording.status" to "recording",
        "audio.count" to "audio", "audio.info" to "audio", "audio.quick" to "audio",
        "audio.read" to "audio", "audio.end" to "audio", "audio.clear" to "audio", "live.subscribe" to "audio",
        "imu.start" to "sensors", "imu.stop" to "sensors", "logs.storage" to "logs", "logs.read" to "logs",
        "time.reply" to "time", "firmware.upgrade" to "firmware",
    )
    val topicScopes = mapOf("imu" to "sensors", "gesture" to "sensors", "live" to "audio", "audio" to "audio", "recording" to "recording", "time" to "time")
    fun requireScopes(requested: Set<String>) {
        require(requested.isNotEmpty() && requested.size <= scopes.size && scopes.containsAll(requested)) { "Unknown or empty permissions" }
    }
    fun operationScope(operation: String): String = operationScopes[operation] ?: throw IllegalArgumentException("Unknown operation")
    fun topicScope(topic: String): String = topicScopes[topic] ?: throw IllegalArgumentException("Unknown topic")
    fun topic(command: Int): String? = when (command) {
        0x605 -> "imu"
        in 0x701..0x704 -> "gesture"
        in 0x50f..0x511 -> "live"
        0x50a -> "recording"
        0x504, 0x505, 0x508 -> "audio"
        0x401 -> "time"
        else -> null
    }
    internal fun withScope(current: Set<String>, scope: String, enabled: Boolean): Set<String> =
        if (enabled) current + scope else current - scope

    fun matches(grant: AppGrant, principal: AppPrincipal, scope: String): Boolean =
        grant.packageName == principal.packageName && grant.certificate == principal.certificate && scope in grant.scopes
}

class GrantStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("authorized-apps", Context.MODE_PRIVATE)
    private val pending = LinkedHashMap<String, GrantRequest>()
    private val lastRequest = mutableMapOf<Int, Long>()
    private val random = SecureRandom()
    private val mutableGrants = MutableStateFlow(load())
    val grants: StateFlow<List<AppGrant>> = mutableGrants.asStateFlow()

    fun caller(): AppPrincipal = principal(Binder.getCallingUid())

    fun principal(uid: Int): AppPrincipal {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: throw SecurityException("Caller is not installed")
        if (packages.size != 1) throw SecurityException("Shared UIDs are not supported")
        val name = packages.single()
        val info = if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION") pm.getPackageInfo(name, PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else @Suppress("DEPRECATION") info.signatures
        if (signatures.isNullOrEmpty()) throw SecurityException("Missing application signer")
        val certificate = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(":")
        val app = pm.getApplicationInfo(name, 0)
        return AppPrincipal(uid, name, pm.getApplicationLabel(app).toString(), certificate)
    }

    @Synchronized
    fun requireAccess(principal: AppPrincipal, scope: String) {
        if (scope !in GrantPolicy.scopes) throw SecurityException("Unknown permission")
        val current = principal(principal.uid)
        if (current.packageName != principal.packageName || current.certificate != principal.certificate) throw SecurityException("Caller identity changed")
        if (principal.uid == context.applicationInfo.uid && principal.packageName == context.packageName) return
        if (mutableGrants.value.none { GrantPolicy.matches(it, current, scope) }) throw SecurityException("Permission not granted: $scope")
    }

    @Synchronized
    fun newRequest(principal: AppPrincipal, scopes: Set<String>): GrantRequest {
        GrantPolicy.requireScopes(scopes)
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { it.value.expiresAt <= now }
        lastRequest.entries.removeAll { now - it.value > 300_000 }
        if (pending.size >= 16 || lastRequest[principal.uid]?.let { now - it < 3000 } == true) throw SecurityException("Too many authorization requests")
        pending.entries.removeAll { it.value.principal.uid == principal.uid }
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return GrantRequest(token, principal, scopes.toSet(), now + 300_000).also {
            pending[token] = it
            lastRequest[principal.uid] = now
        }
    }

    @Synchronized
    fun pendingRequest(token: String): GrantRequest? = pending[token]?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }

    @Synchronized
    fun setRequestedScope(token: String, scope: String, enabled: Boolean) {
        val request = pendingRequest(token) ?: throw SecurityException("Authorization request expired")
        if (scope !in request.scopes) throw SecurityException("Permission was not requested")
        val current = principal(request.principal.uid)
        if (current.packageName != request.principal.packageName || current.certificate != request.principal.certificate) throw SecurityException("Application changed during authorization")
        setScope(current, scope, enabled)
    }

    @Synchronized
    fun setManagedScope(grant: AppGrant, scope: String, enabled: Boolean) {
        if (scope !in GrantPolicy.scopes) throw SecurityException("Unknown permission")
        val current = principal(context.packageManager.getPackageUid(grant.packageName, 0))
        if (current.packageName != grant.packageName || current.certificate != grant.certificate) throw SecurityException("Application changed during authorization")
        if (mutableGrants.value.none { it.packageName == grant.packageName && it.certificate == grant.certificate }) {
            throw SecurityException("Application is not managed")
        }
        setScope(current, scope, enabled)
    }

    private fun setScope(current: AppPrincipal, scope: String, enabled: Boolean) {
        val previous = mutableGrants.value.firstOrNull { it.packageName == current.packageName && it.certificate == current.certificate }
        val before = previous?.scopes.orEmpty()
        val after = GrantPolicy.withScope(before, scope, enabled)
        if (before == after) return
        val grant = AppGrant(current.packageName, current.label, current.certificate, after)
        save(mutableGrants.value.filterNot { it.packageName == current.packageName } + grant)
    }

    private fun save(items: List<AppGrant>) {
        val json = JSONArray()
        items.forEach { item -> json.put(JSONObject().put("package", item.packageName).put("label", item.label).put("certificate", item.certificate).put("scopes", JSONArray(item.scopes.sorted()))) }
        check(preferences.edit().putString("grants", json.toString()).commit()) { "Could not save authorization" }
        mutableGrants.value = items.sortedBy { it.packageName }
    }

    private fun load(): List<AppGrant> = try {
        val array = JSONArray(preferences.getString("grants", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val scopes = item.getJSONArray("scopes")
            AppGrant(item.getString("package"), item.getString("label"), item.getString("certificate"), (0 until scopes.length()).map { scopes.getString(it) }.toSet())
        }.filter { GrantPolicy.scopes.containsAll(it.scopes) }
    } catch (_: Exception) { emptyList() }
}
