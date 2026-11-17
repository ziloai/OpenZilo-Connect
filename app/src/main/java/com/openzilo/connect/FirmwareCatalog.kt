package com.openzilo.connect

import com.zilo.libopenzilo.FirmwareBundle
import com.zilo.libopenzilo.inspectFirmwareBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val FIRMWARE_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEeVBEk5+DbzTU0Q2uYVrDsHQ47fdU
Y30Te+RhhLDF7PXOXDem9y9l+mFQ8jBVB5F4Od67kT+r7ktOiVP3EGvLyA==
-----END PUBLIC KEY-----"""

internal fun firmwareVersionIsNewer(candidate: String, current: String): Boolean {
    fun parts(value: String): List<Long>? {
        val match = Regex("^V([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-(?:debug|prod)-[0-9a-fA-F]{8})?$").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
    }
    val next = parts(candidate) ?: return false
    val running = parts(current) ?: return false
    for (index in next.indices) {
        if (next[index] != running[index]) return next[index] > running[index]
    }
    return false
}

internal enum class FirmwareFailure { Check, Download, Verification }

internal data class PreparedFirmware(val bytes: ByteArray, val metadata: FirmwareBundle)

internal data class FirmwareCatalogState(
    val releases: List<FirmwareRelease> = emptyList(),
    val selected: FirmwareRelease? = null,
    val prepared: PreparedFirmware? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val verifying: Boolean = false,
    val failure: FirmwareFailure? = null,
)

internal class FirmwareCatalog(
    private val scope: CoroutineScope,
    private val fetchReleases: suspend () -> List<FirmwareRelease> = FirmwareRepository()::releases,
    private val fetchFirmware: suspend (FirmwareRelease) -> ByteArray = FirmwareRepository()::download,
) {
    private val mutableState = MutableStateFlow(FirmwareCatalogState())
    val state = mutableState.asStateFlow()
    private var checkingJob: Job? = null
    private var downloadJob: Job? = null
    private var checkedAt: Long? = null

    fun load() {
        val checked = checkedAt
        if (checked == null || System.nanoTime() - checked > 900_000_000_000L) refresh()
    }

    fun refresh() {
        if (checkingJob?.isActive == true) return
        downloadJob?.cancel()
        mutableState.value = mutableState.value.copy(checking = true, downloading = false, verifying = false, failure = null)
        checkingJob = scope.launch {
            try {
                val releases = fetchReleases()
                val previous = mutableState.value
                val selected = releases.firstOrNull { it.id == previous.selected?.id } ?: releases.firstOrNull()
                mutableState.value = FirmwareCatalogState(
                    releases = releases,
                    selected = selected,
                    prepared = previous.prepared.takeIf { previous.selected == selected },
                )
                checkedAt = System.nanoTime()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(checking = false, failure = FirmwareFailure.Check)
            }
        }
    }

    fun select(release: FirmwareRelease) {
        if (mutableState.value.checking || release !in mutableState.value.releases || mutableState.value.selected == release) return
        downloadJob?.cancel()
        mutableState.value = mutableState.value.copy(selected = release, prepared = null, downloading = false, verifying = false, failure = null)
    }

    fun download() {
        val current = mutableState.value
        val release = current.selected ?: return
        if (current.checking || current.downloading || current.prepared != null) return
        mutableState.value = current.copy(downloading = true, verifying = false, failure = null)
        downloadJob = scope.launch {
            val bytes = try {
                fetchFirmware(release)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(downloading = false, failure = FirmwareFailure.Download)
                return@launch
            }
            try {
                mutableState.value = mutableState.value.copy(verifying = true)
                val metadata = withContext(Dispatchers.Default) {
                    inspectFirmwareBundle(bytes, FIRMWARE_PUBLIC_KEY).also {
                        require(it.profile == "production" && it.version == release.version)
                    }
                }
                mutableState.value = mutableState.value.copy(prepared = PreparedFirmware(bytes, metadata), downloading = false, verifying = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(downloading = false, verifying = false, failure = FirmwareFailure.Verification)
            }
        }
    }

    fun retry() {
        val current = mutableState.value
        if (current.failure == FirmwareFailure.Check || current.selected == null) refresh()
        else download()
    }
}
