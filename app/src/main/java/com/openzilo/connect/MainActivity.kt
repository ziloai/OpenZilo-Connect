package com.openzilo.connect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import top.yukonga.miuix.kmp.icon.glass.PrivacySecurity
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Mic
import top.yukonga.miuix.kmp.icon.glass.Audio
import top.yukonga.miuix.kmp.icon.glass.GesturePassword
import top.yukonga.miuix.kmp.icon.glass.Log
import top.yukonga.miuix.kmp.icon.glass.ClockOutline
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Update
import top.yukonga.miuix.kmp.icon.glass.SecurityAuthenticity
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Error
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Device
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.ThirdPartyAppsData
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val host = application as ConnectApplication
        setContent {
            val theme = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = theme) { ConnectScreen(host, this) }
        }
    }
}

@Composable
private fun ConnectScreen(host: ConnectApplication, activity: MainActivity) {
    val controller = host.controller
    val ring by controller.state.collectAsStateWithLifecycle()
    val devices by controller.scanResults.collectAsStateWithLifecycle()
    val grants by host.grants.grants.collectAsStateWithLifecycle()
    val upgrading by host.upgrading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var scanStarting by remember { mutableStateOf(false) }
    val firmwareState by host.firmwareCatalog.state.collectAsStateWithLifecycle()
    var installCandidate by remember { mutableStateOf<PreparedFirmware?>(null) }
    var firmwareNotice by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedGrantPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGrant = grants.firstOrNull { it.packageName == selectedGrantPackage }
    val scanning = ring.phase == "scanning"
    val busy = upgrading || ring.phase in setOf("connecting", "upgrading", "reconnecting")
    val pageTitles = listOf(stringResource(R.string.ui_tab_ring), stringResource(R.string.ui_tab_firmware), stringResource(R.string.ui_tab_apps))
    val scrollStates = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState())
    val scrollState = scrollStates[page]
    val scrollBehaviors = listOf(MiuixScrollBehavior(), MiuixScrollBehavior(), MiuixScrollBehavior())
    val scrollBehavior = scrollBehaviors[page]
    val backdrop = if (Build.VERSION.SDK_INT >= 33) rememberLayerBackdrop() else null
    val dialogBackdrop = if (Build.VERSION.SDK_INT >= 33) rememberLayerBackdrop() else null
    val navigationItems = remember(pageTitles) {
        listOf(
            GlassNavigationItem(MiuixIcons.Glass.Device, pageTitles[0]),
            GlassNavigationItem(MiuixIcons.Glass.Download, pageTitles[1]),
            GlassNavigationItem(MiuixIcons.Glass.ThirdPartyAppsData, pageTitles[2]),
        )
    }

    fun reportFailure(summary: Int, error: Throwable) {
        if (error is CancellationException) throw error
        val message = activity.getString(summary)
        notice = error.message?.takeIf { it.isNotBlank() }?.let { activity.getString(R.string.ui_error_details, message, it) } ?: message
    }
    fun scan() {
        if (scanning || scanStarting || busy) return
        scanStarting = true
        scope.launch {
            notice = null
            try { controller.scan() }
            catch (error: Throwable) { reportFailure(R.string.ui_scan_failed, error) }
            finally { scanStarting = false }
        }
    }
    fun installFirmware() {
        val candidate = installCandidate ?: return
        if (busy) return
        installCandidate = null
        firmwareNotice = null
        notice = null
        val task = host.upgradeFirmware(candidate.bytes)
        scope.launch {
            try {
                task.await()
                firmwareNotice = R.string.ui_upgrade_verified
            } catch (error: Throwable) {
                val summary = if (error is com.openzilo.connect.core.FirmwareUpdateException && !error.installationStarted) {
                    R.string.ui_upgrade_not_started
                } else R.string.ui_upgrade_unconfirmed
                if (error is CancellationException) throw error
                android.util.Log.e("OpenZiloOta", "Firmware update failed", error)
                firmwareNotice = summary
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val required = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.all { result[it] == true || activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) scan()
        else notice = activity.getString(R.string.ui_nearby_permission_required)
    }
    LaunchedEffect(page) {
        if (page == 1) host.firmwareCatalog.load()
    }
    DisposableEffect(Unit) { onDispose { controller.stopScan() } }

    Scaffold {
    Scaffold(
        modifier = Modifier.fillMaxSize().then(if (dialogBackdrop != null) Modifier.layerBackdrop(dialogBackdrop) else Modifier),
        topBar = {
            GlassTopAppBar(
                title = pageTitles[page],
                isContentScrolled = scrollState.value > 0,
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingToolbar = {
            GlassNavigationBar(
                items = navigationItems,
                selectedIndex = page,
                onSelect = { page = it },
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        },
    ) { contentPadding ->
        Column(
            Modifier.fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MiuixTheme.colorScheme.surface)
                .verticalScroll(scrollState),
        ) {
            Column(
                Modifier.padding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 96.dp,
                    start = 12.dp,
                    end = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    0 -> {
                        Card {
                            BasicComponent(
                                title = ring.info?.model ?: stringResource(R.string.ui_your_ring),
                                summary = phaseLabel(ring.phase),
                            )
                            if (scanning || busy) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
                            if (ring.phase != "connected") {
                                BasicComponent(title = stringResource(R.string.ui_connection_instruction))
                            }
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (ring.phase == "connected") {
                                    ConnectButton(stringResource(R.string.ui_refresh), {
                                        scope.launch {
                                            try { controller.refresh() }
                                            catch (error: Throwable) { reportFailure(R.string.ui_refresh_failed, error) }
                                        }
                                    }, Modifier.weight(1f), enabled = !busy, primary = true)
                                    ConnectButton(stringResource(R.string.ui_disconnect), {
                                        try { activity.startService(ConnectService.disconnectIntent(activity)) }
                                        catch (error: Throwable) { reportFailure(R.string.ui_disconnect_failed, error) }
                                    }, Modifier.weight(1f), enabled = !busy)
                                } else {
                                    ConnectButton(stringResource(if (scanning || scanStarting) R.string.ui_scanning else R.string.ui_find_connect), {
                                        val permissions = if (Build.VERSION.SDK_INT >= 31) mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                                        if (permissions.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) scan() else permission.launch(permissions.toTypedArray())
                                    }, Modifier.fillMaxWidth(), enabled = !scanning && !scanStarting && !busy, primary = true)
                                }
                            }
                        }
                        if (devices.isNotEmpty() && ring.phase != "connected") {
                            SmallTitle(stringResource(R.string.ui_nearby_rings))
                            Card {
                                devices.forEach { candidate ->
                                    ArrowPreference(
                                        title = candidate.name.ifBlank { stringResource(R.string.ui_brand) },
                                        summary = stringResource(R.string.ui_signal_connect, candidate.rssi),
                                        enabled = !busy,
                                        onClick = {
                                            controller.stopScan()
                                            try { activity.startForegroundService(ConnectService.connectIntent(activity, candidate.address)) }
                                            catch (error: Throwable) { reportFailure(R.string.ui_connection_failed, error) }
                                        },
                                    )
                                }
                            }
                        }
                        SmallTitle(stringResource(R.string.ui_device_info))
                        Card {
                            Detail(stringResource(R.string.ui_battery), ring.info?.let {
                                stringResource(if (it.batteryCharging) R.string.ui_battery_charging_value else R.string.ui_battery_value, it.batteryPercent.toInt())
                            } ?: stringResource(R.string.ui_after_connection))
                            Detail(stringResource(R.string.ui_firmware_version), ring.info?.firmwareVersion ?: stringResource(R.string.ui_unknown_value))
                            Detail(stringResource(R.string.ui_available_storage), ring.info?.let { stringResource(R.string.ui_storage_value, (it.audioStorageAvailable / 1024u).toLong()) } ?: stringResource(R.string.ui_unknown_value))
                            Detail(stringResource(R.string.ui_device_capabilities), ring.capabilities?.features?.size?.let { pluralStringResource(R.plurals.ui_capabilities_count, it, it) } ?: stringResource(R.string.ui_not_negotiated))
                        }
                    }
                    1 -> FirmwareContent(ring, firmwareState, host.firmwareCatalog, busy, upgrading, firmwareNotice, dialogBackdrop) { installCandidate = it }
                    else -> {
                        Card {
                            BasicComponent(
                                title = stringResource(R.string.ui_authorized_apps),
                                summary = pluralStringResource(R.plurals.ui_authorized_app_count, grants.size, grants.size),
                                startAction = { Icon(MiuixIcons.Glass.PrivacySecurity, null, Modifier.size(48.dp), tint = MiuixTheme.colorScheme.primary) },
                            )
                        }
                        if (grants.isEmpty()) {
                            Card {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(MiuixIcons.Glass.ThirdPartyAppsData, null, Modifier.size(64.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Text(stringResource(R.string.ui_no_authorized_apps), style = MiuixTheme.textStyles.headline1)
                                    Text(stringResource(R.string.ui_authorization_explanation), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        } else {
                            grants.forEach { grant ->
                                val appIcon = remember(grant.packageName) {
                                    try { activity.packageManager.getApplicationIcon(grant.packageName).toBitmap(96, 96).asImageBitmap() }
                                    catch (_: PackageManager.NameNotFoundException) { null }
                                }
                                Card {
                                    BasicComponent(
                                        title = grant.label,
                                        summary = stringResource(R.string.ui_authorized),
                                        startAction = {
                                            if (appIcon != null) Icon(appIcon, null, Modifier.size(44.dp), tint = Color.Unspecified)
                                            else Icon(MiuixIcons.Glass.ThirdPartyAppsData, null, Modifier.size(44.dp))
                                        },
                                        endActions = { Icon(MiuixIcons.Glass.SecurityAuthenticity, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
                                    )
                                    ArrowPreference(
                                        title = stringResource(R.string.ui_manage_permissions),
                                        summary = pluralStringResource(R.plurals.ui_permission_count, grant.scopes.size, grant.scopes.size),
                                        onClick = { selectedGrantPackage = grant.packageName },
                                    )
                                }
                            }
                        }
                        val languageTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                        Card {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.ui_app_language),
                                items = listOf(stringResource(R.string.ui_language_system), stringResource(R.string.ui_language_chinese), stringResource(R.string.ui_language_english)),
                                selectedIndex = when { languageTags.startsWith("zh", true) -> 1; languageTags.startsWith("en", true) -> 2; else -> 0 },
                                enabled = !busy,
                                onSelectedIndexChange = { index ->
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(listOf("", "zh-CN", "en")[index]))
                                },
                            )
                        }
                    }
                }
                (notice ?: ring.message)?.takeIf { page != 1 && it.isNotBlank() }?.let {
                    Card { BasicComponent(title = it) }
                }
            }
        }
        GlassDialog(
            show = selectedGrant != null,
            onDismissRequest = { selectedGrantPackage = null },
            backdrop = dialogBackdrop,
            title = selectedGrant?.label,
            summary = selectedGrant?.packageName,
        ) {
            selectedGrant?.let { grant ->
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    grant.scopes.sorted().forEach { permission ->
                        BasicComponent(
                            title = scopeLabel(permission),
                            startAction = { Icon(scopeIcon(permission), null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassDialogButton(onClick = { selectedGrantPackage = null }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(android.R.string.cancel), style = MiuixTheme.textStyles.button)
                    }
                    GlassDialogButton(onClick = {
                        host.grants.revoke(grant.packageName)
                        selectedGrantPackage = null
                    }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_revoke_access), style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
        GlassDialog(
            show = installCandidate != null,
            onDismissRequest = { installCandidate = null },
            backdrop = dialogBackdrop,
            title = installCandidate?.let { stringResource(R.string.ui_confirm_install, it.metadata.version) },
            summary = stringResource(
                if (com.openzilo.connect.core.legacyFirmwareErasesRecordings(ring.info))
                    R.string.ui_legacy_upgrade_precautions else R.string.ui_upgrade_precautions
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = { installCandidate = null }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(android.R.string.cancel), style = MiuixTheme.textStyles.button)
                }
                GlassDialogButton(onClick = { installFirmware() }, modifier = Modifier.weight(1f), enabled = ring.phase == "connected" && !busy && installCandidate != null) {
                    Text(stringResource(R.string.ui_install_now), style = MiuixTheme.textStyles.button, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
    }
}

@Composable
private fun ColumnScope.FirmwareContent(
    ring: com.openzilo.connect.core.RingSnapshot,
    state: FirmwareCatalogState,
    catalog: FirmwareCatalog,
    busy: Boolean,
    upgrading: Boolean,
    firmwareNotice: Int?,
    dialogBackdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onInstall: (PreparedFirmware) -> Unit,
) {
    val current = ring.info?.firmwareVersion
    val selected = state.selected
    var showNotes by remember(selected?.id) { mutableStateOf(false) }
    val active = upgrading || ring.phase in setOf("upgrading", "reconnecting")
    if (active || state.downloading || firmwareNotice != null) {
        val restarting = ring.phase == "reconnecting"
        val progress = ring.firmwareProgress
        val status = when {
            active && restarting -> R.string.ui_phase_verifying
            active && progress != null -> R.string.ui_phase_updating
            active -> R.string.ui_firmware_preparing
            state.verifying -> R.string.ui_firmware_verifying
            state.downloading -> R.string.ui_firmware_downloading
            else -> firmwareNotice!!
        }
        Card {
            BasicComponent(
                title = stringResource(status),
                startAction = {
                    if (active || state.downloading) CircularProgressIndicator(size = 32.dp)
                    else Icon(
                        if (firmwareNotice == R.string.ui_upgrade_verified) MiuixIcons.Glass.Ok else MiuixIcons.Glass.Error,
                        null, Modifier.size(32.dp), tint = MiuixTheme.colorScheme.primary,
                    )
                },
                endActions = {
                    if (active && progress != null && !restarting) {
                        Text("$progress%", style = MiuixTheme.textStyles.title2)
                    }
                },
            )
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active || state.downloading) {
                    LinearProgressIndicator(progress = if (active && !restarting) progress?.div(100f) else null)
                }
                Text(
                    stringResource(when {
                        active && restarting -> R.string.ui_firmware_restart_detail
                        active -> R.string.ui_firmware_transfer_detail
                        state.verifying -> R.string.ui_firmware_verify_detail
                        state.downloading -> R.string.ui_firmware_download_detail
                        firmwareNotice == R.string.ui_upgrade_verified -> R.string.ui_firmware_verified_detail
                        else -> R.string.ui_firmware_retry_detail
                    }),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
    Card {
        BasicComponent(
            title = stringResource(R.string.ui_current_version),
            summary = current?.substringBefore("-") ?: stringResource(R.string.ui_connect_first),
            startAction = { Icon(MiuixIcons.Glass.Device, null, Modifier.size(32.dp), tint = MiuixTheme.colorScheme.primary) },
            endActions = {
                if (state.checking) CircularProgressIndicator(size = 24.dp)
                else IconButton(onClick = { catalog.refresh() }, enabled = !busy && !state.downloading) {
                    Icon(MiuixIcons.Glass.Refresh, stringResource(R.string.ui_check_updates), Modifier.size(24.dp))
                }
            },
        )
    }
    if (selected != null) {
        Card {
            BasicComponent(
                title = stringResource(when {
                    selected.version == current -> R.string.ui_up_to_date
                    current != null && firmwareVersionIsNewer(selected.version, current) -> R.string.ui_firmware_new_version
                    else -> R.string.ui_available_versions
                }),
                summary = selected.version.substringBefore("-"),
                startAction = { Icon(MiuixIcons.Glass.Update, null, Modifier.size(48.dp), tint = MiuixTheme.colorScheme.primary) },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.ui_version),
                items = state.releases.map { it.version },
                selectedIndex = state.releases.indexOfFirst { it.id == selected.id }.coerceAtLeast(0),
                enabled = !busy && !state.checking,
                onSelectedIndexChange = { catalog.select(state.releases[it]) },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.publishedAt.take(10), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (state.prepared != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(MiuixIcons.Glass.SecurityAuthenticity, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                        Text(stringResource(R.string.ui_verified), style = MiuixTheme.textStyles.footnote1)
                    }
                } else {
                    Text(stringResource(R.string.ui_firmware_size, selected.size / 1024), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            val ready = state.prepared
            val compatible = ready != null && ring.info?.let {
                it.model == ready.metadata.model &&
                    (ready.metadata.sourceVersions.isEmpty() || it.firmwareVersion in ready.metadata.sourceVersions)
            } == true
            val installed = selected.version == current
            val otaSupported = ring.capabilities?.features?.any { it.name == "ota" && it.major.toInt() == 1 } == true
            ConnectButton(
                stringResource(when {
                    installed -> R.string.ui_installed
                    state.verifying -> R.string.ui_firmware_verifying
                    state.downloading -> R.string.ui_firmware_downloading
                    ready == null -> R.string.ui_firmware_download
                    current == null -> R.string.ui_connect_first
                    !compatible -> R.string.ui_incompatible_firmware
                    !otaSupported -> R.string.ui_ota_unavailable
                    else -> R.string.ui_install_now
                }),
                { if (ready == null) catalog.download() else onInstall(ready) },
                Modifier.fillMaxWidth().padding(16.dp),
                enabled = !busy && !state.checking && !state.downloading && !installed &&
                    (ready == null || (ring.phase == "connected" && compatible && otaSupported)),
                primary = true,
            )
        }
        val chinese = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh"
        val notes = selected.releaseNotes(chinese)
        if (notes.isNotBlank()) {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.ui_release_notes),
                    summary = notes.lineSequence().firstOrNull(),
                    onClick = { showNotes = true },
                )
            }
            GlassDialog(
                show = showNotes,
                backdrop = dialogBackdrop,
                onDismissRequest = { showNotes = false },
                title = stringResource(R.string.ui_release_notes),
            ) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(notes, style = MiuixTheme.textStyles.body2)
                }
                ConnectButton(stringResource(android.R.string.ok), { showNotes = false }, Modifier.fillMaxWidth())
            }
        }
    } else if (!state.checking && state.failure == null) {
        Card { BasicComponent(title = stringResource(R.string.ui_no_firmware_releases)) }
    }
    state.failure?.let { failure ->
        Card {
            BasicComponent(
                title = stringResource(when (failure) {
                    FirmwareFailure.Check -> R.string.ui_update_check_failed
                    FirmwareFailure.Download -> R.string.ui_firmware_download_failed
                    FirmwareFailure.Verification -> R.string.ui_bundle_verification_failed
                }),
                endActions = { ConnectButton(stringResource(R.string.ui_retry), { catalog.retry() }, enabled = !busy) },
            )
        }
    }
}

@Composable
private fun ConnectButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    TextButton(text, onClick, modifier = modifier, enabled = enabled, minHeight = 48.dp,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

@Composable
private fun Detail(title: String, value: String) {
    BasicComponent(title = title, endActions = {
        Text(value, modifier = Modifier.widthIn(max = 180.dp), style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    })
}

@Composable
private fun phaseLabel(phase: String): String = stringResource(when (phase) {
    "connected" -> R.string.ui_phase_connected
    "connecting" -> R.string.ui_phase_connecting
    "scanning" -> R.string.ui_scanning
    "upgrading" -> R.string.ui_phase_updating
    "reconnecting" -> R.string.ui_phase_verifying
    "error" -> R.string.ui_phase_error
    else -> R.string.ui_phase_disconnected
})

internal fun scopeIcon(scope: String) = when (scope) {
    "info" -> MiuixIcons.Glass.Info
    "recording" -> MiuixIcons.Glass.Mic
    "audio" -> MiuixIcons.Glass.Audio
    "sensors" -> MiuixIcons.Glass.GesturePassword
    "logs" -> MiuixIcons.Glass.Log
    "time" -> MiuixIcons.Glass.ClockOutline
    "firmware" -> MiuixIcons.Glass.Update
    else -> MiuixIcons.Glass.PrivacySecurity
}

@Composable
private fun scopeLabel(scope: String): String = stringResource(when (scope) {
    "info" -> R.string.ui_scope_info
    "recording" -> R.string.ui_scope_recording
    "audio" -> R.string.ui_scope_audio
    "sensors" -> R.string.ui_scope_sensors
    "logs" -> R.string.ui_scope_logs
    "time" -> R.string.ui_scope_time
    "firmware" -> R.string.ui_scope_firmware
    else -> R.string.ui_scope_unknown
})
