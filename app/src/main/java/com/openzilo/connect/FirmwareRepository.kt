package com.openzilo.connect

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class FirmwareRelease(
    val id: Long,
    val version: String,
    val publishedAt: String,
    val notes: String,
    val downloadUrl: String,
    val size: Int,
) {
    fun releaseNotes(chinese: Boolean): String {
        val sections = notes.split(Regex("(?m)^\\s*---[ \\t]*\\r?$"), limit = 2)
        val selected = if (sections.size == 2) sections[if (chinese) 1 else 0] else notes
        fun plain(text: String) = text.lineSequence().map { line ->
            line.trim().replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^(?:[-+*]|[0-9]+[.)])\\s+"), "")
                .replace(Regex("\\s+#+$"), "")
        }.filterNot { it == "---" }.joinToString("\n").trim()
        return plain(selected).ifBlank { plain(notes) }
    }
}

internal class FirmwareRepository {
    suspend fun releases(): List<FirmwareRelease> = withContext(Dispatchers.IO) {
        parseReleases(request(API_URL, MAX_JSON_SIZE, null).toString(Charsets.UTF_8))
    }

    suspend fun download(release: FirmwareRelease): ByteArray = withContext(Dispatchers.IO) {
        require(release.size in 1..MAX_FIRMWARE_SIZE) { "Invalid firmware size" }
        val tag = "firmware-${release.version}"
        require(validVersion(tag) != null && validateDownloadUrl(release.downloadUrl, tag)) {
            "Invalid firmware download URL"
        }
        request(release.downloadUrl, MAX_FIRMWARE_SIZE, release.size)
    }

    private suspend fun request(initialUrl: String, limit: Int, expectedSize: Int?): ByteArray {
        val context = currentCoroutineContext()
        var url = URI(initialUrl)
        var redirects = 0
        val tag = if (expectedSize != null) url.path.split('/')[5] else null
        while (true) {
            context.ensureActive()
            val connection = url.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "OpenZiloConnect/0.1")
                connection.setRequestProperty("Accept", if (tag == null) "application/vnd.github+json" else "application/octet-stream")
                val status = connection.responseCode
                context.ensureActive()
                if (status in REDIRECT_CODES) {
                    // The API must never leave the single fixed official endpoint.
                    if (tag == null || redirects >= 5) throw IOException("Unexpected redirect")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Missing redirect location")
                    val next = try {
                        url.resolve(URI(location))
                    } catch (error: IllegalArgumentException) {
                        throw IOException("Invalid redirect", error)
                    } catch (error: java.net.URISyntaxException) {
                        throw IOException("Invalid redirect", error)
                    }
                    if (!validRedirect(next, tag)) throw IOException("Untrusted redirect")
                    redirects++
                    url = next
                    continue
                }
                if (status != HttpsURLConnection.HTTP_OK) throw IOException("HTTP $status")
                val declared = connection.getHeaderField("Content-Length")?.let {
                    it.toLongOrNull()?.takeIf { size -> size >= 0 }
                        ?: throw IOException("Invalid content length")
                }
                if (declared != null && (declared > limit ||
                        (expectedSize != null && declared != expectedSize.toLong()))) {
                    throw IOException("Unexpected response size")
                }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        context.ensureActive()
                        if (count < 0) break
                        if (count > limit - output.size()) throw IOException("Response exceeds size limit")
                        if (expectedSize != null && count > expectedSize - output.size()) {
                            throw IOException("Firmware exceeds declared asset size")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if ((expectedSize != null && bytes.size != expectedSize) ||
                    (declared != null && bytes.size.toLong() != declared)) {
                    throw IOException("Truncated response")
                }
                context.ensureActive()
                return bytes
            } catch (error: IOException) {
                context.ensureActive()
                throw error
            } finally {
                try {
                    connection.errorStream?.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    companion object {
        private const val API_URL = "https://api.github.com/repos/ziloai/OpenZilo-Hardware/releases?per_page=30"
        private const val MAX_JSON_SIZE = 2 * 1024 * 1024
        private const val MAX_FIRMWARE_SIZE = 614400
        private val TAG = Regex("^firmware-(V[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+-prod-[0-9a-fA-F]{8})$")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun parseReleases(json: String): List<FirmwareRelease> {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_SIZE) { "Release JSON too large" }
            val array = JSONArray(json)
            val releases = mutableListOf<FirmwareRelease>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.opt("draft") != false || item.opt("prerelease") != false) continue
                val tag = item.opt("tag_name") as? String ?: continue
                val version = validVersion(tag) ?: continue
                val assets = item.optJSONArray("assets") ?: continue
                val otaAssets = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .filter { (it.opt("name") as? String)?.endsWith(".ota.zip") == true }
                val asset = otaAssets.singleOrNull() ?: continue
                val size = integer(asset, "size")?.takeIf { it in 1..MAX_FIRMWARE_SIZE.toLong() } ?: continue
                val downloadUrl = asset.opt("browser_download_url") as? String ?: continue
                if (!validateDownloadUrl(downloadUrl, tag)) continue
                val id = integer(item, "id")?.takeIf { it > 0 } ?: continue
                val publishedAt = item.opt("published_at") as? String ?: continue
                releases += FirmwareRelease(
                    id, version, publishedAt, item.opt("body") as? String ?: "", downloadUrl, size.toInt(),
                )
            }
            return releases.sortedWith { left, right ->
                val a = versionParts(left.version)
                val b = versionParts(right.version)
                var comparison = 0
                for (index in 0..3) {
                    comparison = b[index].compareTo(a[index])
                    if (comparison != 0) break
                }
                if (comparison == 0) right.publishedAt.compareTo(left.publishedAt) else comparison
            }
        }

        fun validateDownloadUrl(url: String, tag: String): Boolean {
            if (validVersion(tag) == null) return false
            val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { return false }
            if (!safeHttps(uri) || uri.host != "github.com" || uri.rawQuery != null) return false
            val prefix = "/ziloai/OpenZilo-Hardware/releases/download/$tag/"
            val path = uri.rawPath ?: return false
            if (!path.startsWith(prefix)) return false
            val filename = path.removePrefix(prefix)
            return Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.ota\\.zip").matches(filename)
        }

        private fun safeHttps(uri: URI): Boolean =
            uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
                uri.port == -1 && uri.rawFragment == null && !uri.isOpaque &&
                uri.rawAuthority == uri.host

        private fun validRedirect(uri: URI, tag: String): Boolean {
            if (!safeHttps(uri)) return false
            return when (uri.host) {
                "github.com" -> validateDownloadUrl(uri.toASCIIString(), tag)
                // GitHub's short-lived signed CDN URLs necessarily contain query parameters.
                "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
                else -> false
            }
        }

        private fun integer(item: JSONObject, key: String): Long? =
            (item.opt(key) as? Number)?.toString()?.toLongOrNull()

        private fun validVersion(tag: String): String? {
            if (tag.length > "firmware-".length + 63) return null
            val version = TAG.matchEntire(tag)?.groupValues?.get(1) ?: return null
            val parts = version.substringAfter('V').substringBefore("-prod-").split('.')
            if (parts.any { it.toLongOrNull() == null }) return null
            return version
        }

        private fun versionParts(version: String): List<Long> =
            version.substringAfter('V').substringBefore("-prod-").split('.').map { it.toLong() }
    }
}
