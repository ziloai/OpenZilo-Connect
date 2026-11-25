package com.openzilo.pose

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openzilo.connect.api.ConnectClient
import com.openzilo.connect.api.ConnectStatus
import com.openzilo.connect.api.ImuAttitudeData
import com.openzilo.connect.api.RingAxisPresetKind
import com.openzilo.connect.api.RingGestureEvent
import com.openzilo.connect.api.RingPostureKind
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnectionState {
    UNBOUND,
    BINDING,
    NEEDS_AUTH,
    RING_OFFLINE,
    RING_READY,
    STREAMING_LIVE,
    SIMULATING,
}

enum class SimPreset {
    ORBIT_SHOWCASE,
    HORIZONTAL_UP,
    HORIZONTAL_FLIP,
    VERTICAL_EDGE,
    FLAT_BORE_UP,
}

data class LiveMotionSample(
    val q: PoseQuaternion = PoseQuaternion.IDENTITY,
    val wxDps: Double = 0.0,
    val wyDps: Double = 0.0,
    val wzDps: Double = 0.0,
    val stationary: Boolean = true,
    val receivedAtNs: Long = System.nanoTime(),
)

data class PoseQuaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): PoseQuaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n > 1e-9) PoseQuaternion(w / n, x / n, y / n, z / n) else IDENTITY
    }

    fun extrapolateBodyDps(wxDps: Double, wyDps: Double, wzDps: Double, dtS: Double): PoseQuaternion {
        if (dtS <= 1e-5) return this
        val degToRad = Math.PI / 180.0
        val wx = wxDps * degToRad
        val wy = wyDps * degToRad
        val wz = wzDps * degToRad
        val omega = sqrt(wx * wx + wy * wy + wz * wz)
        if (omega <= 1e-4) return this
        val half = 0.5 * omega * dtS
        val k = sin(half) / omega
        val dw = cos(half)
        val dx = wx * k
        val dy = wy * k
        val dz = wz * k
        return PoseQuaternion(
            w = w * dw - x * dx - y * dy - z * dz,
            x = w * dx + x * dw + y * dz - z * dy,
            y = w * dy - x * dz + y * dw + z * dx,
            z = w * dz + x * dy - y * dx + z * dw,
        ).normalized()
    }

    fun toOpenGlMatrix(out16: FloatArray) {
        val qn = normalized()
        val qw = qn.w.toFloat()
        val qx = qn.x.toFloat()
        val qy = qn.z.toFloat()
        val qz = (-qn.y).toFloat()
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        out16[0] = 1f - 2f * (yy + zz); out16[1] = 2f * (xy + wz); out16[2] = 2f * (xz - wy); out16[3] = 0f
        out16[4] = 2f * (xy - wz); out16[5] = 1f - 2f * (xx + zz); out16[6] = 2f * (yz + wx); out16[7] = 0f
        out16[8] = 2f * (xz + wy); out16[9] = 2f * (yz - wx); out16[10] = 1f - 2f * (xx + yy); out16[11] = 0f
        out16[12] = 0f; out16[13] = 0f; out16[14] = 0f; out16[15] = 1f
    }

    companion object {
        val IDENTITY = PoseQuaternion(1.0, 0.0, 0.0, 0.0)

        fun slerp(a: PoseQuaternion, b: PoseQuaternion, t: Double): PoseQuaternion {
            val c = t.coerceIn(0.0, 1.0)
            var bw = b.w; var bx = b.x; var by = b.y; var bz = b.z
            var cosHalf = a.w * bw + a.x * bx + a.y * by + a.z * bz
            if (cosHalf < 0.0) {
                bw = -bw; bx = -bx; by = -by; bz = -bz; cosHalf = -cosHalf
            }
            if (cosHalf >= 0.9995) {
                return PoseQuaternion(
                    a.w + c * (bw - a.w),
                    a.x + c * (bx - a.x),
                    a.y + c * (by - a.y),
                    a.z + c * (bz - a.z),
                ).normalized()
            }
            val half = acos(cosHalf.coerceIn(-1.0, 1.0))
            val sinHalf = sqrt(1.0 - cosHalf * cosHalf)
            val ra = sin((1.0 - c) * half) / sinHalf
            val rb = sin(c * half) / sinHalf
            return PoseQuaternion(
                a.w * ra + bw * rb,
                a.x * ra + bx * rb,
                a.y * ra + by * rb,
                a.z * ra + bz * rb,
            ).normalized()
        }
    }
}

val INITIAL_ATTITUDE = ImuAttitudeData(
    qw = 1.0, qx = 0.0, qy = 0.0, qz = 0.0,
    rollDeg = 0.0, pitchDeg = 0.0, yawDeg = 0.0,
    tiltFromZenithDeg = 0.0,
    posture = RingPostureKind.HORIZONTAL_UPRIGHT,
    openGlMatrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
    stationary = true,
)

data class PoseUiState(
    val connection: ConnectionState = ConnectionState.UNBOUND,
    val deviceName: String = "",
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val calibratedComponents: List<String> = emptyList(),
    val gyroBiasDps: List<Double>? = null,
    val sampleRateHz: Int = 100,
    val pose: ImuAttitudeData = INITIAL_ATTITUDE,
    val simPreset: SimPreset = SimPreset.ORBIT_SHOWCASE,
    val axisPreset: RingAxisPresetKind = RingAxisPresetKind.STANDARD,
    val invertRadialZ: Boolean = false,
    val showAxes: Boolean = true,
    val showGrid: Boolean = true,
    val calibratingProgress: Float? = null,
    val toastNotice: String? = null,
    val ledPulseIntensity: Float = 0f,
    val sequenceGaps: Int = 0,
    val errorBanner: String? = null,
)

internal class ImuTrajectoryBuffer {
    private class Keyframe(val tMs: Double, val q: PoseQuaternion)

    private val lock = Any()
    private val frames = ArrayDeque<Keyframe>(128)
    private var lastDeviceTsMs: Long? = null
    private var timelineHeadMs: Double = 0.0
    private var playheadMs: Double = 0.0
    private var playbackRate: Double = 1.0
    private var initialized: Boolean = false
    private var flushOnNextBatch: Boolean = false

    private var tailWxDps: Double = 0.0
    private var tailWyDps: Double = 0.0
    private var tailWzDps: Double = 0.0
    private var tailStationary: Boolean = true
    private var immediateSample: LiveMotionSample = LiveMotionSample()
    private var useImmediateMode: Boolean = true

    fun setImmediate(sample: LiveMotionSample) = synchronized(lock) {
        immediateSample = sample
        useImmediateMode = true
        frames.clear()
        initialized = false
        lastDeviceTsMs = null
    }

    fun requestFlushOnNextBatch() = synchronized(lock) {
        flushOnNextBatch = true
    }

    fun pushBatch(
        timestampsMs: LongArray,
        packedQuats: DoubleArray?,
        fallbackAtt: ImuAttitudeData,
    ): Unit = synchronized(lock) {
        val quats = packedQuats
        val count = if (quats != null) minOf(timestampsMs.size, quats.size / 4) else 0
        if (count <= 0) {
            setImmediate(
                LiveMotionSample(
                    q = PoseQuaternion(fallbackAtt.qw, fallbackAtt.qx, fallbackAtt.qy, fallbackAtt.qz),
                    wxDps = fallbackAtt.wxDps,
                    wyDps = fallbackAtt.wyDps,
                    wzDps = fallbackAtt.wzDps,
                    stationary = fallbackAtt.stationary,
                ),
            )
            return@synchronized
        }

        if (flushOnNextBatch || useImmediateMode || !initialized) {
            frames.clear()
            lastDeviceTsMs = null
            timelineHeadMs = 0.0
            initialized = false
            flushOnNextBatch = false
            useImmediateMode = false
        }

        var wasInitialized = initialized
        val remBeforeMs = if (wasInitialized) timelineHeadMs - playheadMs else 0.0
        if (wasInitialized && abs(remBeforeMs) > 750.0) {
            frames.clear()
            lastDeviceTsMs = null
            timelineHeadMs = 0.0
            wasInitialized = false
        }

        var firstStepMs = 10.0
        for (i in 0 until count) {
            val devTs = timestampsMs[i]
            val prevDev = lastDeviceTsMs
            if (prevDev != null) {
                val rawDelta = (devTs - prevDev) and 0xFFFF_FFFFL
                val stepMs = if (rawDelta in 2L..80L) rawDelta.toDouble() else 10.0
                if (i == 0) firstStepMs = stepMs
                timelineHeadMs += stepMs
            }
            lastDeviceTsMs = devTs
            val o = i * 4
            val q = PoseQuaternion(quats!![o], quats[o + 1], quats[o + 2], quats[o + 3]).normalized()
            frames.addLast(Keyframe(timelineHeadMs, q))
        }

        if (!wasInitialized) {
            playheadMs = frames.first().tMs
            // Gentle 0.94x initial pace leaves a ~20ms BLE jitter cushion when batch #2 arrives 320ms later
            playbackRate = 0.94
            initialized = true
        } else {
            val reserveAtArrivalMs = remBeforeMs + firstStepMs
            val errMs = reserveAtArrivalMs - 22.0
            playbackRate = (1.0 + errMs / 380.0).coerceIn(0.90, 1.16)
        }

        while (frames.size > 2 && frames[1].tMs < playheadMs - 20.0) {
            frames.removeFirst()
        }

        tailWxDps = fallbackAtt.wxDps
        tailWyDps = fallbackAtt.wyDps
        tailWzDps = fallbackAtt.wzDps
        tailStationary = fallbackAtt.stationary
    }

    fun step(dtFrameS: Double): LiveMotionSample = synchronized(lock) {
        if (useImmediateMode || frames.isEmpty()) {
            return immediateSample
        }
        playheadMs += dtFrameS * 1000.0 * playbackRate
        if (playheadMs > timelineHeadMs + 75.0) {
            playheadMs = timelineHeadMs + 75.0
        }
        while (frames.size >= 2 && frames[1].tMs <= playheadMs) {
            frames.removeFirst()
        }
        val qOut = when {
            playheadMs <= frames.first().tMs -> frames.first().q
            frames.size >= 2 && playheadMs <= frames[1].tMs -> {
                val f0 = frames[0]
                val f1 = frames[1]
                val span = (f1.tMs - f0.tMs).coerceAtLeast(1e-3)
                val u = ((playheadMs - f0.tMs) / span).coerceIn(0.0, 1.0)
                PoseQuaternion.slerp(f0.q, f1.q, u)
            }
            else -> {
                val overS = ((playheadMs - timelineHeadMs) / 1000.0).coerceIn(0.0, 0.075)
                val speed = sqrt(tailWxDps * tailWxDps + tailWyDps * tailWyDps + tailWzDps * tailWzDps)
                val lastQ = frames.last().q
                if (!tailStationary && speed > 2.0) {
                    val horizonS = 0.065 * (1.0 - exp(-overS / 0.065))
                    lastQ.extrapolateBodyDps(tailWxDps, tailWyDps, tailWzDps, horizonS)
                } else {
                    lastQ
                }
            }
        }
        LiveMotionSample(
            q = qOut,
            wxDps = tailWxDps,
            wyDps = tailWyDps,
            wzDps = tailWzDps,
            stationary = tailStationary,
        )
    }
}

class PoseViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PoseUiState())
    val state: StateFlow<PoseUiState> = mutableState.asStateFlow()

    private val trajectoryBuffer = ImuTrajectoryBuffer()
    private var hudFrameCounter: Int = 0

    @Volatile var liveLedPulse: Float = 0f
        private set

    private var connectClient: ConnectClient? = null
    private var eventJob: Job? = null
    private var simJob: Job? = null
    private var expectedNextSeq: Long? = null
    private var simYawTareRad: Double = 0.0

    fun stepRenderMotion(dtFrameS: Double): LiveMotionSample {
        val sample = trajectoryBuffer.step(dtFrameS)
        if (liveLedPulse > 0.005f) {
            liveLedPulse = (liveLedPulse * 0.965f).coerceAtLeast(0f)
        } else if (liveLedPulse != 0f) {
            liveLedPulse = 0f
        }
        hudFrameCounter = (hudFrameCounter + 1) and 0x7FFF_FFFF
        if (hudFrameCounter % 4 == 0 && mutableState.value.connection == ConnectionState.STREAMING_LIVE) {
            val smoothPose = synthesizePreviewAttitude(sample.q).copy(
                stationary = sample.stationary,
                wxDps = sample.wxDps,
                wyDps = sample.wyDps,
                wzDps = sample.wzDps,
            )
            mutableState.update { it.copy(pose = smoothPose, ledPulseIntensity = liveLedPulse) }
        }
        return sample
    }

    private var triedWakeHost = false

    fun toggleLiveOrConnect(onLaunchAuth: (PendingIntent) -> Unit, onWakeHost: (Intent) -> Unit) {
        when (mutableState.value.connection) {
            ConnectionState.STREAMING_LIVE -> stopLiveStream()
            ConnectionState.SIMULATING, ConnectionState.UNBOUND -> {
                triedWakeHost = false
                bindAndStart(onLaunchAuth, onWakeHost)
            }
            ConnectionState.NEEDS_AUTH -> requestAuthorization(onLaunchAuth)
            ConnectionState.RING_OFFLINE -> openConnectApp()
            ConnectionState.RING_READY -> startImuStream()
            ConnectionState.BINDING -> Unit
        }
    }

    fun bindAndStart(onLaunchAuth: (PendingIntent) -> Unit, onWakeHost: ((Intent) -> Unit)? = null) {
        viewModelScope.launch {
            stopSimulationInternal()
            mutableState.update { it.copy(connection = ConnectionState.BINDING, errorBanner = null) }
            try {
                val client = ensureBoundClient()
                triedWakeHost = false
                val status = try {
                    client.ringStatus()
                } catch (_: SecurityException) {
                    mutableState.update { it.copy(connection = ConnectionState.NEEDS_AUTH) }
                    onLaunchAuth(client.requestAuthorization(REQUIRED_SCOPES))
                    return@launch
                }
                applyConnectStatus(status)
                if (status.phase == "connected") {
                    startImuStreamInternal(client)
                } else {
                    client.subscribe(setOf("status"))
                }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                releaseClient()
                if (!triedWakeHost && onWakeHost != null && isConnectInstalled()) {
                    triedWakeHost = true
                    onWakeHost(ConnectClient.wakeHostIntent())
                } else {
                    mutableState.update { it.copy(connection = ConnectionState.UNBOUND, errorBanner = "Connect 后台被系统拦截，点此唤醒") }
                }
            } catch (e: Exception) {
                releaseClient()
                if (!triedWakeHost && onWakeHost != null && isConnectInstalled()) {
                    triedWakeHost = true
                    onWakeHost(ConnectClient.wakeHostIntent())
                } else {
                    mutableState.update {
                        it.copy(connection = ConnectionState.UNBOUND, errorBanner = e.message ?: "Connect unavailable")
                    }
                }
            }
        }
    }

    private fun isConnectInstalled(): Boolean = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(ConnectClient.HOST_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun requestAuthorization(onLaunchAuth: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            try {
                val client = ensureBoundClient()
                onLaunchAuth(client.requestAuthorization(REQUIRED_SCOPES))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(errorBanner = e.message) }
            }
        }
    }

    fun onAuthorizationReturned() {
        viewModelScope.launch {
            val client = connectClient ?: return@launch
            try {
                val status = client.ringStatus()
                applyConnectStatus(status)
                if (status.phase == "connected") {
                    startImuStreamInternal(client)
                } else {
                    client.subscribe(setOf("status"))
                }
            } catch (_: SecurityException) {
                mutableState.update { it.copy(connection = ConnectionState.NEEDS_AUTH) }
            } catch (e: Exception) {
                mutableState.update { it.copy(errorBanner = e.message) }
            }
        }
    }

    fun startImuStream() {
        val client = connectClient ?: return
        viewModelScope.launch { startImuStreamInternal(client) }
    }

    private suspend fun startImuStreamInternal(client: ConnectClient) {
        try {
            expectedNextSeq = null
            trajectoryBuffer.requestFlushOnNextBatch()
            client.subscribe(setOf("status", "imu", "gesture"))
            client.configureAttitudeAxes(mutableState.value.axisPreset, mutableState.value.invertRadialZ)
            val config = client.startImu()
            mutableState.update {
                it.copy(
                    connection = ConnectionState.STREAMING_LIVE,
                    sampleRateHz = config.sampleRateHz,
                    sequenceGaps = 0,
                    errorBanner = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            mutableState.update { it.copy(connection = ConnectionState.NEEDS_AUTH) }
        } catch (e: Exception) {
            showToast("启动传感器失败: ${e.message ?: "请检查固件版本"}")
            mutableState.update {
                it.copy(connection = ConnectionState.RING_READY, errorBanner = e.message)
            }
        }
    }

    fun stopLiveStream() {
        val client = connectClient
        viewModelScope.launch {
            runCatching {
                client?.stopImu()
                client?.subscribe(setOf("status"))
            }
            if (client != null) {
                runCatching { applyConnectStatus(client.ringStatus()) }
            } else {
                mutableState.update { it.copy(connection = ConnectionState.UNBOUND) }
            }
        }
    }

    fun startSimulation(preset: SimPreset = mutableState.value.simPreset) {
        val prevClient = connectClient
        if (prevClient != null) {
            viewModelScope.launch {
                runCatching {
                    prevClient.stopImu()
                    prevClient.unsubscribe()
                }
                releaseClient()
            }
        }
        stopSimulationInternal()
        simYawTareRad = 0.0
        mutableState.update {
            it.copy(
                connection = ConnectionState.SIMULATING,
                simPreset = preset,
                sampleRateHz = 100,
                errorBanner = null,
            )
        }
        simJob = viewModelScope.launch(Dispatchers.Default) {
            var tick = 0L
            var phase = 0.0
            while (isActive) {
                val rawQ = simulatedTargetQuaternion(mutableState.value.simPreset, phase)
                val taredQ = mulQ(fromAxisAngle(0.0, 0.0, 1.0, -simYawTareRad), rawQ).normalized()
                publishAttitude(synthesizePreviewAttitude(taredQ), tick)
                tick += 1L
                phase += 0.016
                delay(16L)
            }
        }
    }

    fun selectSimPreset(preset: SimPreset) {
        if (mutableState.value.connection == ConnectionState.SIMULATING) {
            mutableState.update { it.copy(simPreset = preset) }
        } else {
            startSimulation(preset)
        }
    }

    fun zeroHeading() {
        val client = connectClient
        if (mutableState.value.connection == ConnectionState.STREAMING_LIVE && client != null) {
            viewModelScope.launch {
                runCatching { client.zeroAttitudeHeading() }
                trajectoryBuffer.requestFlushOnNextBatch()
            }
        } else {
            simYawTareRad += Math.toRadians(mutableState.value.pose.yawDeg)
        }
        pulseLed()
    }

    fun selectAxisPreset(preset: RingAxisPresetKind) {
        val invert = mutableState.value.invertRadialZ
        mutableState.update { it.copy(axisPreset = preset) }
        val client = connectClient
        if (client != null && mutableState.value.connection == ConnectionState.STREAMING_LIVE) {
            viewModelScope.launch {
                runCatching { client.configureAttitudeAxes(preset, invert) }
                trajectoryBuffer.requestFlushOnNextBatch()
            }
        }
    }

    fun toggleInvertRadialZ() {
        val nextInvert = !mutableState.value.invertRadialZ
        val preset = mutableState.value.axisPreset
        mutableState.update { it.copy(invertRadialZ = nextInvert) }
        val client = connectClient
        if (client != null && mutableState.value.connection == ConnectionState.STREAMING_LIVE) {
            viewModelScope.launch {
                runCatching { client.configureAttitudeAxes(preset, nextInvert) }
                trajectoryBuffer.requestFlushOnNextBatch()
            }
        }
    }

    fun toggleAxesOverlay() {
        mutableState.update { it.copy(showAxes = !it.showAxes) }
    }

    fun toggleGridOverlay() {
        mutableState.update { it.copy(showGrid = !it.showGrid) }
    }

    fun calibrateGyroBias3s() {
        val client = connectClient
        if (mutableState.value.connection == ConnectionState.STREAMING_LIVE && client != null) {
            if (mutableState.value.calibratingProgress != null) return
            viewModelScope.launch {
                val progressJob = launch {
                    val start = SystemClock.elapsedRealtime()
                    while (isActive) {
                        val p = ((SystemClock.elapsedRealtime() - start) / 3500f).coerceIn(0.02f, 0.98f)
                        mutableState.update { it.copy(calibratingProgress = p) }
                        delay(50)
                    }
                }
                try {
                    val reply = client.calibrateGyroBias(3_000)
                    trajectoryBuffer.requestFlushOnNextBatch()
                    val comps = reply.getStringArray("calibratedComponents")?.toList() ?: listOf("gyro")
                    val bias = reply.getDoubleArray("gyroBiasDps")?.toList()
                    mutableState.update { it.copy(calibratedComponents = comps, gyroBiasDps = bias ?: it.gyroBiasDps) }
                    pulseLed()
                    showToast("平放零偏与水平面校准完成 ✓")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("OpenZiloPose", "Calibration failed: ${e.message}")
                    showToast("请将戒指平放桌面松手静置 3 秒再试")
                } finally {
                    progressJob.cancel()
                    mutableState.update { it.copy(calibratingProgress = null) }
                }
            }
        } else {
            selectSimPreset(SimPreset.FLAT_BORE_UP)
            pulseLed()
            showToast("请先点左上角 BLE 连接真实戒指后再校准")
        }
    }

    fun calibrateUpright() {
        val client = connectClient
        if (mutableState.value.connection == ConnectionState.STREAMING_LIVE && client != null) {
            viewModelScope.launch {
                try {
                    val reply = client.calibrateUprightReference()
                    trajectoryBuffer.requestFlushOnNextBatch()
                    val comps = reply.getStringArray("calibratedComponents")?.toList() ?: (mutableState.value.calibratedComponents + "mount").distinct()
                    mutableState.update { it.copy(calibratedComponents = comps) }
                    pulseLed()
                    showToast("佩戴按键朝向已对齐 ✓")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    showToast("请戴在手指上使按键朝上后点击")
                }
            }
        } else {
            selectSimPreset(SimPreset.HORIZONTAL_UP)
            pulseLed()
            showToast("仿真已切至佩戴正朝上")
        }
    }

    fun resetAllCalibration() {
        val client = connectClient
        if (client != null && mutableState.value.connection in setOf(ConnectionState.STREAMING_LIVE, ConnectionState.RING_READY)) {
            viewModelScope.launch {
                runCatching { client.resetImuCalibration() }
                trajectoryBuffer.requestFlushOnNextBatch()
                mutableState.update { it.copy(calibratedComponents = emptyList(), gyroBiasDps = null) }
                showToast("已清除标定参数")
            }
        }
    }

    private var toastJob: Job? = null
    private fun showToast(msg: String) {
        toastJob?.cancel()
        mutableState.update { it.copy(toastNotice = msg) }
        toastJob = viewModelScope.launch {
            delay(2_600)
            mutableState.update { if (it.toastNotice == msg) it.copy(toastNotice = null) else it }
        }
    }

    fun openConnectApp() {
        val app = getApplication<Application>()
        val launch = app.packageManager.getLaunchIntentForPackage(ConnectClient.HOST_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
            runCatching { app.startActivity(launch) }
        }
    }

    private suspend fun ensureBoundClient(): ConnectClient {
        connectClient?.let { return it }
        val client = ConnectClient(getApplication()).connect()
        connectClient = client
        eventJob?.cancel()
        eventJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                client.events.collect { event ->
                    when (event.topic) {
                        "imu" -> {
                            val batch = runCatching { event.imuBatch() }.getOrNull() ?: return@collect
                            expectedNextSeq?.let { expected ->
                                if (batch.sequenceStart != expected) {
                                    mutableState.update { s -> s.copy(sequenceGaps = s.sequenceGaps + 1) }
                                }
                            }
                            expectedNextSeq = (batch.sequenceStart + batch.samples.size) and 0xFFFF_FFFFL
                            batch.attitude?.let { att ->
                                val ts = LongArray(batch.samples.size) { i -> batch.samples[i].timestampMs }
                                trajectoryBuffer.pushBatch(ts, batch.trajectoryQuaternions, att)
                            }
                        }
                        "gesture" -> {
                            val gesture = runCatching { event.gestureEvent() }.getOrNull() ?: return@collect
                            if (gesture is RingGestureEvent.DoubleTap ||
                                gesture is RingGestureEvent.ButtonSingleClick ||
                                gesture is RingGestureEvent.ButtonDoubleClick
                            ) {
                                runCatching { client.zeroAttitudeHeading() }
                                trajectoryBuffer.requestFlushOnNextBatch()
                            }
                            pulseLed()
                        }
                        "status" -> {
                            val status = runCatching { event.statusSnapshot() }.getOrNull() ?: return@collect
                            applyConnectStatus(status)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                releaseClient()
                mutableState.update {
                    it.copy(connection = ConnectionState.UNBOUND, errorBanner = e.message)
                }
            }
        }
        return client
    }

    private fun publishAttitude(att: ImuAttitudeData, tick: Long) {
        val _unused = tick
        trajectoryBuffer.setImmediate(
            LiveMotionSample(
                q = PoseQuaternion(att.qw, att.qx, att.qy, att.qz),
                wxDps = att.wxDps,
                wyDps = att.wyDps,
                wzDps = att.wzDps,
                stationary = att.stationary,
            ),
        )
        mutableState.update {
            it.copy(pose = att, ledPulseIntensity = liveLedPulse)
        }
    }

    private fun pulseLed() {
        liveLedPulse = 1.0f
    }

    private fun applyConnectStatus(status: ConnectStatus) {
        val currentConn = mutableState.value.connection
        val nextConn = when {
            status.phase == "connected" && currentConn == ConnectionState.STREAMING_LIVE -> ConnectionState.STREAMING_LIVE
            status.phase == "connected" -> ConnectionState.RING_READY
            else -> ConnectionState.RING_OFFLINE
        }
        mutableState.update {
            it.copy(
                connection = nextConn,
                deviceName = status.model ?: status.deviceName,
                batteryPercent = status.batteryPercent,
                charging = status.charging,
                calibratedComponents = status.calibratedComponents,
                gyroBiasDps = status.gyroBiasDps,
                errorBanner = null,
            )
        }
        // Auto-start IMU stream as soon as Connect finishes reconnecting to the ring!
        if (nextConn == ConnectionState.RING_READY && currentConn == ConnectionState.RING_OFFLINE) {
            connectClient?.let { client -> viewModelScope.launch { startImuStreamInternal(client) } }
        }
    }

    private fun stopSimulationInternal() {
        simJob?.cancel()
        simJob = null
    }

    private fun releaseClient() {
        eventJob?.cancel()
        eventJob = null
        runCatching { connectClient?.close() }
        connectClient = null
    }

    override fun onCleared() {
        stopSimulationInternal()
        releaseClient()
        super.onCleared()
    }

    companion object {
        private val REQUIRED_SCOPES = setOf("info", "sensors")

        internal fun synthesizePreviewAttitude(q: PoseQuaternion): ImuAttitudeData {
            val qn = q.normalized()
            val sinp = (2.0 * (qn.w * qn.y - qn.z * qn.x)).coerceIn(-1.0, 1.0)
            val pitch = Math.toDegrees(asin(sinp))
            val roll: Double
            val yaw: Double
            if (abs(sinp) >= 0.9985) {
                roll = 0.0
                yaw = Math.toDegrees(atan2(2.0 * (qn.w * qn.z - qn.x * qn.y), 1.0 - 2.0 * (qn.x * qn.x + qn.z * qn.z)))
            } else {
                roll = Math.toDegrees(atan2(2.0 * (qn.w * qn.x + qn.y * qn.z), 1.0 - 2.0 * (qn.x * qn.x + qn.y * qn.y)))
                yaw = Math.toDegrees(atan2(2.0 * (qn.w * qn.z + qn.x * qn.y), 1.0 - 2.0 * (qn.y * qn.y + qn.z * qn.z)))
            }

            val boreZ = 2.0 * (qn.x * qn.z - qn.w * qn.y)
            val sideZ = 2.0 * (qn.y * qn.z + qn.w * qn.x)
            val topZ = 1.0 - 2.0 * (qn.x * qn.x + qn.y * qn.y)
            val tilt = Math.toDegrees(acos(topZ.coerceIn(-1.0, 1.0)))
            val posture = when {
                abs(boreZ) >= 0.8660254 -> RingPostureKind.VERTICAL_BORE_AXIS
                abs(boreZ) <= 0.4694716 && topZ >= 0.8660254 -> RingPostureKind.HORIZONTAL_UPRIGHT
                abs(boreZ) <= 0.4694716 && topZ <= -0.8660254 -> RingPostureKind.HORIZONTAL_FLIPPED
                abs(boreZ) <= 0.4694716 && abs(sideZ) >= 0.8660254 -> RingPostureKind.VERTICAL_STANDING_EDGE
                else -> RingPostureKind.TILTED
            }
            val mat = FloatArray(16)
            qn.toOpenGlMatrix(mat)
            return ImuAttitudeData(qn.w, qn.x, qn.y, qn.z, roll, pitch, yaw, tilt, posture, mat, true)
        }

        private fun fromAxisAngle(ax: Double, ay: Double, az: Double, rad: Double): PoseQuaternion {
            val n = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-9)
            val s = sin(rad * 0.5) / n
            return PoseQuaternion(cos(rad * 0.5), ax * s, ay * s, az * s)
        }

        private fun mulQ(a: PoseQuaternion, b: PoseQuaternion): PoseQuaternion = PoseQuaternion(
            w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
            x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
            y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        )

        private fun simulatedTargetQuaternion(preset: SimPreset, t: Double): PoseQuaternion = when (preset) {
            SimPreset.HORIZONTAL_UP -> mulQ(
                fromAxisAngle(1.0, 0.0, 0.0, sin(t * 1.4) * 0.06),
                fromAxisAngle(0.0, 1.0, 0.0, cos(t * 1.1) * 0.05),
            )
            SimPreset.HORIZONTAL_FLIP -> fromAxisAngle(1.0, 0.0, 0.0, Math.PI + sin(t * 1.3) * 0.06)
            SimPreset.VERTICAL_EDGE -> fromAxisAngle(1.0, 0.0, 0.0, Math.PI * 0.5 + sin(t * 1.2) * 0.05)
            SimPreset.FLAT_BORE_UP -> fromAxisAngle(0.0, 1.0, 0.0, -Math.PI * 0.5 + sin(t * 1.0) * 0.04)
            SimPreset.ORBIT_SHOWCASE -> {
                val cycle = (t * 0.38) % (Math.PI * 2.0)
                mulQ(
                    mulQ(
                        fromAxisAngle(0.0, 0.0, 1.0, t * 0.35),
                        fromAxisAngle(0.0, 1.0, 0.0, sin(cycle * 0.5) * 0.85),
                    ),
                    fromAxisAngle(1.0, 0.0, 0.0, sin(cycle) * Math.PI),
                )
            }
        }
    }
}
