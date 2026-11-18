package com.openzilo.connect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import top.yukonga.miuix.kmp.basic.BasicComponent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.SecurityAuthenticity
import top.yukonga.miuix.kmp.icon.glass.ThirdPartyAppsData
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class GrantActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val store = (application as ConnectApplication).grants
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val request = token?.let(store::pendingRequest)
        val managed = if (token == null) store.grants.value.firstOrNull {
            it.packageName == intent.getStringExtra(EXTRA_PACKAGE)
        } else null
        val packageName = request?.principal?.packageName ?: managed?.packageName ?: run { finish(); return }
        val label = request?.principal?.label ?: checkNotNull(managed).label
        val certificate = request?.principal?.certificate ?: checkNotNull(managed).certificate
        val scopes = request?.scopes ?: GrantPolicy.scopes
        setContent {
            val theme = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = theme) {
                var error by remember { mutableStateOf<String?>(null) }
                var showIdentity by remember { mutableStateOf(false) }
                val grants by store.grants.collectAsStateWithLifecycle()
                val grantedScopes = grants.firstOrNull { it.packageName == packageName && it.certificate == certificate }?.scopes.orEmpty()
                val appIcon = remember(packageName) {
                    try { packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }
                    catch (_: PackageManager.NameNotFoundException) { null }
                }
                Scaffold(topBar = {
                    TopAppBar(
                        title = stringResource(R.string.auth_title),
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.auth_back))
                            }
                        },
                    )
                }) { padding ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Card {
                            BasicComponent(
                                title = label,
                                summary = packageName,
                                startAction = {
                                    if (appIcon != null) Icon(appIcon, null, Modifier.size(48.dp), tint = Color.Unspecified)
                                    else Icon(MiuixIcons.Glass.ThirdPartyAppsData, null, Modifier.size(48.dp))
                                },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.ui_authorization_checks),
                                summary = stringResource(R.string.ui_package_and_certificate),
                                startAction = { Icon(MiuixIcons.Glass.SecurityAuthenticity, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
                                onClick = { showIdentity = true },
                            )
                        }
                        SmallTitle(stringResource(if (request != null) R.string.ui_requested_permissions else R.string.ui_manage_permissions))
                        Card {
                            scopes.sorted().forEach { scope ->
                                SwitchPreference(
                                    checked = scope in grantedScopes,
                                    onCheckedChange = { enabled ->
                                        try {
                                            if (token != null) store.setRequestedScope(token, scope, enabled)
                                            else store.setManagedScope(checkNotNull(managed), scope, enabled)
                                            error = null
                                        } catch (_: Exception) { error = getString(R.string.auth_failed) }
                                    },
                                    title = scopeLabel(this@GrantActivity, scope),
                                    summary = scopeDescription(this@GrantActivity, scope),
                                    startAction = { Icon(scopeIcon(scope), null, Modifier.size(28.dp), tint = MiuixTheme.colorScheme.primary) },
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.auth_privacy_warning),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        error?.let { Card { BasicComponent(title = it) } }
                    }
                    GlassDialog(
                        show = showIdentity,
                        backdrop = null,
                        onDismissRequest = { showIdentity = false },
                        title = stringResource(R.string.ui_authorization_checks),
                        summary = stringResource(R.string.auth_bound_identity),
                    ) {
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(packageName, style = MiuixTheme.textStyles.body2)
                            Text(stringResource(R.string.auth_certificate, certificate), style = MiuixTheme.textStyles.footnote1)
                        }
                        GlassDialogButton(onClick = { showIdentity = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(android.R.string.ok), style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return false
        return super.dispatchTouchEvent(event)
    }

    companion object {
        const val EXTRA_TOKEN = "requestToken"
        internal const val EXTRA_PACKAGE = "managedPackage"
        fun scopeLabel(context: Context, scope: String): String = context.getString(when (scope) {
            "info" -> R.string.auth_scope_info
            "recording" -> R.string.auth_scope_recording
            "audio" -> R.string.auth_scope_audio
            "sensors" -> R.string.auth_scope_sensors
            "logs" -> R.string.auth_scope_logs
            "time" -> R.string.auth_scope_time
            "firmware" -> R.string.auth_scope_firmware
            else -> R.string.auth_scope_unknown
        })
        fun scopeDescription(context: Context, scope: String): String = context.getString(when (scope) {
            "info" -> R.string.auth_description_info
            "recording" -> R.string.auth_description_recording
            "audio" -> R.string.auth_description_audio
            "sensors" -> R.string.auth_description_sensors
            "logs" -> R.string.auth_description_logs
            "time" -> R.string.auth_description_time
            "firmware" -> R.string.auth_description_firmware
            else -> R.string.auth_scope_unknown
        })
    }
}
