package com.openzilo.connect.api

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ConnectStatus(
    val phase: String,
    val deviceName: String,
    val model: String?,
    val firmwareVersion: String?,
    val boardTarget: String?,
    val batteryPercent: Int?,
    val charging: Boolean,
    val ringKey: String?,
    val calibratedComponents: List<String>,
    val gyroBiasDps: List<Double>?,
    val message: String?,
)

data class ImuConfigInfo(
    val sampleRateHz: Int,
    val accelRangeG: Int,
    val gyroRangeDps: Int,
)

data class ImuSampleData(
    val timestampMs: Long,
    val axG: Double,
    val ayG: Double,
    val azG: Double,
    val gxDps: Double,
    val gyDps: Double,
    val gzDps: Double,
)

enum class RingPostureKind {
    HORIZONTAL_UPRIGHT,
    HORIZONTAL_FLIPPED,
    VERTICAL_STANDING_EDGE,
    VERTICAL_BORE_AXIS,
    TILTED,
    DYNAMIC_MOTION,
}

enum class RingAxisPresetKind {
    STANDARD,
    SWAPPED_XY,
    Z_BORE,
}

data class ImuAttitudeData(
    val qw: Double,
    val qx: Double,
    val qy: Double,
    val qz: Double,
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
    val tiltFromZenithDeg: Double,
    val posture: RingPostureKind,
    val openGlMatrix: FloatArray,
    val stationary: Boolean,
    val wxDps: Double = 0.0,
    val wyDps: Double = 0.0,
    val wzDps: Double = 0.0,
)

data class ImuBatchEvent(
    val sequenceStart: Long,
    val samples: List<ImuSampleData>,
    val appliedComponents: List<String>,
    val attitude: ImuAttitudeData?,
    val trajectoryQuaternions: DoubleArray? = null,
)

sealed interface RingGestureEvent {
    val timestamp: Long
    data class DoubleTap(override val timestamp: Long) : RingGestureEvent
    data class SensorGesture(override val timestamp: Long, val gestureId: Int, val name: String) : RingGestureEvent
    data class ButtonDoubleClick(override val timestamp: Long) : RingGestureEvent
    data class ButtonSingleClick(override val timestamp: Long) : RingGestureEvent
}

fun Bundle.toConnectStatus(): ConnectStatus {
    check(getInt("apiVersion") == ConnectClient.API_VERSION) { "Unsupported Connect API version" }
    return ConnectStatus(
        phase = getString("phase") ?: "disconnected",
        deviceName = getString("deviceName").orEmpty(),
        model = getString("model"),
        firmwareVersion = getString("firmwareVersion"),
        boardTarget = getString("boardTarget"),
        batteryPercent = if (containsKey("batteryPercent")) getInt("batteryPercent") else null,
        charging = getBoolean("charging", false),
        ringKey = getString("ringKey"),
        calibratedComponents = getStringArray("calibratedComponents")?.toList().orEmpty(),
        gyroBiasDps = getDoubleArray("gyroBiasDps")?.toList(),
        message = getString("message"),
    )
}

data class ConnectEvent(val topic: String, val data: Bundle) {
    fun liveAudio(): LiveAudioEvent? {
        if (topic != "live") return null
        check(data.getInt("apiVersion") == ConnectClient.API_VERSION) { "Unsupported Connect API version" }
        fun sessionId() = data.getLong("sessionId").also { require(it > 0) }
        return when (data.getString("type")) {
            "started" -> LiveAudioEvent.Started(
                sessionId(), data.getInt("sampleRate").also { require(it == 16000) },
                data.getInt("channels").also { require(it in 1..2) },
            )
            "frame" -> LiveAudioEvent.Frame(
                sessionId(), data.getLong("sequence", -1).also { require(it >= 0) },
                requireNotNull(data.getByteArray("data")).also { require(it.size in 1..100) },
            )
            "ended" -> LiveAudioEvent.Ended(sessionId(), data.getLong("byteCount", -1).also { require(it >= 0) })
            "interrupted" -> LiveAudioEvent.Interrupted
            else -> error("Unknown live audio event")
        }
    }

    fun imuBatch(): ImuBatchEvent? {
        if (topic != "imu") return null
        check(data.getInt("apiVersion") == ConnectClient.API_VERSION) { "Unsupported Connect API version" }
        val ts = data.getLongArray("timestampsMs") ?: return null
        val accel = data.getDoubleArray("accelG") ?: return null
        val gyro = data.getDoubleArray("gyroDps") ?: return null
        if (accel.size != ts.size * 3 || gyro.size != ts.size * 3) return null
        val samples = ts.indices.map { i ->
            val o = i * 3
            ImuSampleData(ts[i], accel[o], accel[o + 1], accel[o + 2], gyro[o], gyro[o + 1], gyro[o + 2])
        }
        val q = data.getDoubleArray("quaternion")
        val euler = data.getDoubleArray("eulerDeg")
        val gyroBody = data.getDoubleArray("gyroBodyDps")
        val glMat = data.getFloatArray("openGlMatrix")
        val postureName = data.getString("posture")
        val attitude = if (q != null && q.size == 4 && euler != null && euler.size == 3 && glMat != null && glMat.size == 16 && postureName != null) {
            ImuAttitudeData(
                qw = q[0], qx = q[1], qy = q[2], qz = q[3],
                rollDeg = euler[0], pitchDeg = euler[1], yawDeg = euler[2],
                tiltFromZenithDeg = data.getDouble("tiltFromZenithDeg", 0.0),
                posture = runCatching { RingPostureKind.valueOf(postureName) }.getOrDefault(RingPostureKind.TILTED),
                openGlMatrix = glMat,
                stationary = data.getBoolean("stationary", false),
                wxDps = gyroBody?.getOrNull(0) ?: 0.0,
                wyDps = gyroBody?.getOrNull(1) ?: 0.0,
                wzDps = gyroBody?.getOrNull(2) ?: 0.0,
            )
        } else null
        val trajQuats = data.getDoubleArray("trajectoryQuaternions")?.takeIf { it.size == samples.size * 4 }
        return ImuBatchEvent(
            sequenceStart = data.getLong("sequenceStart", 0L),
            samples = samples,
            appliedComponents = data.getStringArray("appliedComponents")?.toList().orEmpty(),
            attitude = attitude,
            trajectoryQuaternions = trajQuats,
        )
    }

    fun gestureEvent(): RingGestureEvent? {
        if (topic != "gesture") return null
        check(data.getInt("apiVersion") == ConnectClient.API_VERSION) { "Unsupported Connect API version" }
        val command = data.getInt("command", 0)
        val ts = if (data.containsKey("timestamp")) {
            data.getLong("timestamp")
        } else {
            val body = data.getByteArray("body") ?: return null
            if (body.size < 4) return null
            ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        }
        return when (command) {
            0x0701 -> RingGestureEvent.DoubleTap(ts)
            0x0702 -> RingGestureEvent.SensorGesture(
                ts,
                data.getInt("gestureId", 0),
                data.getString("gestureName") ?: "unknown",
            )
            0x0703 -> RingGestureEvent.ButtonDoubleClick(ts)
            0x0704 -> RingGestureEvent.ButtonSingleClick(ts)
            else -> null
        }
    }

    fun statusSnapshot(): ConnectStatus? = if (topic == "status") data.toConnectStatus() else null
}

sealed interface LiveAudioEvent {
    data class Started(val sessionId: Long, val sampleRate: Int, val channels: Int) : LiveAudioEvent
    data class Frame(val sessionId: Long, val sequence: Long, val data: ByteArray) : LiveAudioEvent
    data class Ended(val sessionId: Long, val byteCount: Long) : LiveAudioEvent
    data object Interrupted : LiveAudioEvent
}

/** One client owns one bounded event consumer; no Bluetooth or native Rust library is required. */
class ConnectClient(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Bundle>>()
    private val eventChannel = Channel<ConnectEvent>(64)
    private val closed = AtomicBoolean(false)
    private var connection: ServiceConnection? = null
    @Volatile private var remote: IConnectService? = null
    private var remoteBinder: IBinder? = null
    private val death = IBinder.DeathRecipient { disconnected(IllegalStateException("Connect service stopped")) }
    val events: Flow<ConnectEvent> = eventChannel.receiveAsFlow()

    private val callback = object : IConnectCallback.Stub() {
        override fun onResult(requestId: String, result: Bundle) {
            pending.remove(requestId)?.complete(Bundle(result))
        }
        override fun onEvent(topic: String, event: Bundle) {
            if (eventChannel.trySend(ConnectEvent(topic, Bundle(event))).isFailure) {
                val error = IllegalStateException("Connect event consumer is too slow")
                eventChannel.close(error)
                scope.launch {
                    try { runCatching { remote?.closeClient(objectCallback()) } }
                    finally { disconnected(error) }
                }
            }
        }
    }
    private fun objectCallback(): IConnectCallback = callback

    suspend fun connect(timeoutMs: Long = 2_000): ConnectClient = bindMutex.withLock {
        check(!closed.get()) { "Client is closed" }
        if (remote != null) return@withLock this
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (!continuation.isActive || closed.get()) { releaseConnection(); return }
                        try {
                            service.linkToDeath(death, 0)
                            remoteBinder = service
                            remote = IConnectService.Stub.asInterface(service)
                            continuation.resume(Unit)
                        } catch (error: Exception) {
                            disconnected(error)
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        disconnected(IllegalStateException("Connect service disconnected"))
                    }
                    override fun onBindingDied(name: ComponentName) {
                        disconnected(IllegalStateException("Connect service binding died"))
                    }
                    override fun onNullBinding(name: ComponentName) {
                        val error = IllegalStateException("Connect service rejected binding")
                        disconnected(error)
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                connection = serviceConnection
                continuation.invokeOnCancellation { releaseConnection() }
                try {
                    val intent = Intent().setComponent(ComponentName(HOST_PACKAGE, HOST_SERVICE))
                    if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                        releaseConnection()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Install OpenZilo Connect before binding"))
                    }
                } catch (error: Exception) {
                    releaseConnection()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
        this
    }

    suspend fun requestAuthorization(scopes: Set<String>): PendingIntent = withContext(Dispatchers.IO) {
        service().requestAuthorization(scopes.toTypedArray())
    }

    suspend fun status(): Bundle = withContext(Dispatchers.IO) { service().status }

    suspend fun ringStatus(): ConnectStatus = status().toConnectStatus()

    suspend fun startImu(): ImuConfigInfo {
        val reply = execute("imu.start")
        check(reply.getBoolean("ok", false)) { reply.getString("error") ?: "imu.start failed" }
        if (reply.containsKey("sampleRateHz")) {
            return ImuConfigInfo(
                sampleRateHz = reply.getInt("sampleRateHz"),
                accelRangeG = reply.getInt("accelRangeG"),
                gyroRangeDps = reply.getInt("gyroRangeDps"),
            )
        }
        val body = requireNotNull(reply.getByteArray("body")) { "Missing 0x0602 body" }
        require(body.size == 8) { "Invalid 0x0602 response size" }
        val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        check((buf.short.toInt() and 0xFFFF) == 0) { "Device rejected IMU start" }
        return ImuConfigInfo(
            sampleRateHz = buf.short.toInt() and 0xFFFF,
            accelRangeG = buf.short.toInt() and 0xFFFF,
            gyroRangeDps = buf.short.toInt() and 0xFFFF,
        )
    }

    suspend fun stopImu() {
        val reply = execute("imu.stop")
        check(reply.getBoolean("ok", false)) { reply.getString("error") ?: "imu.stop failed" }
    }

    suspend fun zeroAttitudeHeading() {
        val reply = execute("imu.attitude.zero")
        check(reply.getBoolean("ok", false)) { reply.getString("error") ?: "imu.attitude.zero failed" }
    }

    suspend fun configureAttitudeAxes(preset: RingAxisPresetKind, invertRadialZ: Boolean) {
        val args = Bundle().apply {
            putString("preset", preset.name)
            putBoolean("invertRadialZ", invertRadialZ)
        }
        val reply = execute("imu.attitude.mapping", args)
        check(reply.getBoolean("ok", false)) { reply.getString("error") ?: "imu.attitude.mapping failed" }
    }

    suspend fun calibrateGyroBias(durationMs: Int = 3_000): Bundle {
        val args = Bundle().apply { putInt("durationMs", durationMs) }
        val reply = execute("imu.calibrate.gyro", args, timeoutMs = (durationMs + 15_000).toLong())
        check(reply.getBoolean("ok", false)) { reply.getString("detail") ?: reply.getString("error") ?: "imu.calibrate.gyro failed" }
        return reply
    }

    suspend fun calibrateUprightReference(): Bundle {
        val reply = execute("imu.calibrate.upright")
        check(reply.getBoolean("ok", false)) { reply.getString("detail") ?: reply.getString("error") ?: "imu.calibrate.upright failed" }
        return reply
    }

    suspend fun resetImuCalibration(): Bundle {
        val reply = execute("imu.calibration.reset")
        check(reply.getBoolean("ok", false)) { reply.getString("detail") ?: reply.getString("error") ?: "imu.calibration.reset failed" }
        return reply
    }

    suspend fun execute(operation: String, arguments: Bundle = Bundle(), timeoutMs: Long = 30_000): Bundle {
        require(timeoutMs in 1..600_000)
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<Bundle>()
        synchronized(pending) {
            check(pending.size < 4) { "Too many requests" }
            pending[requestId] = result
        }
        var delivered = false
        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) { service().execute(requestId, operation, arguments, callback) }
                result.await().also { delivered = true }
            }
        } finally {
            pending.remove(requestId)
            result.cancel()
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) { runCatching { remote?.cancel(requestId, callback) } }
        }
    }

    suspend fun subscribe(topics: Set<String>) = withContext(Dispatchers.IO) {
        service().subscribe(topics.toTypedArray(), callback)
    }

    suspend fun unsubscribe() = withContext(Dispatchers.IO) { service().unsubscribe(callback) }

    private fun service(): IConnectService {
        check(!closed.get()) { "Client is closed" }
        return remote ?: throw IllegalStateException("Call connect() before using ConnectClient")
    }

    @Synchronized
    private fun disconnected(error: Throwable) {
        closed.set(true)
        remote = null
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        eventChannel.close(error)
        releaseConnection()
        scope.cancel()
    }

    @Synchronized
    private fun releaseConnection() {
        remoteBinder?.let { binder -> runCatching { binder.unlinkToDeath(death, 0) } }
        remoteBinder = null
        remote = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { runCatching { remote?.closeClient(callback) } }
        finally { disconnected(IllegalStateException("Client is closed")) }
    }

    companion object {
        const val API_VERSION = 2
        const val HOST_PACKAGE = "com.openzilo.connect"
        const val HOST_SERVICE = "com.openzilo.connect.ConnectService"
        const val HOST_WAKE_ACTIVITY = "com.openzilo.connect.WakeActivity"

        fun wakeHostIntent(): Intent = Intent("com.openzilo.connect.WAKE")
            .setComponent(ComponentName(HOST_PACKAGE, HOST_WAKE_ACTIVITY))
    }
}
