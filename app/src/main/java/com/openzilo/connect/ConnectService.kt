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
import com.openzilo.connect.api.IConnectCallback
import com.openzilo.connect.api.IConnectService
import com.zilo.libopenzilo.DeviceRequest
import com.zilo.libopenzilo.RecordingConfig
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ConnectService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subscriptions = ConcurrentHashMap<IBinder, Subscription>()
    private val active = ConcurrentHashMap<Int, Int>()
    private val operations = ConcurrentHashMap<String, Operation>()
    private val controls = ConcurrentHashMap<String, ControlOwner>()
    private val releasing = ConcurrentHashMap<String, ControlOwner>()
    private val host get() = application as ConnectApplication

    private class DeviceOperationException(val code: Int, val command: Int, val body: ByteArray) : IllegalStateException("Device rejected operation")
    private data class Operation(val principal: AppPrincipal, val permission: String, val requestId: String, val callback: IBinder, val job: Job)
    private data class ControlOwner(val principal: AppPrincipal, val permission: String, val requestId: String, val binder: IBinder, val death: IBinder.DeathRecipient, val epoch: Long, val fileIndex: UInt? = null)
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
            host.controller.events.collect { packet ->
                val topic = GrantPolicy.topic(packet.command.toInt()) ?: return@collect
                if (packet.body.size > 5120) return@collect
                val event = Bundle().apply {
                    putInt("apiVersion", 1)
                    putInt("command", packet.command.toInt())
                    putByteArray("body", packet.body)
                }
                subscriptions.values.toList().forEach { subscription ->
                    if (topic in subscription.topics && ownsEvent(topic, subscription.principal)) {
                        try {
                            host.grants.requireAccess(subscription.principal, GrantPolicy.topicScope(topic))
                            if (subscription.queue.trySend(topic to event).isFailure) removeSubscription(subscription.callback.asBinder())
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
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

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
            return status()
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
                            putInt("apiVersion", 1)
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
                        putInt("apiVersion", 1)
                        putString("error", when (failure) {
                            is SecurityException -> "permission_denied"
                            is DeviceOperationException -> "device_rejected"
                            is IllegalArgumentException -> "invalid_arguments"
                            else -> "operation_failed"
                        })
                        putString("message", localizedContext.getString(R.string.auth_operation_failed))
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
            for (resource in listOf("imu", "live")) {
                val owner = controls[resource] ?: continue
                val topic = if (resource == "imu") "imu" else "live"
                if (owner.principal.uid == item.principal.uid && subscriptions.values.none { it.principal.uid == owner.principal.uid && topic in it.topics }) {
                    loseControl(resource, owner)
                }
            }
        }
    }

    private fun status(): Bundle = Bundle().apply {
        val value = host.controller.state.value
        putInt("apiVersion", 1)
        putString("phase", value.phase)
        putString("deviceName", value.deviceName)
        putString("message", value.message)
        value.firmwareProgress?.let { putInt("firmwareProgress", it) }
        value.info?.let {
            putString("model", it.model)
            putString("firmwareVersion", it.firmwareVersion)
            putInt("batteryPercent", it.batteryPercent.toInt())
            putBoolean("charging", it.batteryCharging)
        }
    }

    private suspend fun perform(operation: String, args: Bundle, principal: AppPrincipal, callbackBinder: IBinder, requestId: String): Bundle {
        val epoch = host.controller.connectionEpoch
        if (operation == "system.info") {
            host.controller.refresh()
            return status()
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
            return status()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra(CONTROL_TOKEN) != controlSecret) return START_NOT_STICKY
        when (intent.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra("address") ?: return START_NOT_STICKY
                startForeground(NOTIFICATION, notification())
                started = true
                scope.launch { runCatching { host.controller.connect(address) } }
            }
            ACTION_DISCONNECT -> scope.launch {
                runCatching { host.controller.disconnect() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                started = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
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
        scope.cancel()
        controls.entries.toList().forEach { (resource, owner) -> dropControl(resource, owner) }
        if (started) CoroutineScope(Dispatchers.IO).launch { runCatching { host.controller.disconnect(epoch) } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.openzilo.connect.CONNECT"
        const val ACTION_DISCONNECT = "com.openzilo.connect.DISCONNECT"
        private const val CONTROL_TOKEN = "internalControlToken"
        private val controlSecret = UUID.randomUUID().toString()
        private const val CHANNEL = "ring-connection"
        private const val NOTIFICATION = 100
        fun connectIntent(context: Context, address: String): Intent = Intent(context, ConnectService::class.java)
            .setAction(ACTION_CONNECT).putExtra("address", address).putExtra(CONTROL_TOKEN, controlSecret)
        fun disconnectIntent(context: Context): Intent = Intent(context, ConnectService::class.java)
            .setAction(ACTION_DISCONNECT).putExtra(CONTROL_TOKEN, controlSecret)
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
