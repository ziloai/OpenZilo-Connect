package com.openzilo.pose

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openzilo.connect.api.RingAxisPresetKind
import com.openzilo.connect.api.RingPostureKind
import com.openzilo.pose.render.RingPoseGlView
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : AppCompatActivity() {
    private val viewModel: PoseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val theme = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = theme) {
                PoseStageScreen(viewModel, this)
            }
        }
    }
}

private val GlassFill = Color(0xB80E131F)
private val GlassStroke = Color(0x3394A3B8)
private val ZiloBlue = Color(0xFF38BDF8)
private val ZiloOrange = Color(0xFFE2E8F0) // Crisp monochrome specular dot matching pure-black hardware
private val EmeraldLive = Color(0xFF10B981)
private val AmberWarn = Color(0xFFF59E0B)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextMuted = Color(0xFF94A3B8)

@Composable
private fun PoseStageScreen(viewModel: PoseViewModel, activity: MainActivity) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var glViewRef by remember { mutableStateOf<RingPoseGlView?>(null) }
    var showTuneDialog by remember { mutableStateOf(false) }
    val dialogBackdrop = if (Build.VERSION.SDK_INT >= 33) rememberLayerBackdrop() else null

    fun launchPendingIntent(pi: PendingIntent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
                pi.send(activity, 0, null, null, null, null, opts)
            } else {
                pi.send()
            }
        }
    }

    val wakeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.bindAndStart(::launchPendingIntent, null)
    }

    fun launchWakeHost(intent: Intent) {
        runCatching { wakeLauncher.launch(intent) }
    }

    LaunchedEffect(Unit) {
        viewModel.bindAndStart(::launchPendingIntent, ::launchWakeHost)
    }

    LaunchedEffect(ui.showAxes, ui.showGrid, glViewRef) {
        glViewRef?.renderer?.showAxes = ui.showAxes
        glViewRef?.renderer?.showGrid = ui.showGrid
    }

    DisposableEffect(lifecycleOwner, glViewRef) {
        val view = glViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    view?.onResume()
                    val conn = viewModel.state.value.connection
                    if (conn in setOf(ConnectionState.NEEDS_AUTH, ConnectionState.RING_OFFLINE, ConnectionState.RING_READY)) {
                        viewModel.onAuthorizationReturned()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
            .then(if (dialogBackdrop != null) Modifier.layerBackdrop(dialogBackdrop) else Modifier),
    ) {
        // 1. Full-screen 3D OpenZilo-Hardware PBR Stage
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                RingPoseGlView(
                    context = ctx,
                    motionStepper = { dtFrameS -> viewModel.stepRenderMotion(dtFrameS) },
                    ledPulseProvider = { viewModel.liveLedPulse },
                ).also { glViewRef = it }
            },
        )

        // 2. Top Floating Minimal HUD: Connection Pill (Left) + Graphic Attitude Horizon Glyph (Right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionPill(
                state = ui.connection,
                rateHz = ui.sampleRateHz,
                battery = ui.batteryPercent,
                onClick = { viewModel.toggleLiveOrConnect(::launchPendingIntent, ::launchWakeHost) },
                onToggleSim = {
                    if (ui.connection == ConnectionState.SIMULATING) {
                        viewModel.bindAndStart(::launchPendingIntent, ::launchWakeHost)
                    } else {
                        viewModel.startSimulation()
                    }
                },
            )

            PostureHorizonBadge(
                posture = ui.pose.posture,
                rollDeg = ui.pose.rollDeg.toFloat(),
                pitchDeg = ui.pose.pitchDeg.toFloat(),
                tiltDeg = ui.pose.tiltFromZenithDeg.toFloat(),
            )
        }

        // 3. Bottom Floating Minimal Glass Control Dock
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Live Toast / 3s Zero-Bias Calibration Countdown Banner
            if (ui.calibratingProgress != null || ui.toastNotice != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GlassFill)
                        .border(1.dp, ZiloBlue.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (ui.calibratingProgress != null) "保持戒指侧面平放桌面静置 3 秒（零偏 + 平面校准）…" else ui.toastNotice.orEmpty(),
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    ui.calibratingProgress?.let { progress ->
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Visual Sim Posture Selector Strip (icons only, shown in Demo mode)
            if (ui.connection == ConnectionState.SIMULATING) {
                SimPostureStrip(
                    selected = ui.simPreset,
                    onSelect = viewModel::selectSimPreset,
                )
            }

            // Main Glass Dock: 3 Arc Gauges + 4 Iconic Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(GlassFill)
                    .border(1.dp, GlassStroke, RoundedCornerShape(26.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AngleArcGauge("R", ui.pose.rollDeg.toFloat(), 180f, Color(0xFFFB7185))
                    AngleArcGauge("P", ui.pose.pitchDeg.toFloat(), 90f, Color(0xFF34D399))
                    AngleArcGauge("Y", ui.pose.yawDeg.toFloat(), 180f, Color(0xFF38BDF8))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val gyroDone = "gyro" in ui.calibratedComponents
                    val mountDone = "mount" in ui.calibratedComponents

                    DockActionChip(
                        modifier = Modifier.weight(1f),
                        label = "平放校准",
                        doneDot = gyroDone && mountDone,
                        active = ui.calibratingProgress != null,
                        onClick = viewModel::calibrateGyroBias3s,
                    )

                    DockActionChip(
                        modifier = Modifier.weight(1f),
                        label = "佩戴对齐",
                        doneDot = mountDone,
                        active = false,
                        onClick = viewModel::calibrateUpright,
                    )

                    DockActionChip(
                        modifier = Modifier.weight(1f),
                        label = "航向归零",
                        doneDot = false,
                        active = false,
                        onClick = viewModel::zeroHeading,
                    )

                    DockGlyphButton(
                        modifier = Modifier.width(46.dp),
                        active = ui.invertRadialZ || ui.axisPreset != RingAxisPresetKind.STANDARD,
                        onClick = { showTuneDialog = true },
                    ) { TuneSlidersGlyph() }
                }
            }
        }

        // Calibration Details, View Reset & Axis Alignment Modal
        GlassDialog(
            show = showTuneDialog,
            backdrop = dialogBackdrop,
            onDismissRequest = { showTuneDialog = false },
            title = stringResource(R.string.tab_calibration),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val biasText = ui.gyroBiasDps?.takeIf { it.size == 3 }?.let { b ->
                    "零偏: %.2f, %.2f, %.2f °/s".format(b[0], b[1], b[2])
                } ?: "零偏: 未手动标定（自动静止补偿开启）"
                Text(biasText, color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RingAxisPresetKind.entries.forEach { preset ->
                        val selected = ui.axisPreset == preset
                        val label = when (preset) {
                            RingAxisPresetKind.STANDARD -> "XYZ"
                            RingAxisPresetKind.SWAPPED_XY -> "YXZ"
                            RingAxisPresetKind.Z_BORE -> "ZYX"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) ZiloBlue.copy(alpha = 0.28f) else Color(0x22FFFFFF))
                                .border(
                                    1.dp,
                                    if (selected) ZiloBlue else GlassStroke,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { viewModel.selectAxisPreset(preset) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassDialogButton(
                        onClick = {
                            glViewRef?.resetCamera()
                            showTuneDialog = false
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重置视角", color = TextPrimary, fontSize = 13.sp)
                    }
                    GlassDialogButton(
                        onClick = {
                            viewModel.toggleAxesOverlay()
                            viewModel.toggleGridOverlay()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (ui.showAxes) "隐藏辅助轴" else "显示辅助轴", color = TextPrimary, fontSize = 13.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassDialogButton(
                        onClick = viewModel::toggleInvertRadialZ,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (ui.invertRadialZ) "Z 轴已反转" else "反转顶底 Z",
                            color = if (ui.invertRadialZ) ZiloBlue else TextPrimary,
                            fontSize = 13.sp,
                        )
                    }
                    GlassDialogButton(
                        onClick = {
                            viewModel.resetAllCalibration()
                            showTuneDialog = false
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("清除标定", color = Color(0xFFFB7185), fontSize = 13.sp)
                    }
                }

                GlassDialogButton(
                    onClick = { showTuneDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("✓", color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ConnectionPill(
    state: ConnectionState,
    rateHz: Int,
    battery: Int?,
    onClick: () -> Unit,
    onToggleSim: () -> Unit,
) {
    val dotColor by animateColorAsState(
        when (state) {
            ConnectionState.STREAMING_LIVE -> EmeraldLive
            ConnectionState.RING_READY -> ZiloBlue
            ConnectionState.SIMULATING -> ZiloOrange
            ConnectionState.NEEDS_AUTH, ConnectionState.RING_OFFLINE -> AmberWarn
            ConnectionState.BINDING, ConnectionState.UNBOUND -> TextMuted
        },
        label = "dotColor",
    )
    val shortLabel = when (state) {
        ConnectionState.STREAMING_LIVE -> "已连接 · ${rateHz}Hz" + (battery?.let { " · $it%" } ?: "")
        ConnectionState.RING_READY -> "点此开启采集"
        ConnectionState.SIMULATING -> "仿真演示"
        ConnectionState.NEEDS_AUTH -> "点此授权传感器"
        ConnectionState.RING_OFFLINE -> "等待戒指连接…"
        ConnectionState.BINDING -> "正在连接服务…"
        ConnectionState.UNBOUND -> "点此重连戒指"
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(GlassFill)
            .border(1.dp, GlassStroke, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = shortLabel,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        // Compact mode-switch chip (Ring <-> Sim)
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0x26FFFFFF))
                .clickable(onClick = onToggleSim)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (state == ConnectionState.SIMULATING) "BLE" else "SIM",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Graphic Artificial-Horizon + Ring Cross-Section Glyph replacing verbose posture text!
 * Visually depicts Horizontal Upright (orange button top), Flipped (orange button bottom),
 * Vertical Standing (90° side), and Flat Bore (top-down circle).
 */
@Composable
private fun PostureHorizonBadge(
    posture: RingPostureKind,
    rollDeg: Float,
    pitchDeg: Float,
    tiltDeg: Float,
) {
    val accent by animateColorAsState(
        when (posture) {
            RingPostureKind.HORIZONTAL_UPRIGHT -> EmeraldLive
            RingPostureKind.HORIZONTAL_FLIPPED -> ZiloOrange
            RingPostureKind.VERTICAL_STANDING_EDGE -> ZiloBlue
            RingPostureKind.VERTICAL_BORE_AXIS -> Color(0xFFA78BFA)
            RingPostureKind.TILTED -> Color(0xFF38BDF8)
            RingPostureKind.DYNAMIC_MOTION -> AmberWarn
        },
        label = "postureAccent",
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(GlassFill)
            .border(1.dp, accent.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(26.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension * 0.42f
            // Horizon line
            drawLine(
                color = Color(0x5594A3B8),
                start = Offset(1f, c.y),
                end = Offset(size.width - 1f, c.y),
                strokeWidth = 1.5.dp.toPx(),
            )
            if (posture == RingPostureKind.VERTICAL_BORE_AXIS || abs(pitchDeg) > 62f) {
                // Flat on table: draw concentric bore view from side as flat horizontal bar
                drawLine(
                    color = accent,
                    start = Offset(c.x - r, c.y),
                    end = Offset(c.x + r, c.y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = TextPrimary,
                    start = Offset(c.x, c.y - r * 0.85f),
                    end = Offset(c.x, c.y + r * 0.85f),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            } else {
                // Ring cross-section rotating by live Roll, with Zilo Orange button dot!
                rotate(degrees = rollDeg, pivot = c) {
                    drawCircle(
                        color = accent,
                        radius = r * 0.82f,
                        center = c,
                        style = Stroke(width = 2.6.dp.toPx()),
                    )
                    drawCircle(
                        color = ZiloOrange,
                        radius = 3.1.dp.toPx(),
                        center = Offset(c.x, c.y - r * 0.88f),
                    )
                }
            }
        }
        Text(
            text = "${tiltDeg.roundToInt()}°",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SimPostureStrip(
    selected: SimPreset,
    onSelect: (SimPreset) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(GlassFill)
            .border(1.dp, GlassStroke, CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimPreset.entries.forEach { preset ->
            val isSelected = preset == selected
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) ZiloBlue.copy(alpha = 0.28f) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) ZiloBlue else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(preset) },
                contentAlignment = Alignment.Center,
            ) {
                SimPresetMiniGlyph(preset, isSelected)
            }
        }
    }
}

@Composable
private fun SimPresetMiniGlyph(preset: SimPreset, active: Boolean) {
    val ringColor = if (active) TextPrimary else TextMuted
    Canvas(Modifier.size(20.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.36f
        when (preset) {
            SimPreset.ORBIT_SHOWCASE -> {
                drawArc(
                    color = ringColor,
                    startAngle = -35f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawCircle(ZiloOrange, 2.4.dp.toPx(), Offset(c.x + r * 0.7f, c.y - r * 0.7f))
            }
            SimPreset.HORIZONTAL_UP -> {
                drawCircle(ringColor, r, c, style = Stroke(2.2.dp.toPx()))
                drawCircle(ZiloOrange, 2.6.dp.toPx(), Offset(c.x, c.y - r))
            }
            SimPreset.HORIZONTAL_FLIP -> {
                drawCircle(ringColor, r, c, style = Stroke(2.2.dp.toPx()))
                drawCircle(ZiloOrange, 2.6.dp.toPx(), Offset(c.x, c.y + r))
            }
            SimPreset.VERTICAL_EDGE -> {
                drawCircle(ringColor, r, c, style = Stroke(2.2.dp.toPx()))
                drawCircle(ZiloOrange, 2.6.dp.toPx(), Offset(c.x + r, c.y))
            }
            SimPreset.FLAT_BORE_UP -> {
                drawLine(
                    color = ringColor,
                    start = Offset(c.x - r * 1.15f, c.y + 2f),
                    end = Offset(c.x + r * 1.15f, c.y + 2f),
                    strokeWidth = 3.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(ZiloBlue, 2.3.dp.toPx(), Offset(c.x, c.y - r * 0.65f))
            }
        }
    }
}

@Composable
private fun AngleArcGauge(
    axisTag: String,
    angleDeg: Float,
    maxAbsDeg: Float,
    color: Color,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(68.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val pad = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            drawArc(
                color = Color(0x2494A3B8),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val normalized = (angleDeg / maxAbsDeg).coerceIn(-1f, 1f)
            drawArc(
                color = color,
                startAngle = 270f,
                sweepAngle = normalized * 135f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = axisTag,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${angleDeg.roundToInt()}°",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DockActionChip(
    modifier: Modifier = Modifier,
    label: String,
    doneDot: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) ZiloBlue.copy(alpha = 0.26f) else Color(0x18FFFFFF))
            .border(
                1.dp,
                when {
                    active -> ZiloBlue
                    doneDot -> EmeraldLive.copy(alpha = 0.55f)
                    else -> GlassStroke
                },
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        if (doneDot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(EmeraldLive))
        }
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DockGlyphButton(
    modifier: Modifier = Modifier,
    active: Boolean,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) ZiloBlue.copy(alpha = 0.22f) else Color(0x18FFFFFF))
            .border(
                1.dp,
                if (active) ZiloBlue.copy(alpha = 0.65f) else GlassStroke,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        glyph()
    }
}

@Composable
private fun CrosshairZeroGlyph() {
    Canvas(Modifier.size(18.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.34f
        drawCircle(TextPrimary, r, c, style = Stroke(1.8.dp.toPx()))
        drawLine(TextPrimary, Offset(c.x, 1f), Offset(c.x, size.height - 1f), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(TextPrimary, Offset(1f, c.y), Offset(size.width - 1f, c.y), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun CameraResetGlyph() {
    Canvas(Modifier.size(18.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.38f
        drawArc(
            color = TextPrimary,
            startAngle = -60f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(ZiloBlue, 2.2.dp.toPx(), c)
    }
}

@Composable
private fun AxisCubeGlyph(active: Boolean) {
    val col = if (active) ZiloBlue else TextMuted
    Canvas(Modifier.size(18.dp)) {
        val o = Offset(size.width * 0.45f, size.height * 0.58f)
        drawLine(Color(0xFFFB7185), o, Offset(size.width * 0.90f, size.height * 0.72f), 1.8.dp.toPx(), StrokeCap.Round)
        drawLine(Color(0xFF38BDF8), o, Offset(size.width * 0.45f, size.height * 0.12f), 1.8.dp.toPx(), StrokeCap.Round)
        drawLine(col, o, Offset(size.width * 0.12f, size.height * 0.78f), 1.8.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun TuneSlidersGlyph() {
    Canvas(Modifier.size(18.dp)) {
        val y1 = size.height * 0.30f
        val y2 = size.height * 0.70f
        drawLine(TextPrimary, Offset(2f, y1), Offset(size.width - 2f, y1), 1.7.dp.toPx(), StrokeCap.Round)
        drawLine(TextPrimary, Offset(2f, y2), Offset(size.width - 2f, y2), 1.7.dp.toPx(), StrokeCap.Round)
        drawCircle(ZiloOrange, 2.6.dp.toPx(), Offset(size.width * 0.35f, y1))
        drawCircle(ZiloBlue, 2.6.dp.toPx(), Offset(size.width * 0.68f, y2))
    }
}
