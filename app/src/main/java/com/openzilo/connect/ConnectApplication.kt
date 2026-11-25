package com.openzilo.connect

import android.app.Application
import com.openzilo.connect.core.RingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RingBinding(val address: String, val name: String, val identity: String, val autoConnect: Boolean)

class ConnectApplication : Application() {
    val controller by lazy { RingController(this) }
    private val bindingPreferences by lazy { getSharedPreferences("ring-binding", MODE_PRIVATE) }
    private val mutableBinding by lazy {
        val prefs = bindingPreferences
        val address = prefs.getString("address", null)
        val identity = prefs.getString("identity", null)
        MutableStateFlow(if (address != null && identity != null) {
            RingBinding(address, prefs.getString("name", "OpenZilo Ring").orEmpty(), identity, prefs.getBoolean("autoConnect", false))
        } else null)
    }
    internal val binding by lazy { mutableBinding.asStateFlow() }

    internal fun saveBinding(binding: RingBinding?) {
        val edit = bindingPreferences.edit().clear()
        if (binding != null) {
            edit.putString("address", binding.address).putString("name", binding.name)
                .putString("identity", binding.identity).putBoolean("autoConnect", binding.autoConnect)
        }
        edit.apply()
        mutableBinding.value = binding
    }
    val grants by lazy { GrantStore(this) }
    val calibrations by lazy { CalibrationStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val firmwareActive = MutableStateFlow(false)
    val upgrading = firmwareActive.asStateFlow()
    internal val firmwareCatalog by lazy { FirmwareCatalog(scope) }

    fun upgradeFirmware(bundle: ByteArray): Deferred<Unit> {
        check(!firmwareActive.value) { getString(R.string.ui_phase_updating) }
        firmwareActive.value = true
        return scope.async {
            try { controller.upgrade(bundle, FIRMWARE_PUBLIC_KEY) }
            finally { firmwareActive.value = false }
        }
    }
}
