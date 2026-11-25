package com.openzilo.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.openzilo.connect.api.ConnectClient
import com.openzilo.connect.api.IConnectCallback
import com.openzilo.connect.api.IConnectService
import com.zilo.libopenzilo.DeviceRequest
import com.zilo.libopenzilo.ImuAttitudeEstimator
import com.zilo.libopenzilo.ImuAxisMapping
import com.zilo.libopenzilo.ImuAxisPreset
import com.zilo.libopenzilo.ImuConfig
import com.zilo.libopenzilo.ImuSample
import com.zilo.libopenzilo.LiveStreamEvent
import com.zilo.libopenzilo.PhysicalImuBatch
import com.zilo.libopenzilo.RecordingConfig
import com.zilo.libopenzilo.analyzeDeskImuWindow
import com.zilo.libopenzilo.defaultRingAxisMapping
import com.zilo.libopenzilo.parseDeviceEvent
import com.zilo.libopenzilo.parseImu
import com.zilo.libopenzilo.parseImuConfig
import com.zilo.libopenzilo.quaternionToOpenglMatrix
import com.zilo.libopenzilo.scopedDeviceKey
import com.zilo.libopenzilo.sensorGestureName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import com.openzilo.connect.core.RingIdentityMismatch
import com.openzilo.connect.core.ringIdentity
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private fun LiveStreamEvent.toBundle(): Bundle = Bundle().apply {
    when (val event = this@toBundle) {
        is LiveStreamEvent.Started -> {
            putString("type", "started")
            putLong("sessionId", event.value.sessionId.toLong())
            putLong("recordTime", event.value.recordTime.toLong())
            putInt("sampleRate", event.value.sampleRate.toInt())
            putInt("channels", event.value.channels.toInt())
            putString("codec", "opus")
        }
        is LiveStreamEvent.Frame -> {
            putString("type", "frame")
            putLong("sessionId", event.value.sessionId.toLong())
            putLong("sequence", event.value.sequence.toLong())
            putByteArray("data", event.value.data)
        }
        is LiveStreamEvent.Ended -> {
            putString("type", "ended")
            putLong("sessionId", event.value.sessionId.toLong())
            putLong("byteCount", event.value.sentBytes.toLong())
        }
        LiveStreamEvent.Interrupted -> putString("type", "interrupted")
    }
}

class ConnectService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subscriptions = ConcurrentHashMap<IBinder, Subscription>()
    private val active = ConcurrentHashMap<Int, Int>()
    private val operations = ConcurrentHashMap<String, Operation>()
    private val controls = ConcurrentHashMap<String, ControlOwner>()
    private val releasing = ConcurrentHashMap<String, ControlOwner>()
    private val imuMutex = Mutex()
    private val imuLeases = ConcurrentHashMap<IBinder, ImuLease>()
    private val attitudeEstimators = ConcurrentHashMap<IBinder, ImuAttitudeEstimator>()
    @Volatile private var activeImuState: ActiveImuState? = null
    private val host get() = application as ConnectApplication

    private class DeviceOperationException(val code: Int, val command: Int, val body: ByteArray) : IllegalStateException("Device rejected operation")
    private data class Operation(val principal: AppPrincipal, val permission: String, val requestId: String, val callback: IBinder, val job: Job)
    private data class ControlOwner(val principal: AppPrincipal, val permission: String, val requestId: String, val binder: IBinder, val death: IBinder.DeathRecipient, val epoch: Long, val fileIndex: UInt? = null)
    private data class ImuLease(val principal: AppPrincipal, val binder: IBinder?, val death: IBinder.DeathRecipient?, val epoch: Long)
    private data class ActiveImuState(val epoch: Long, val responseBody: ByteArray, val config: ImuConfig)
    private var started = false

    private data class Subscription(
        val principal: AppPrincipal,
        val callback: IConnectCallback,
        val topics: Set<String>,
        val queue: Channel<Pair<String, Bundle>>,
        val death: IBinder.DeathRecipient,
        val job: Job,
    )

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, localizedContext.getString(R.string.auth_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch {
            host.controller.events.collect { ringEvent ->
                if (ringEvent.epoch != host.controller.connectionEpoch) return@collect
                val packet = ringEvent.packet
                val topic = GrantPolicy.topic(packet.command.toInt()) ?: return@collect
                if (packet.body.size > 5120) return@collect
                val physicalImu = if (topic == "imu") decodePhysicalImu(packet.body, ringEvent.epoch) else null
                val baseEvent = if (topic == "live") {
                    ringEvent.live?.toBundle() ?: return@collect
                } else {
                    Bundle().apply {
                        putInt("command", packet.command.toInt())
                        putByteArray("body", packet.body)
                        if (topic == "imu" && physicalImu != null) writePhysicalImuBundle(this, physicalImu)
                        if (topic == "gesture") enrichGestureBundle(this, packet.command.toInt(), packet.body)
                    }
                }
                baseEvent.putInt("apiVersion", ConnectClient.API_VERSION)
                subscriptions.values.toList().forEach { subscription ->
                    if (topic in subscription.topics && ownsEvent(topic, subscription.principal)) {
                        try {
                            host.grants.requireAccess(subscription.principal, GrantPolicy.topicScope(topic))
                            val outbound = if (topic == "imu" && physicalImu != null) {
                                Bundle(baseEvent).also { attachClientAttitude(it, subscription.callback.asBinder(), physicalImu) }
                            } else baseEvent
                            if (subscription.queue.trySend(topic to outbound).isFailure) removeSubscription(subscription.callback.asBinder())
                        } catch (_: Exception) { removeSubscription(subscription.callback.asBinder()) }
                    }
                }
            }
        }
        scope.launch {
            host.controller.state.collect {
                val epoch = host.controller.connectionEpoch
                controls.entries.toList().forEach { (resource, owner) ->
                    if (resource != "firmware" && owner.epoch != epoch) dropControl(resource, owner)
                }
                clearStaleImuLeases(epoch)
                broadcastStatus()
            }
        }
        scope.launch {
            host.grants.grants.collect {
                subscriptions.values.toList().forEach { subscription ->
                    try { subscription.topics.forEach { topic -> host.grants.requireAccess(subscription.principal, GrantPolicy.topicScope(topic)) } }
                    catch (_: Exception) { removeSubscription(subscription.callback.asBinder()) }
                }
                operations.values.toList().forEach { operation ->
                    try { host.grants.requireAccess(operation.principal, operation.permission) }
                    catch (_: Exception) { operation.job.cancel() }
                }
                controls.entries.toList().forEach { (resource, owner) ->
                    try { host.grants.requireAccess(owner.principal, owner.permission) }
                    catch (_: Exception) { loseControl(resource, owner) }
                }
                imuLeases.entries.toList().forEach { (key, lease) ->
                    try { host.grants.requireAccess(lease.principal, "sensors") }
                    catch (_: Exception) { scope.launch { releaseImuLease(key) } }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        ensureAutoReconnectIfBound()
        return binder
    }

    private fun ensureAutoReconnectIfBound() {
        val saved = host.binding.value ?: return
        if (!saved.autoConnect) return
        if (started && connectionTask?.isActive == true) return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { startForegroundService(resumeIntent(this)) }
    }

    private val binder = object : IConnectService.Stub() {
        override fun requestAuthorization(scopes: Array<out String>): PendingIntent {
            require(scopes.size <= GrantPolicy.scopes.size) { "Too many permissions" }
            val request = host.grants.newRequest(host.grants.caller(), scopes.toSet())
            val identity = Binder.clearCallingIdentity()
            return try {
                PendingIntent.getActivity(
                    this@ConnectService, request.token.hashCode(),
                    Intent(this@ConnectService, GrantActivity::class.java)
                        .setAction("com.openzilo.connect.AUTHORIZE.${request.token}")
                        .putExtra(GrantActivity.EXTRA_TOKEN, request.token),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            } finally { Binder.restoreCallingIdentity(identity) }
        }

        override fun getStatus(): Bundle {
            val principal = host.grants.caller()
            host.grants.requireAccess(principal, "info")
            return status(principal)
        }

        override fun execute(requestId: String, operation: String, arguments: Bundle, callback: IConnectCallback) {
            require(requestId.length in 1..64) { "Invalid request ID" }
            val permission = GrantPolicy.operationScope(operation)
            val principal = host.grants.caller()
            host.grants.requireAccess(principal, permission)
            arguments.classLoader = javaClass.classLoader
            val parcel = Parcel.obtain()
            try { parcel.writeBundle(arguments); require(parcel.dataSize() <= 5120) { "Arguments exceed 5120 bytes" } }
            finally { parcel.recycle() }
            val copied = Bundle(arguments)
            synchronized(active) {
                val count = active[principal.uid] ?: 0
                if (count >= 4 || active.values.sum() >= 32) throw IllegalStateException("Too many active operations")
                active[principal.uid] = count + 1
            }
            val operationId = UUID.randomUUID().toString()
            val callbackBinder = callback.asBinder()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    withTimeout(if (operation == "firmware.upgrade") 600_000L else 30_000L) {
                        host.grants.requireAccess(principal, permission)
                        perform(operation, copied, principal, callbackBinder, requestId).apply {
                            putBoolean("ok", true)
                            putInt("apiVersion", ConnectClient.API_VERSION)
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) {
                        controls.entries.toList().filter { it.value.binder == callbackBinder && it.value.requestId == requestId }
                            .forEach { loseControl(it.key, it.value) }
                        throw failure
                    }
                    Bundle().apply {
                        putBoolean("ok", false)
                        putInt("apiVersion", ConnectClient.API_VERSION)
                        putString("error", when (failure) {
                            is SecurityException -> "permission_denied"
                            is DeviceOperationException -> "device_rejected"
                            is IllegalArgumentException -> "invalid_arguments"
                            else -> "operation_failed"
                        })
                        putString("message", localizedContext.getString(R.string.auth_operation_failed))
                        failure.message?.takeIf { it.isNotBlank() }?.let { putString("detail", it) }
                        if (failure is DeviceOperationException) {
                            putInt("deviceCode", failure.code)
                            putInt("command", failure.command)
                            putByteArray("body", failure.body)
                        }
                    }
                }
                try {
                    host.grants.requireAccess(principal, permission)
                    callback.onResult(requestId, result)
                } catch (_: Exception) { }
            }
            val death = IBinder.DeathRecipient { job.cancel() }
            operations[operationId] = Operation(principal, permission, requestId, callbackBinder, job)
            job.invokeOnCompletion {
                operations.remove(operationId)
                runCatching { callbackBinder.unlinkToDeath(death, 0) }
                runCatching { (copied.getParcelableCompat("firmware") as? ParcelFileDescriptor)?.close() }
                synchronized(active) {
                    val count = (active[principal.uid] ?: 1) - 1
                    if (count == 0) active.remove(principal.uid) else active[principal.uid] = count
                }
            }
            try { callbackBinder.linkToDeath(death, 0); job.start() }
            catch (failure: Exception) { job.cancel(); throw failure }
        }

        override fun subscribe(topics: Array<out String>, callback: IConnectCallback) {
            require(topics.isNotEmpty() && topics.size <= GrantPolicy.topicScopes.size) { "Invalid topics" }
            val principal = host.grants.caller()
            val selected = topics.toSet()
            selected.forEach { host.grants.requireAccess(principal, GrantPolicy.topicScope(it)) }
            val key = callback.asBinder()
            synchronized(subscriptions) {
                val existing = subscriptions[key]
                if (existing != null && existing.principal.uid != principal.uid) throw SecurityException("Listener belongs to another UID")
                if (existing == null && (subscriptions.size >= 32 || subscriptions.values.count { it.principal.uid == principal.uid } >= 4)) throw IllegalStateException("Too many listeners")
                removeSubscription(key, releaseStreams = false)
                val queue = Channel<Pair<String, Bundle>>(64)
                val death = IBinder.DeathRecipient { removeSubscription(key) }
                key.linkToDeath(death, 0)
                val job = scope.launch {
                    try {
                        for ((topic, event) in queue) {
                            host.grants.requireAccess(principal, GrantPolicy.topicScope(topic))
                            if (ownsEvent(topic, principal)) callback.onEvent(topic, event)
                        }
                    } catch (_: Exception) { }
                    finally { removeSubscription(key, queue) }
                }
                subscriptions[key] = Subscription(principal, callback, selected, queue, death, job)
                if (!key.isBinderAlive) removeSubscription(key)
            }
        }

        override fun cancel(requestId: String, callback: IConnectCallback) {
            require(requestId.length in 1..64)
            val principal = host.grants.caller()
            val key = callback.asBinder()
            operations.values.toList().filter { it.principal.uid == principal.uid && it.callback == key && it.requestId == requestId }
                .forEach { it.job.cancel() }
            controls.entries.toList().filter { it.value.principal.uid == principal.uid && it.value.binder == key && it.value.requestId == requestId }
                .forEach { loseControl(it.key, it.value) }
        }

        override fun closeClient(callback: IConnectCallback) {
            val principal = host.grants.caller()
            val key = callback.asBinder()
            operations.values.toList().filter { it.principal.uid == principal.uid && it.callback == key }.forEach { it.job.cancel() }
            controls.entries.toList().filter { it.value.principal.uid == principal.uid && it.value.binder == key }.forEach { loseControl(it.key, it.value) }
            scope.launch { releaseImuLease(key) }
            val subscription = subscriptions[key]
            if (subscription != null) {
                if (subscription.principal.uid != principal.uid) throw SecurityException("Listener belongs to another UID")
                removeSubscription(key)
            }
        }

        override fun unsubscribe(callback: IConnectCallback) {
            val principal = host.grants.caller()
            val key = callback.asBinder()
            synchronized(subscriptions) {
                val existing = subscriptions[key] ?: return
                if (existing.principal.uid != principal.uid || existing.principal.certificate != principal.certificate) throw SecurityException("Listener belongs to another application")
                removeSubscription(key)
            }
        }
    }

    private fun removeSubscription(key: IBinder, expectedQueue: Channel<Pair<String, Bundle>>? = null, releaseStreams: Boolean = true) {
        val item = subscriptions[key] ?: return
        if (expectedQueue != null && item.queue !== expectedQueue) return
        if (!subscriptions.remove(key, item)) return
        item.queue.close()
        item.job.cancel()
        runCatching { key.unlinkToDeath(item.death, 0) }
        if (releaseStreams) {
            val owner = controls["live"]
            if (owner != null && owner.principal.uid == item.principal.uid &&
                subscriptions.values.none { it.principal.uid == owner.principal.uid && "live" in it.topics }
            ) {
                loseControl("live", owner)
            }
            if ("imu" in item.topics && subscriptions.values.none { it.callback.asBinder() == key && "imu" in it.topics }) {
                attitudeEstimators.remove(key)?.close()
                scope.launch { releaseImuLease(key) }
            }
        }
    }

    private fun status(principal: AppPrincipal? = null): Bundle = Bundle().apply {
        val value = host.controller.state.value
        putInt("apiVersion", ConnectClient.API_VERSION)
        putString("phase", value.phase)
        putString("deviceName", value.deviceName)
        putString("message", value.message)
        value.firmwareProgress?.let { putInt("firmwareProgress", it) }
        value.capabilities?.boardTarget?.let { putString("boardTarget", it) }
        value.info?.let { info ->
            putString("model", info.model)
            putString("firmwareVersion", info.firmwareVersion)
            putInt("batteryPercent", info.batteryPercent.toInt())
            putBoolean("charging", info.batteryCharging)
            putStringArray("calibratedComponents", host.calibrations.calibratedComponents(info).toTypedArray())
            host.calibrations.gyroBiasDps(info)?.let { putDoubleArray("gyroBiasDps", it) }
            if (principal != null) {
                runCatching { scopedDeviceKey(info, "${principal.packageName}:${principal.certificate}") }
                    .getOrNull()
                    ?.let { putString("ringKey", it) }
            }
        }
    }

    private fun broadcastStatus() {
        subscriptions.values.toList().forEach { subscription ->
            if ("status" in subscription.topics) {
                try {
                    host.grants.requireAccess(subscription.principal, "info")
                    val snapshot = status(subscription.principal)
                    if (subscription.queue.trySend("status" to snapshot).isFailure) {
                        removeSubscription(subscription.callback.asBinder())
                    }
                } catch (_: Exception) {
                    removeSubscription(subscription.callback.asBinder())
                }
            }
        }
    }

    private fun decodePhysicalImu(rawBody: ByteArray, epoch: Long): PhysicalImuBatch? {
        val imuState = activeImuState?.takeIf { it.epoch == epoch } ?: return null
        val rawBatch = runCatching { parseImu(rawBody) }.getOrNull() ?: return null
        val ringSnapshot = host.controller.state.value
        return runCatching {
            host.calibrations.applyOrConvert(
                rawBatch,
                ringSnapshot.info,
                imuState.config,
                ringSnapshot.capabilities?.features.orEmpty(),
            )
        }.getOrNull()
    }

    private fun writePhysicalImuBundle(bundle: Bundle, physical: PhysicalImuBatch) {
        val n = physical.samples.size
        val ts = LongArray(n)
        val accel = DoubleArray(n * 3)
        val gyro = DoubleArray(n * 3)
        for (i in 0 until n) {
            val s = physical.samples[i]
            ts[i] = s.timestampMs.toLong()
            val o = i * 3
            accel[o] = s.accelG[0]; accel[o + 1] = s.accelG[1]; accel[o + 2] = s.accelG[2]
            gyro[o] = s.gyroDps[0]; gyro[o + 1] = s.gyroDps[1]; gyro[o + 2] = s.gyroDps[2]
        }
        if (physical.sequenceStart < 40u) {
            android.util.Log.i("OpenZiloImuDiag", "0x0605 batch seq=${physical.sequenceStart} frameCount=${physical.frameCount}")
        }
        bundle.putLong("sequenceStart", physical.sequenceStart.toLong())
        bundle.putLongArray("timestampsMs", ts)
        bundle.putDoubleArray("accelG", accel)
        bundle.putDoubleArray("gyroDps", gyro)
        bundle.putStringArray("appliedComponents", physical.appliedComponents.toTypedArray())
    }

    private fun attachClientAttitude(bundle: Bundle, key: IBinder, physical: PhysicalImuBatch) {
        val estimator = attitudeEstimators.computeIfAbsent(key) {
            val snap = host.controller.state.value
            ImuAttitudeEstimator(defaultRingAxisMapping(snap.capabilities?.boardTarget), 1.6).also { est ->
                host.calibrations.loadMountAlignment(snap.info)?.let { m -> runCatching { est.setMountAlignment(m) } }
            }
        }
        val snapshots = runCatching { estimator.acceptBatch(physical) }.getOrNull() ?: return
        val latest = snapshots.lastOrNull() ?: return
        val trajQuats = DoubleArray(snapshots.size * 4)
        for (i in snapshots.indices) {
            val sq = snapshots[i].quaternion
            val o = i * 4
            trajQuats[o] = sq.w
            trajQuats[o + 1] = sq.x
            trajQuats[o + 2] = sq.y
            trajQuats[o + 3] = sq.z
        }
        val q = latest.quaternion
        bundle.putDoubleArray("trajectoryQuaternions", trajQuats)
        bundle.putDoubleArray("quaternion", doubleArrayOf(q.w, q.x, q.y, q.z))
        bundle.putDoubleArray("eulerDeg", doubleArrayOf(latest.euler.rollDeg, latest.euler.pitchDeg, latest.euler.yawDeg))
        bundle.putDoubleArray("gyroBodyDps", latest.gyroBodyDps.toDoubleArray())
        bundle.putDouble("tiltFromZenithDeg", latest.tiltFromZenithDeg)
        bundle.putString("posture", latest.posture.name)
        bundle.putBoolean("stationary", latest.stationary)
        runCatching { quaternionToOpenglMatrix(q).toFloatArray() }.getOrNull()?.let {
            bundle.putFloatArray("openGlMatrix", it)
        }
    }

    private fun enrichGestureBundle(bundle: Bundle, command: Int, body: ByteArray) {
        val parsed = runCatching { parseDeviceEvent(command.toUShort(), body) }.getOrNull() ?: return
        bundle.putLong("timestamp", parsed.timestamp.toLong())
        bundle.putString("timestampUnit", parsed.timestampUnit)
        parsed.gestureId?.let { id ->
            bundle.putInt("gestureId", id.toInt())
            bundle.putString("gestureName", sensorGestureName(id))
        }
    }

    private suspend fun acquireImuLease(principal: AppPrincipal, binder: IBinder, epoch: Long): ActiveImuState = imuMutex.withLock {
        check(epoch == host.controller.connectionEpoch) { "Ring connection changed" }
        clearStaleImuLeasesLocked(epoch)
        val existingState = activeImuState?.takeIf { it.epoch == epoch && imuLeases.isNotEmpty() }
        val state = if (existingState != null) {
            existingState
        } else {
            val packet = host.controller.request(DeviceRequest.StartImu, epoch)
            if (packet.body.size >= 2) {
                val code = ((packet.body[0].toInt() and 255) shl 8) or (packet.body[1].toInt() and 255)
                if (code != 0) throw DeviceOperationException(code, packet.command.toInt(), packet.body)
            }
            val cfg = parseImuConfig(packet.body)
            android.util.Log.i("OpenZiloImuDiag", "IMU started: rate=${cfg.sampleRateHz}Hz accel=±${cfg.accelRangeG}g gyro=±${cfg.gyroRangeDps}dps")
            ActiveImuState(epoch, packet.body, cfg).also { activeImuState = it }
        }
        if (!imuLeases.containsKey(binder)) {
            val death = IBinder.DeathRecipient { scope.launch { releaseImuLease(binder) } }
            binder.linkToDeath(death, 0)
            imuLeases[binder] = ImuLease(principal, binder, death, epoch)
            if (!binder.isBinderAlive) {
                imuLeases.remove(binder)?.death?.let { runCatching { binder.unlinkToDeath(it, 0) } }
                if (imuLeases.isEmpty()) stopHardwareImuLocked(epoch)
                throw IllegalStateException("IMU client disconnected")
            }
        }
        state
    }

    private suspend fun releaseImuLease(binder: IBinder) = imuMutex.withLock {
        val removed = imuLeases.remove(binder) ?: return@withLock
        removed.death?.let { d -> removed.binder?.let { b -> runCatching { b.unlinkToDeath(d, 0) } } }
        if (imuLeases.values.none { it.epoch == removed.epoch }) {
            stopHardwareImuLocked(removed.epoch)
        }
    }

    private suspend fun stopHardwareImuLocked(epoch: Long) {
        if (activeImuState?.epoch == epoch) {
            activeImuState = null
            if (epoch == host.controller.connectionEpoch) {
                runCatching { withTimeout(3_000) { host.controller.request(DeviceRequest.StopImu, epoch) } }
            }
        }
    }

    private fun clearStaleImuLeases(currentEpoch: Long) {
        scope.launch { imuMutex.withLock { clearStaleImuLeasesLocked(currentEpoch) } }
    }

    private fun clearStaleImuLeasesLocked(currentEpoch: Long) {
        imuLeases.entries.toList().filter { it.value.epoch != currentEpoch }.forEach { (key, lease) ->
            if (imuLeases.remove(key, lease)) {
                lease.death?.let { d -> lease.binder?.let { b -> runCatching { b.unlinkToDeath(d, 0) } } }
            }
        }
        if (activeImuState?.epoch != currentEpoch) activeImuState = null
    }

    private suspend fun perform(operation: String, args: Bundle, principal: AppPrincipal, callbackBinder: IBinder, requestId: String): Bundle {
        val epoch = host.controller.connectionEpoch
        if (operation == "system.info") {
            host.controller.refresh()
            return status(principal)
        }
        if (operation == "imu.start") {
            val state = acquireImuLease(principal, callbackBinder, epoch)
            return Bundle().apply {
                putInt("command", 0x0602)
                putByteArray("body", state.responseBody)
                putInt("sampleRateHz", state.config.sampleRateHz.toInt())
                putInt("accelRangeG", state.config.accelRangeG.toInt())
                putInt("gyroRangeDps", state.config.gyroRangeDps.toInt())
            }
        }
        if (operation == "imu.stop") {
            attitudeEstimators.remove(callbackBinder)?.close()
            releaseImuLease(callbackBinder)
            return Bundle().apply { putInt("command", 0x0604); putByteArray("body", byteArrayOf(0, 0)) }
        }
        if (operation == "imu.attitude.zero") {
            attitudeEstimators[callbackBinder]?.zeroHeading()
            return Bundle()
        }
        if (operation == "imu.attitude.mapping") {
            val preset = when (args.getString("preset")) {
                "STANDARD" -> ImuAxisPreset.STANDARD
                "SWAPPED_XY" -> ImuAxisPreset.SWAPPED_XY
                "Z_BORE" -> ImuAxisPreset.Z_BORE
                else -> throw IllegalArgumentException("Invalid axis preset")
            }
            val invert = args.boolean("invertRadialZ")
            val estimator = attitudeEstimators.computeIfAbsent(callbackBinder) {
                ImuAttitudeEstimator(ImuAxisMapping(preset, invert), 1.6)
            }
            estimator.setAxisMapping(ImuAxisMapping(preset, invert))
            return Bundle()
        }
        if (operation == "imu.calibration") {
            val info = host.controller.state.value.info
            return Bundle().apply {
                putStringArray("calibratedComponents", host.calibrations.calibratedComponents(info).toTypedArray())
            }
        }
        if (operation == "imu.calibrate.upright") {
            val info = checkNotNull(host.controller.state.value.info) { "Connect a ring first" }
            val estimator = checkNotNull(attitudeEstimators[callbackBinder]) { "Start IMU stream before alignment" }
            val mount = estimator.calibrateWornButtonUp()
            host.calibrations.saveMountAlignment(info, mount)
            attitudeEstimators.forEach { (otherKey, otherEst) ->
                if (otherKey != callbackBinder) runCatching { otherEst.setMountAlignment(mount) }
            }
            broadcastStatus()
            return Bundle().apply {
                putStringArray("calibratedComponents", host.calibrations.calibratedComponents(info).toTypedArray())
            }
        }
        if (operation == "imu.calibration.reset") {
            val info = checkNotNull(host.controller.state.value.info) { "Connect a ring first" }
            host.calibrations.resetCalibration(info)
            attitudeEstimators.values.forEach { est ->
                est.clearMountAlignment()
                est.clearGyroBias()
            }
            broadcastStatus()
            return Bundle().apply { putStringArray("calibratedComponents", emptyArray()) }
        }
        if (operation == "imu.calibrate.gyro") {
            val durationMs = args.uint("durationMs", 30_000).coerceIn(2_000u, 30_000u)
            val alignFlat = if (args.containsKey("alignFlat")) args.boolean("alignFlat") else true
            val tempBinder = Binder()
            val imuState = acquireImuLease(principal, tempBinder, epoch)
            try {
                val targetSamples = ((durationMs.toLong() * imuState.config.sampleRateHz.toLong()) / 1000L)
                    .toInt()
                    .coerceIn(140, 3000)
                val rawSamples = ArrayList<ImuSample>(targetSamples + 64)
                host.controller.events.first { ev ->
                    if (ev.epoch != epoch || ev.packet.command.toInt() != 0x0605) return@first false
                    val parsed = runCatching { parseImu(ev.packet.body) }.getOrNull() ?: return@first false
                    rawSamples.addAll(parsed.samples)
                    rawSamples.size >= targetSamples
                }
                val window = try {
                    analyzeDeskImuWindow(rawSamples, imuState.config)
                } catch (calErr: Exception) {
                    android.util.Log.w("OpenZiloImuDiag", "Desk calibration rejected (${rawSamples.size} samples): ${calErr.message}")
                    throw calErr
                }
                android.util.Log.i(
                    "OpenZiloImuDiag",
                    "Desk calibration OK: samples=${window.samples} accelStd=${window.accelStdG}g gyroStd=${window.gyroStdDps}dps bias=${window.gyroDps}",
                )
                val snapshot = host.controller.state.value
                val info = checkNotNull(snapshot.info) { "Ring disconnected during calibration" }
                host.calibrations.saveGyroBiasWindow(info, imuState.config, snapshot.capabilities?.features.orEmpty(), window)
                if (alignFlat) {
                    val callerEstimator = attitudeEstimators.computeIfAbsent(callbackBinder) {
                        ImuAttitudeEstimator(defaultRingAxisMapping(snapshot.capabilities?.boardTarget), 1.6)
                    }
                    val mount = callerEstimator.calibrateFlatTableMount(window.accelG)
                    host.calibrations.saveMountAlignment(info, mount)
                    attitudeEstimators.forEach { (otherKey, otherEst) ->
                        if (otherKey != callbackBinder) runCatching { otherEst.setMountAlignment(mount) }
                    }
                }
                attitudeEstimators.values.forEach { it.clearGyroBias() }
                broadcastStatus()
                return Bundle().apply {
                    putStringArray("calibratedComponents", host.calibrations.calibratedComponents(info).toTypedArray())
                    host.calibrations.gyroBiasDps(info)?.let { putDoubleArray("gyroBiasDps", it) }
                }
            } finally {
                releaseImuLease(tempBinder)
            }
        }
        if (operation == "firmware.upgrade") {
            val descriptor = args.getParcelableCompat("firmware") as? ParcelFileDescriptor
                ?: throw IllegalArgumentException("firmware must be a read-only ParcelFileDescriptor")
            val stat = android.system.Os.fstat(descriptor.fileDescriptor)
            require(android.system.OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1L..600L * 1024) {
                "Firmware must be a bounded regular file"
            }
            val bytes = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 600 * 1024) { "Firmware exceeds 600 KiB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            val owner = claimControl("firmware", principal, "firmware", callbackBinder, requestId, epoch)
            try { host.controller.upgrade(bytes, FIRMWARE_PUBLIC_KEY) }
            finally { dropControl("firmware", owner) }
            return status(principal)
        }
        val request = when (operation) {
            "capabilities" -> DeviceRequest.Capabilities
            "connection.rssi" -> DeviceRequest.ConnectionRssi
            "audio.count" -> DeviceRequest.AudioCount
            "audio.info" -> DeviceRequest.AudioInfo(args.uint("fileIndex"))
            "audio.quick" -> DeviceRequest.AudioQuick(args.uint("fileIndex"))
            "audio.read" -> DeviceRequest.AudioRead(args.uint("fileIndex"), args.uint("offset"))
            "audio.end" -> DeviceRequest.AudioEnd(args.uint("fileIndex"))
            "audio.clear" -> DeviceRequest.ClearAudio(args.boolean("confirmed").also { require(it) { "Deleting recordings requires confirmed=true" } })
            "live.subscribe" -> DeviceRequest.LiveSubscribe(args.boolean("enabled"))
            "recording.config" -> DeviceRequest.GetRecordingConfig
            "recording.configure" -> DeviceRequest.SetRecordingConfig(RecordingConfig(
                output = args.uint("output", 3).toUByte(),
                disconnectPolicy = args.uint("disconnectPolicy", 1).toUByte(),
                channels = args.uint("channels", 2).toUByte(),
                bitrate = args.uint("bitrate"),
                gainLeftQ8 = args.uint("gainLeftQ8", 1024).toUShort(),
                gainRightQ8 = args.uint("gainRightQ8", 1024).toUShort(),
                maxDurationMs = args.uint("maxDurationMs", 60000).toUShort(),
            ))
            "recording.start" -> DeviceRequest.StartRecording
            "recording.stop" -> DeviceRequest.StopRecording(args.uint("sessionId").also { require(it != 0u) }, args.boolean("cancel"))
            "recording.status" -> DeviceRequest.RecordingStatus
            "imu.start" -> DeviceRequest.StartImu
            "imu.stop" -> DeviceRequest.StopImu
            "logs.storage" -> DeviceRequest.LogStorage
            "logs.read" -> DeviceRequest.ReadLog(args.uint("fileIndex"), args.uint("offset"), args.uint("length", 4096))
            "time.reply" -> DeviceRequest.TimeReply(args.uint("requestTime"), args.uint("responseTime"), args.uint("sendTime"))
            else -> throw IllegalArgumentException("Unknown operation")
        }
        val resource = when (operation) {
            "recording.start", "recording.stop", "recording.configure" -> "recording"
            "live.subscribe" -> "live"
            "imu.start", "imu.stop" -> "imu"
            "audio.info", "audio.quick", "audio.read", "audio.end", "audio.clear" -> "audio"
            else -> null
        }
        resource?.let { checkControlOwner(it, principal) }
        if (operation == "audio.clear") check(controls["audio"] == null) { "End extraction before clearing recordings" }
        if (resource == "audio" && operation != "audio.clear") controls["audio"]?.fileIndex?.let {
            require(it == args.uint("fileIndex")) { "End the current extraction before selecting another file" }
        }
        val startsControl = operation in setOf("recording.start", "imu.start", "audio.info", "audio.quick", "audio.read") ||
            (operation == "live.subscribe" && args.boolean("enabled"))
        val owner = if (startsControl && resource != null) claimControl(resource, principal, GrantPolicy.operationScope(operation), callbackBinder, requestId, epoch,
            if (resource == "audio") args.uint("fileIndex") else null) else resource?.let { controls[it] }
        val packet = try { host.controller.request(request, epoch) }
        catch (failure: Exception) {
            if (startsControl && resource != null) loseControl(resource, owner)
            throw failure
        }
        if (packet.body.size >= 2) {
            val code = ((packet.body[0].toInt() and 255) shl 8) or (packet.body[1].toInt() and 255)
            if (code != 0) {
                if (startsControl && resource != null) loseControl(resource, owner)
                throw DeviceOperationException(code, packet.command.toInt(), packet.body)
            }
        }
        if (operation in setOf("recording.stop", "imu.stop", "audio.end") || (operation == "live.subscribe" && !args.boolean("enabled"))) {
            resource?.let { dropControl(it, owner) }
        }
        require(packet.body.size <= 5120) { "Response exceeds API limit" }
        return Bundle().apply { putInt("command", packet.command.toInt()); putByteArray("body", packet.body) }
    }

    private val localizedContext: Context
        get() {
            val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return this
            val configuration = android.content.res.Configuration(resources.configuration)
            configuration.setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
            return createConfigurationContext(configuration)
        }

    private fun ownsEvent(topic: String, principal: AppPrincipal): Boolean {
        val resource = when (topic) { "audio" -> "audio"; "live" -> "live"; else -> return true }
        val owner = controls[resource] ?: return false
        return owner.epoch == host.controller.connectionEpoch && owner.principal.uid == principal.uid &&
            owner.principal.certificate == principal.certificate
    }

    private fun checkControlOwner(resource: String, principal: AppPrincipal) {
        val owner = controls[resource] ?: return
        check(owner.principal.uid == principal.uid && owner.principal.certificate == principal.certificate && !releasing.containsKey(resource)) {
            "Another application owns this device operation"
        }
    }

    private fun claimControl(resource: String, principal: AppPrincipal, permission: String, binder: IBinder, requestId: String, epoch: Long, fileIndex: UInt? = null): ControlOwner =
        synchronized(controls) {
            check(epoch == host.controller.connectionEpoch) { "Ring connection changed" }
            controls[resource]?.takeIf { it.epoch != epoch && resource != "firmware" }?.let { dropControl(resource, it) }
            checkControlOwner(resource, principal)
            controls[resource]?.let { return@synchronized it }
            lateinit var owner: ControlOwner
            val death = IBinder.DeathRecipient { loseControl(resource, owner) }
            owner = ControlOwner(principal, permission, requestId, binder, death, epoch, fileIndex)
            binder.linkToDeath(death, 0)
            controls[resource] = owner
            if (!binder.isBinderAlive) {
                loseControl(resource, owner)
                throw IllegalStateException("Control client disconnected")
            }
            owner
        }

    private fun dropControl(resource: String, owner: ControlOwner?) {
        if (owner == null || !controls.remove(resource, owner)) return
        releasing.remove(resource, owner)
        runCatching { owner.binder.unlinkToDeath(owner.death, 0) }
    }

    private fun loseControl(resource: String, owner: ControlOwner?) {
        if (owner == null || controls[resource] !== owner || releasing.putIfAbsent(resource, owner) != null) return
        scope.launch {
            try {
                val stopped = runCatching {
                    withTimeout(3000) {
                        val packet = when (resource) {
                            "imu" -> host.controller.request(DeviceRequest.StopImu, owner.epoch)
                            "live" -> host.controller.request(DeviceRequest.LiveSubscribe(false), owner.epoch)
                            "audio" -> host.controller.request(DeviceRequest.AudioEnd(owner.fileIndex ?: error("Missing extraction identity")), owner.epoch)
                            "recording" -> {
                                val status = com.zilo.libopenzilo.parseRecordingStatus(host.controller.request(DeviceRequest.RecordingStatus, owner.epoch).body)
                                if (status.sessionId == 0u || status.state.toInt() !in 1..3) return@withTimeout
                                host.controller.request(DeviceRequest.StopRecording(status.sessionId, true), owner.epoch)
                            }
                            else -> error("Operation outcome requires reconnect")
                        }
                        check(packet.body.size >= 2 && packet.body[0] == 0.toByte() && packet.body[1] == 0.toByte())
                    }
                }.isSuccess
                if (!stopped) {
                    runCatching { host.controller.disconnect(owner.epoch) }
                    controls.entries.toList().filter { it.value.epoch == owner.epoch && it.key != "firmware" }
                        .forEach { (name, entry) -> dropControl(name, entry) }
                }
            } finally {
                dropControl(resource, owner)
                releasing.remove(resource, owner)
            }
        }
    }

    private var connectionTask: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.getStringExtra(CONTROL_TOKEN) != controlSecret) {
            if (!started) stopSelf(startId)
            return if (started) START_STICKY else START_NOT_STICKY
        }
        val binding = host.binding.value
        when (intent?.action) {
            ACTION_DISCONNECT, ACTION_FORGET -> {
                host.saveBinding(if (intent.action == ACTION_FORGET) null else binding?.copy(autoConnect = false))
                replaceConnectionTask {
                    host.controller.disconnect()
                    stopTracking()
                }
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val address = intent.getStringExtra("address") ?: return START_NOT_STICKY
                if (!startTracking()) return START_NOT_STICKY
                replaceConnectionTask {
                    try {
                        host.controller.connect(address)
                        val connected = host.controller.state.value
                        val info = checkNotNull(connected.info)
                        val saved = RingBinding(address, connected.deviceName, ringIdentity(info, address), autoConnect = true)
                        host.saveBinding(saved)
                        maintainConnection(saved)
                    } catch (error: CancellationException) {
                        if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                        stopTracking()
                    } catch (_: Exception) {
                        stopTracking()
                    }
                }
            }
            ACTION_RESUME, null -> {
                if (binding == null || (intent == null && !binding.autoConnect)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (started && connectionTask?.isActive == true) return START_STICKY
                if (!startTracking()) return START_NOT_STICKY
                val activeBinding = binding.copy(autoConnect = true)
                host.saveBinding(activeBinding)
                replaceConnectionTask { maintainConnection(activeBinding) }
            }
            else -> return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun replaceConnectionTask(block: suspend () -> Unit) {
        val previous = connectionTask
        previous?.cancel()
        connectionTask = scope.launch(Dispatchers.Main) {
            previous?.cancelAndJoin()
            block()
        }
    }

    private fun startTracking(): Boolean = try {
        startForeground(NOTIFICATION, notification())
        started = true
        true
    } catch (_: SecurityException) {
        stopSelf()
        false
    }

    private fun stopTracking() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        started = false
        stopSelf()
    }

    private suspend fun maintainConnection(binding: RingBinding) {
        var retryDelay = 1_000L
        while (scope.isActive) {
            if (host.controller.state.value.phase != "disconnected") {
                host.controller.state.first { it.phase == "disconnected" }
                delay(1_000)
            }
            host.upgrading.first { !it }
            try {
                host.controller.connect(binding.address, binding.identity, binding.name)
                retryDelay = 1_000
            } catch (error: CancellationException) {
                if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(60_000)
            } catch (_: RingIdentityMismatch) {
                host.saveBinding(binding.copy(autoConnect = false))
                stopTracking()
                return
            } catch (_: SecurityException) {
                stopTracking()
                return
            } catch (_: Exception) {
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(60_000)
            }
        }
    }

    private fun notification(): Notification {
        val disconnect = PendingIntent.getService(this, 0, disconnectIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val launch = PendingIntent.getActivity(this, 1, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(localizedContext.getString(R.string.auth_notification_title))
            .setContentText(localizedContext.getString(R.string.auth_notification_body))
            .setContentIntent(launch).setOngoing(true)
            .addAction(Notification.Action.Builder(null, localizedContext.getString(R.string.auth_disconnect), disconnect).build()).build()
    }

    override fun onDestroy() {
        val epoch = host.controller.connectionEpoch
        subscriptions.keys.toList().forEach(::removeSubscription)
        imuLeases.entries.toList().forEach { (_, lease) ->
            lease.death?.let { d -> lease.binder?.let { b -> runCatching { b.unlinkToDeath(d, 0) } } }
        }
        imuLeases.clear()
        attitudeEstimators.values.forEach { runCatching { it.close() } }
        attitudeEstimators.clear()
        activeImuState = null
        scope.cancel()
        controls.entries.toList().forEach { (resource, owner) -> dropControl(resource, owner) }
        if (started) CoroutineScope(Dispatchers.IO).launch { runCatching { host.controller.disconnect(epoch) } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.openzilo.connect.CONNECT"
        const val ACTION_DISCONNECT = "com.openzilo.connect.DISCONNECT"
        private const val ACTION_RESUME = "com.openzilo.connect.RESUME"
        private const val ACTION_FORGET = "com.openzilo.connect.FORGET"
        private const val CONTROL_TOKEN = "internalControlToken"
        private val controlSecret = UUID.randomUUID().toString()
        private const val CHANNEL = "ring-connection"
        private const val NOTIFICATION = 100
        fun connectIntent(context: Context, address: String): Intent = Intent(context, ConnectService::class.java)
            .setAction(ACTION_CONNECT).putExtra("address", address).putExtra(CONTROL_TOKEN, controlSecret)
        fun disconnectIntent(context: Context): Intent = Intent(context, ConnectService::class.java)
            .setAction(ACTION_DISCONNECT).putExtra(CONTROL_TOKEN, controlSecret)
        fun resumeIntent(context: Context): Intent = Intent(context, ConnectService::class.java)
            .setAction(ACTION_RESUME).putExtra(CONTROL_TOKEN, controlSecret)
        fun forgetIntent(context: Context): Intent = Intent(context, ConnectService::class.java)
            .setAction(ACTION_FORGET).putExtra(CONTROL_TOKEN, controlSecret)
    }
}

private fun Bundle.uint(key: String, max: Long = 0xffff_ffffL): UInt {
    require(containsKey(key)) { "Missing $key" }
    val raw = get(key)
    val value = when (raw) { is Long -> raw; is Int -> raw.toLong(); else -> throw IllegalArgumentException("$key must be an integer") }
    require(value in 0..max) { "$key is outside its supported range" }
    return value.toUInt()
}
private fun Bundle.boolean(key: String): Boolean {
    require(containsKey(key)) { "Missing $key" }
    return get(key) as? Boolean ?: throw IllegalArgumentException("$key must be a boolean")
}
@Suppress("DEPRECATION")
private fun Bundle.getParcelableCompat(key: String): android.os.Parcelable? = getParcelable(key)
