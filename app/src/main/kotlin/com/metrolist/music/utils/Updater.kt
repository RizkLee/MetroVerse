/*
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.metrolist.music.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val GITHUB_API_BASE = "https://api.github.com/repos/RizkLee/MetroVerse"
private const val GITHUB_REPOSITORY = "https://github.com/RizkLee/MetroVerse"
private const val CHECK_INTERVAL_MILLIS = 2 * 60 * 60 * 1000L
private const val MAX_APK_SIZE_BYTES = 100L * 1024L * 1024L
private const val MAX_CHECKSUM_SIZE_BYTES = 128L * 1024L
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private val RELEASE_APK_PATTERN = Regex("^MetroVerse-(v.+)-(foss|gms)\\.apk$")

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val description: String,
    val releaseDate: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
    val checksumDownloadUrl: String?,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val variant: String,
)

internal data class ParsedReleaseAssetName(
    val tagName: String,
    val variant: String,
)

internal fun parseReleaseAssetName(name: String): ParsedReleaseAssetName? {
    val match = RELEASE_APK_PATTERN.matchEntire(name) ?: return null
    return ParsedReleaseAssetName(
        tagName = match.groupValues[1],
        variant = match.groupValues[2],
    )
}

internal fun isPublishedStableRelease(
    draft: Boolean,
    prerelease: Boolean,
): Boolean = !draft && !prerelease

internal fun parseSha256Checksum(
    checksumText: String,
    fileName: String,
): String? =
    checksumText
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val listedName = parts[1].removePrefix("*").trim()
            parts[0].lowercase().takeIf { listedName == fileName && it.matches(Regex("[0-9a-f]{64}")) }
        }.firstOrNull()

private data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String?,
)

private fun parseSemanticVersion(value: String): SemanticVersion? {
    val match = Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?").find(value) ?: return null
    return SemanticVersion(
        major = match.groupValues[1].toIntOrNull() ?: return null,
        minor = match.groupValues[2].toIntOrNull() ?: 0,
        patch = match.groupValues[3].toIntOrNull() ?: 0,
        prerelease = match.groupValues[4].ifBlank { null },
    )
}

private fun comparePrerelease(
    first: String?,
    second: String?,
): Int {
    if (first == null && second == null) return 0
    if (first == null) return 1
    if (second == null) return -1

    val firstParts = first.split('.', '-')
    val secondParts = second.split('.', '-')
    val maxLength = maxOf(firstParts.size, secondParts.size)
    for (index in 0 until maxLength) {
        val firstPart = firstParts.getOrNull(index) ?: return -1
        val secondPart = secondParts.getOrNull(index) ?: return 1
        val firstNumber = firstPart.toIntOrNull()
        val secondNumber = secondPart.toIntOrNull()
        val comparison =
            when {
                firstNumber != null && secondNumber != null -> firstNumber.compareTo(secondNumber)
                firstNumber != null -> -1
                secondNumber != null -> 1
                else -> firstPart.compareTo(secondPart, ignoreCase = true)
            }
        if (comparison != 0) return comparison
    }
    return 0
}

object Updater {
    private val client = HttpClient()

    private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    var lastCheckTime = -1L
        private set

    private var cachedReleaseInfo: ReleaseInfo? = null
    private var cachedAllReleases: List<ReleaseInfo> = emptyList()

    val isSupportedBuild: Boolean
        get() = BuildConfig.UPDATER_AVAILABLE && !BuildConfig.DEBUG

    fun compareVersions(
        first: String,
        second: String,
    ): Int {
        val firstVersion = parseSemanticVersion(first)
        val secondVersion = parseSemanticVersion(second)
        if (firstVersion == null || secondVersion == null) {
            return first.compareTo(second, ignoreCase = true)
        }

        firstVersion.major.compareTo(secondVersion.major).takeIf { it != 0 }?.let { return it }
        firstVersion.minor.compareTo(secondVersion.minor).takeIf { it != 0 }?.let { return it }
        firstVersion.patch.compareTo(secondVersion.patch).takeIf { it != 0 }?.let { return it }
        return comparePrerelease(firstVersion.prerelease, secondVersion.prerelease)
    }

    fun isUpdateAvailable(
        currentVersion: String,
        latestVersion: String,
    ): Boolean = compareVersions(latestVersion, currentVersion) > 0

    private fun currentVariant(): String = BuildConfig.DISTRIBUTION

    private fun parseRelease(releaseObject: JSONObject): ReleaseInfo {
        val assetsArray = releaseObject.getJSONArray("assets")
        val assets = mutableListOf<ReleaseAsset>()
        var checksumDownloadUrl: String? = null

        for (index in 0 until assetsArray.length()) {
            val assetObject = assetsArray.getJSONObject(index)
            val name = assetObject.getString("name")
            val downloadUrl = assetObject.getString("browser_download_url")
            if (name.endsWith("-SHA256SUMS.txt")) {
                checksumDownloadUrl = downloadUrl
                continue
            }

            val parsedName = parseReleaseAssetName(name) ?: continue
            assets +=
                ReleaseAsset(
                    name = name,
                    downloadUrl = downloadUrl,
                    size = assetObject.getLong("size"),
                    variant = parsedName.variant,
                )
        }

        val tagName = releaseObject.getString("tag_name")
        return ReleaseInfo(
            tagName = tagName,
            versionName = tagName.removePrefix("v"),
            description = releaseObject.optString("body"),
            releaseDate = releaseObject.optString("published_at"),
            htmlUrl = releaseObject.optString("html_url", "$GITHUB_REPOSITORY/releases/tag/$tagName"),
            prerelease = releaseObject.optBoolean("prerelease"),
            assets = assets,
            checksumDownloadUrl = checksumDownloadUrl,
        )
    }

    suspend fun getAllReleases(forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
        withContext(Dispatchers.IO) {
            cancellableResult {
                if (cachedAllReleases.isNotEmpty() && !forceRefresh) {
                    return@cancellableResult cachedAllReleases
                }

                val response =
                    client.get("$GITHUB_API_BASE/releases?per_page=30") {
                        header(HttpHeaders.Accept, "application/vnd.github+json")
                        header(HttpHeaders.UserAgent, "MetroVerse/${BuildConfig.VERSION_NAME}")
                    }
                check(response.status.isSuccess()) { "GitHub returned HTTP ${response.status.value}" }

                val releaseArray = JSONArray(response.bodyAsText())
                val releases =
                    buildList {
                        for (index in 0 until releaseArray.length()) {
                            val releaseObject = releaseArray.getJSONObject(index)
                            if (!releaseObject.optBoolean("draft")) {
                                add(parseRelease(releaseObject))
                            }
                        }
                    }
                cachedAllReleases = releases
                releases
            }
        }

    suspend fun getLatestRelease(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            cancellableResult {
                if (cachedReleaseInfo != null && !forceRefresh) {
                    return@cancellableResult requireNotNull(cachedReleaseInfo)
                }

                val response =
                    client.get("$GITHUB_API_BASE/releases/latest") {
                        header(HttpHeaders.Accept, "application/vnd.github+json")
                        header(HttpHeaders.UserAgent, "MetroVerse/${BuildConfig.VERSION_NAME}")
                    }
                check(response.status.isSuccess()) { "GitHub returned HTTP ${response.status.value}" }

                val releaseObject = JSONObject(response.bodyAsText())
                check(
                    isPublishedStableRelease(
                        draft = releaseObject.optBoolean("draft"),
                        prerelease = releaseObject.optBoolean("prerelease"),
                    ),
                ) { "GitHub Latest is not a published stable release" }

                val latest = parseRelease(releaseObject)
                check(latest.assets.any { it.variant == currentVariant() }) {
                    "GitHub Latest has no compatible MetroVerse APK"
                }

                cachedReleaseInfo = latest
                lastCheckTime = System.currentTimeMillis()
                latest
            }
        }

    fun getAssetForCurrentVariant(releaseInfo: ReleaseInfo): ReleaseAsset? =
        releaseInfo.assets.firstOrNull { it.variant == currentVariant() }

    fun getDownloadUrlForCurrentVariant(releaseInfo: ReleaseInfo): String? =
        getAssetForCurrentVariant(releaseInfo)?.downloadUrl

    fun getAllDownloadUrls(releaseInfo: ReleaseInfo): Map<String, String> =
        releaseInfo.assets.associate { it.variant to it.downloadUrl }

    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo?, Boolean>> =
        withContext(Dispatchers.IO) {
            cancellableResult {
                check(isSupportedBuild) { "Automatic updates are disabled for this build" }
                val shouldFetch =
                    forceRefresh ||
                        cachedReleaseInfo == null ||
                        System.currentTimeMillis() - lastCheckTime > CHECK_INTERVAL_MILLIS
                val releaseInfo =
                    if (shouldFetch) {
                        getLatestRelease(forceRefresh = true).getOrThrow()
                    } else {
                        requireNotNull(cachedReleaseInfo)
                    }
                releaseInfo to isUpdateAvailable(BuildConfig.VERSION_NAME, releaseInfo.tagName)
            }
        }

    fun getLatestDownloadUrl(): String? =
        cachedReleaseInfo?.let(::getDownloadUrlForCurrentVariant)

    fun getCachedLatestRelease(): ReleaseInfo? = cachedReleaseInfo

    suspend fun downloadAndVerifyUpdate(
        context: Context,
        releaseInfo: ReleaseInfo,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            var partialFile: File? = null
            try {
                check(isSupportedBuild) { "Updates cannot be installed from this build" }
                val asset = getAssetForCurrentVariant(releaseInfo) ?: error("No matching ${currentVariant()} APK was found")
                check(asset.downloadUrl.startsWith("https://github.com/")) { "Unexpected update download host" }
                check(asset.size in 1..MAX_APK_SIZE_BYTES) { "The update file size is invalid" }

                val checksumUrl = releaseInfo.checksumDownloadUrl ?: error("The release has no SHA-256 checksum file")
                check(checksumUrl.startsWith("https://github.com/")) { "Unexpected checksum download host" }
                val checksumText = readHttpsText(checksumUrl)
                val expectedChecksum =
                    parseSha256Checksum(checksumText, asset.name)
                        ?: error("The release checksum does not contain ${asset.name}")

                val updatesDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
                updatesDirectory.listFiles()?.forEach(File::delete)
                val downloadingFile = File(updatesDirectory, "${asset.name}.part")
                partialFile = downloadingFile
                val finalFile = File(updatesDirectory, asset.name)

                val actualChecksum = downloadFile(asset.downloadUrl, downloadingFile, asset.size)
                check(actualChecksum == expectedChecksum) { "The downloaded APK checksum does not match the release" }
                verifyApkIdentity(context, downloadingFile)
                check(downloadingFile.renameTo(finalFile)) { "The verified APK could not be finalized" }
                Result.success(finalFile)
            } catch (error: CancellationException) {
                partialFile?.delete()
                throw error
            } catch (error: Exception) {
                partialFile?.delete()
                Result.failure(error)
            }
        }

    fun createInstallIntent(
        context: Context,
        apkFile: File,
    ): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun readHttpsText(url: String): String {
        val connection = openConnection(url)
        return try {
            check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}" }
            check(connection.url.protocol.equals("https", ignoreCase = true)) { "The checksum download used an insecure redirect" }
            val reportedSize = connection.contentLengthLong
            if (reportedSize > 0L) {
                check(reportedSize <= MAX_CHECKSUM_SIZE_BYTES) { "The checksum file is too large" }
            }

            val output = ByteArrayOutputStream()
            var totalBytes = 0L
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    check(totalBytes <= MAX_CHECKSUM_SIZE_BYTES) { "The checksum file exceeded the size limit" }
                    output.write(buffer, 0, read)
                }
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(
        url: String,
        destination: File,
        expectedSize: Long,
    ): String {
        val connection = openConnection(url)
        return try {
            check(connection.responseCode in 200..299) { "APK download failed with HTTP ${connection.responseCode}" }
            check(connection.url.protocol.equals("https", ignoreCase = true)) { "The APK download used an insecure redirect" }
            val reportedSize = connection.contentLengthLong
            if (reportedSize > 0L) {
                check(reportedSize <= MAX_APK_SIZE_BYTES) { "The downloaded APK is too large" }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        check(totalBytes <= MAX_APK_SIZE_BYTES) { "The downloaded APK exceeded the size limit" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(totalBytes == expectedSize) { "The downloaded APK size does not match the release" }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "MetroVerse/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/octet-stream")
        }

    @Suppress("DEPRECATION")
    private fun verifyApkIdentity(
        context: Context,
        apkFile: File,
    ) {
        val packageManager = context.packageManager
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        val archiveInfo =
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: error("Android could not read the downloaded APK")
        check(archiveInfo.packageName == context.packageName) { "The downloaded APK has a different package name" }

        val installedInfo = packageManager.getPackageInfo(context.packageName, flags)
        check(longVersionCode(archiveInfo) > longVersionCode(installedInfo)) { "The downloaded APK is not newer than this app" }

        val installedSigners = signerDigests(installedInfo)
        val archiveSigners = signerDigests(archiveInfo)
        check(installedSigners.isNotEmpty() && archiveSigners == installedSigners) {
            "The downloaded APK signing certificate does not match this app"
        }
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners.orEmpty()
            } else {
                packageInfo.signatures.orEmpty()
            }
        return signatures
            .map { signature ->
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.toSet()
    }
}
