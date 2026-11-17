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

class ConnectApplication : Application() {
    val controller by lazy { RingController(this) }
    val grants by lazy { GrantStore(this) }
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
