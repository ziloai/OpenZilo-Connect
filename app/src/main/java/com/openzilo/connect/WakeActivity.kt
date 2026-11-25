package com.openzilo.connect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Zero-UI translucent trampoline Activity allowing client apps to wake the Connect process
 * and resume a saved ring foreground connection on OEM ROMs (e.g., Xiaomi HyperOS WakePathChecker)
 * that block cold background `bindService` calls across packages.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = application as ConnectApplication
        val canConnectBle = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (host.binding.value?.autoConnect == true && canConnectBle) {
            runCatching { startForegroundService(ConnectService.resumeIntent(this)) }
        } else {
            runCatching { startService(Intent(this, ConnectService::class.java)) }
        }
        setResult(RESULT_OK)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
