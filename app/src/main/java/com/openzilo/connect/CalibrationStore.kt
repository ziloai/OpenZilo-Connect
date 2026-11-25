package com.openzilo.connect

import android.content.Context
import com.zilo.libopenzilo.CalibrationProfile
import com.zilo.libopenzilo.DeviceInfo
import com.zilo.libopenzilo.FeatureVersion
import com.zilo.libopenzilo.ImuBatch
import com.zilo.libopenzilo.ImuConfig
import com.zilo.libopenzilo.ImuStaticWindow
import com.zilo.libopenzilo.PhysicalImuBatch
import com.zilo.libopenzilo.ProfileBinding
import com.zilo.libopenzilo.Quaternion
import com.zilo.libopenzilo.applyImuCalibration
import com.zilo.libopenzilo.checkProfileDevice
import com.zilo.libopenzilo.fitImuGyroBias
import com.zilo.libopenzilo.identityImuCalibration
import com.zilo.libopenzilo.parseCalibrationProfile
import com.zilo.libopenzilo.rawImuToPhysical
import com.zilo.libopenzilo.serializeCalibrationProfile
import com.zilo.libopenzilo.stableDeviceKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Host-managed IMU calibration repository keyed by `stableDeviceKey(info)` so every
 * authorized client app receives consistent calibrated physical IMU streams (`g` and `deg/s`)
 * without exposing raw hardware serial numbers or CPU IDs.
 */
class CalibrationStore(context: Context) {
    private val preferences = context.getSharedPreferences("imu-calibrations", Context.MODE_PRIVATE)

    @Synchronized
    fun activeProfile(info: DeviceInfo, config: ImuConfig, features: List<FeatureVersion>): CalibrationProfile? {
        val key = runCatching { stableDeviceKey(info) }.getOrNull() ?: return null
        val json = preferences.getString(key, null) ?: return null
        return runCatching {
            val profile = parseCalibrationProfile(json)
            checkProfileDevice(profile, info, null, config, features)
            profile
        }.getOrNull()
    }

    @Synchronized
    fun calibratedComponents(info: DeviceInfo?): List<String> {
        if (info == null) return emptyList()
        val key = runCatching { stableDeviceKey(info) }.getOrNull() ?: return emptyList()
        val hasMount = preferences.contains("mount:$key")
        val hasDirectGyro = preferences.contains("gyro_bias:$key")
        val json = preferences.getString(key, null)
        val profile = json?.let { runCatching { parseCalibrationProfile(it) }.getOrNull() }
            ?.takeIf { it.model == info.model && it.firmwareVersion == info.firmwareVersion }
        return buildList {
            if (profile?.accelValidated == true) add("accel")
            if (profile?.gyroValidated == true || hasDirectGyro) add("gyro")
            if (hasMount) add("mount")
        }
    }

    @Synchronized
    fun gyroBiasDps(info: DeviceInfo?): DoubleArray? {
        if (info == null) return null
        val key = runCatching { stableDeviceKey(info) }.getOrNull() ?: return null
        loadDirectGyroBias(key)?.let { return it }
        val json = preferences.getString(key, null) ?: return null
        val profile = runCatching { parseCalibrationProfile(json) }.getOrNull() ?: return null
        if (!profile.gyroValidated || profile.model != info.model || profile.firmwareVersion != info.firmwareVersion) return null
        return DoubleArray(3) { i -> profile.calibration.gyroBiasUdps[i] / 1_000_000.0 }
    }

    private fun loadDirectGyroBias(key: String): DoubleArray? {
        val parts = preferences.getString("gyro_bias:$key", null)?.split(",") ?: return null
        if (parts.size != 3) return null
        return runCatching {
            doubleArrayOf(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
                .takeIf { arr -> arr.all { it.isFinite() && kotlin.math.abs(it) <= 60.0 } }
        }.getOrNull()
    }

    @Synchronized
    fun saveMountAlignment(info: DeviceInfo, mount: Quaternion) {
        val key = stableDeviceKey(info)
        val encoded = "${mount.w},${mount.x},${mount.y},${mount.z}"
        check(preferences.edit().putString("mount:$key", encoded).commit()) { "Could not persist mount alignment" }
    }

    @Synchronized
    fun loadMountAlignment(info: DeviceInfo?): Quaternion? {
        if (info == null) return null
        val key = runCatching { stableDeviceKey(info) }.getOrNull() ?: return null
        val parts = preferences.getString("mount:$key", null)?.split(",") ?: return null
        if (parts.size != 4) return null
        return runCatching {
            Quaternion(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble())
        }.getOrNull()
    }

    @Synchronized
    fun resetCalibration(info: DeviceInfo) {
        val key = stableDeviceKey(info)
        check(preferences.edit().remove(key).remove("mount:$key").remove("gyro_bias:$key").commit()) {
            "Could not reset calibration"
        }
    }

    @Synchronized
    fun applyOrConvert(
        batch: ImuBatch,
        info: DeviceInfo?,
        config: ImuConfig,
        features: List<FeatureVersion>,
    ): PhysicalImuBatch {
        val profile = if (info != null) activeProfile(info, config, features) else null
        if (profile != null) {
            return applyImuCalibration(
                batch,
                config,
                profile.calibration,
                profile.accelValidated,
                profile.gyroValidated,
            )
        }
        val rawPhysical = rawImuToPhysical(batch, config)
        val key = info?.let { runCatching { stableDeviceKey(it) }.getOrNull() }
        val directBias = key?.let { loadDirectGyroBias(it) } ?: return rawPhysical
        val adjustedSamples = rawPhysical.samples.map { s ->
            com.zilo.libopenzilo.PhysicalImuSample(
                timestampMs = s.timestampMs,
                accelG = s.accelG,
                gyroDps = listOf(
                    s.gyroDps[0] - directBias[0],
                    s.gyroDps[1] - directBias[1],
                    s.gyroDps[2] - directBias[2],
                ),
            )
        }
        return PhysicalImuBatch(
            sequenceStart = rawPhysical.sequenceStart,
            frameCount = rawPhysical.frameCount,
            samples = adjustedSamples,
            appliedComponents = (rawPhysical.appliedComponents + "gyro").distinct(),
        )
    }

    @Synchronized
    fun saveGyroBiasWindow(
        info: DeviceInfo,
        config: ImuConfig,
        features: List<FeatureVersion>,
        window: ImuStaticWindow,
    ) {
        val key = stableDeviceKey(info)
        val encodedBias = "${window.gyroDps[0]},${window.gyroDps[1]},${window.gyroDps[2]}"
        val editor = preferences.edit().putString("gyro_bias:$key", encodedBias)
        runCatching {
            val existing = activeProfile(info, config, features)
            val baseCal = existing?.calibration ?: identityImuCalibration(config)
            val updatedCal = fitImuGyroBias(baseCal, window)
            val nowRfc3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC))
            val profile = CalibrationProfile(
                binding = ProfileBinding.DEVICE_IDENTITY,
                deviceKey = key,
                firmwareVersion = info.firmwareVersion,
                model = info.model,
                calibration = updatedCal,
                accelValidated = existing?.accelValidated ?: false,
                gyroValidated = true,
                createdAt = nowRfc3339,
            )
            editor.putString(key, serializeCalibrationProfile(profile))
        }
        check(editor.commit()) { "Could not persist IMU gyro bias" }
    }
}
