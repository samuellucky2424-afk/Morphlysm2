package top.niunaijun.blackboxa.data

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a release APK, checks it against the published SHA-256, and only
 * then hands it to the package installer. A debug-signed test build cannot be
 * replaced by the Morphly release key, so that case is reported instead of
 * being left to the system "package appears to be invalid" dialog.
 */
object AppUpdater {
    data class Result(
            val file: File,
            val installerUri: Uri,
            val sameSignature: Boolean,
            val installedCert: String?,
            val updateCert: String?
    )

    fun downloadVerified(activity: Activity, apkUrl: String, expectedSha256: String, expectedSize: Long): Result {
        val target = File(activity.cacheDir, "Morphly-update.apk")
        if (target.exists()) target.delete()
        val connection = URL(apkUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (${connection.responseCode})")
            }
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        if (expectedSize > 0 && target.length() != expectedSize) {
            target.delete()
            throw IllegalStateException("Download was truncated (${target.length()} of $expectedSize bytes)")
        }
        val actual = sha256(target)
        val expected = expectedSha256.trim().lowercase()
        if (expected.isNotBlank() && actual != expected) {
            target.delete()
            throw IllegalStateException("Download did not match the published SHA-256")
        }
        if (activity.packageManager.getPackageArchiveInfo(target.absolutePath, 0) == null) {
            target.delete()
            throw IllegalStateException("Downloaded file is not a valid APK")
        }
        val updateCert = certificateSha256(activity.packageManager, target)
        val installedCert = runCatching { certificateSha256(activity.packageManager, activity.packageName) }.getOrNull()
        val sameSignature = installedCert == null || updateCert == null || installedCert == updateCert
        val uri = publishToDownloads(activity, target)
        return Result(target, uri, sameSignature, installedCert, updateCert)
    }

    fun openInstaller(activity: Activity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    fun requestUninstall(activity: Activity) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${activity.packageName}"))
        activity.startActivity(intent)
    }

    private fun publishToDownloads(activity: Activity, source: File): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Uri.fromFile(source)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "Morphly-verified.apk")
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the download")
        activity.contentResolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } ?: throw IllegalStateException("Could not write the download")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        activity.contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 64)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun certificateSha256(packageManager: PackageManager, apk: File): String? {
        val info = packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags()) ?: return null
        info.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        return certificates(info).firstOrNull()
    }

    private fun certificateSha256(packageManager: PackageManager, packageName: String): String? {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, signingFlags())
        }
        return certificates(info).firstOrNull()
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun certificates(info: PackageInfo): List<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return raw.map { bytes -> digest.digest(bytes).joinToString("") { "%02x".format(it) } }
    }
}
