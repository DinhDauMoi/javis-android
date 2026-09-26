package com.dinh.javis.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Trình quản lý kiểm tra, tải về và cài đặt bản cập nhật APK trực tiếp từ GitHub Releases
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val GITHUB_REPO = "DinhDauMoi/javis-android"
        private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val tagName: String,
        val releaseName: String,
        val releaseNotes: String,
        val remoteVersionCode: Long,
        val apkDownloadUrl: String,
        val apkSize: Long
    )

    fun getCurrentVersionCode(): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lấy versionCode: ${e.message}")
            1L
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Kiểm tra phiên bản mới nhất từ GitHub Releases
     */
    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "JAVIS-Android-App")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    return@withContext Result.failure(Exception("Chưa có bản Release nào được xuất bản trên GitHub."))
                }
                return@withContext Result.failure(Exception("Lỗi kết nối GitHub (HTTP ${response.code}): ${response.message}"))
            }

            val bodyString = response.body?.string() ?: return@withContext Result.failure(Exception("Phản hồi rỗng từ GitHub"))
            val json = JSONObject(bodyString)

            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "")

            // Extract remote versionCode (supports "build-42", "v1.0.42", "1.0.42", "Build #42", etc.)
            val remoteVersionCode = Regex("""(?:build-|\.)(\d+)$""").find(tagName)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("""(\d+)$""").find(tagName)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("""Build\s*#?(\d+)""", RegexOption.IGNORE_CASE).find(releaseName)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L

            val currentCode = getCurrentVersionCode()

            // Tìm asset file .apk
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isEmpty()) {
                return@withContext Result.failure(Exception("Bản phát hành $tagName không đính kèm file APK."))
            }

            if (remoteVersionCode > currentCode) {
                Result.success(
                    UpdateInfo(
                        tagName = tagName,
                        releaseName = releaseName,
                        releaseNotes = releaseNotes,
                        remoteVersionCode = remoteVersionCode,
                        apkDownloadUrl = apkUrl,
                        apkSize = apkSize
                    )
                )
            } else {
                // Đã là bản mới nhất hoặc bản hiện tại cao hơn
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kiểm tra cập nhật: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Tải file APK với báo cáo tiến trình %
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "JAVIS-Android-App")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Lỗi tải APK (HTTP ${response.code})"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Phản hồi rỗng khi tải file"))
            val contentLength = body.contentLength()

            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            val apkFile = File(downloadDir, "javis-update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(apkFile)

            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastPercent = -1

            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val percent = ((totalBytesRead * 100) / contentLength).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent, totalBytesRead, contentLength)
                                }
                            }
                        }
                    }
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi tải file APK: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Kích hoạt cài đặt file APK qua FileProvider
     */
    fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(activity, "File APK không tồn tại hoặc đã bị xóa.", Toast.LENGTH_SHORT).show()
            return
        }

        // Kiểm tra quyền cài đặt ứng dụng từ nguồn không xác định (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    activity,
                    "Vui lòng bật 'Cho phép từ nguồn này' để JAVIS có thể cập nhật ứng dụng",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        try {
            val authority = "${activity.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(activity, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi chạy trình cài đặt: ${e.message}", e)
            Toast.makeText(activity, "Không thể mở trình cài đặt APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
