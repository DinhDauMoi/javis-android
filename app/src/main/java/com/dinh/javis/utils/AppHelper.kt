package com.dinh.javis.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Tiện ích hỗ trợ tìm kiếm và mở ứng dụng, tra cứu danh bạ, lấy thông tin thời gian tiếng Việt
 */
object AppHelper {

    // Bảng map tên gọi thông dụng sang Package Name
    // Hỗ trợ cả tên có dấu và không dấu (normalized bởi TextNormalizer.removeAccents)
    private val POPULAR_APPS = mapOf(
        // TikTok — package chính thức: com.zhiliaoapp.musically (VN/Global)
        //          bản TikTok Lite: com.zhiliaoapp.musically.lite
        //          bản Trill (TQ):  com.ss.android.ugc.trill
        "tiktok"    to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.lite"),
        "tik tok"   to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.lite"),
        "tik"       to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        // YouTube
        "youtube"   to listOf("com.google.android.youtube"),
        "you tube"  to listOf("com.google.android.youtube"),
        "yt"        to listOf("com.google.android.youtube"),
        // Mạng xã hội
        "zalo"      to listOf("com.zing.zalo"),
        "facebook"  to listOf("com.facebook.katana"),
        "fb"        to listOf("com.facebook.katana"),
        "messenger" to listOf("com.facebook.orca"),
        "instagram" to listOf("com.instagram.android"),
        "ins"       to listOf("com.instagram.android"),
        // Trình duyệt & Google
        "chrome"    to listOf("com.android.chrome"),
        "google"    to listOf("com.google.android.googlequicksearchbox"),
        // Mua sắm
        "shopee"    to listOf("com.shopee.vn"),
        "lazada"    to listOf("com.lazada.android"),
        // Bản đồ
        "maps"      to listOf("com.google.android.apps.maps"),
        "ban do"    to listOf("com.google.android.apps.maps"),
        "google map" to listOf("com.google.android.apps.maps"),
        // Gọi video
        "zoom"      to listOf("us.zoom.videomeetings"),
        "meet"      to listOf("com.google.android.apps.meetings"),
        "teams"     to listOf("com.microsoft.teams"),
        // Nhạc & Media
        "spotify"   to listOf("com.spotify.music"),
        "capcut"    to listOf("com.lemon.lvoverseas")
    )

    /**
     * Mở ứng dụng theo tên người dùng nói (hỗ trợ cả tiếng Việt có dấu và không dấu)
     * @return Pair<Boolean, String>: Pair(Thành công hay không, Tên ứng dụng hiển thị)
     */
    fun openAppByName(context: Context, rawAppName: String): Pair<Boolean, String> {
        val cleanName = TextNormalizer.stripFillerWords(rawAppName).trim()
        val normalizedTarget = TextNormalizer.removeAccents(cleanName)

        // 1. Kiểm tra các app hệ thống đặc biệt
        when {
            normalizedTarget.contains("cai dat") || normalizedTarget == "settings" -> {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return Pair(true, "Cài đặt")
            }
            normalizedTarget.contains("camera") || normalizedTarget.contains("may anh") -> {
                context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return Pair(true, "Máy ảnh")
            }
            normalizedTarget.contains("dong ho") || normalizedTarget.contains("bao thuc") -> {
                val clockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (clockIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(clockIntent)
                    return Pair(true, "Đồng hồ")
                }
            }
            normalizedTarget.contains("tin nhan") || normalizedTarget == "sms" -> {
                val smsIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MESSAGING)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (smsIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(smsIntent)
                    return Pair(true, "Tin nhắn")
                }
            }
        }

        val pm = context.packageManager

        // 2. Kiểm tra map các ứng dụng phổ biến đã biết trước
        for ((key, pkgList) in POPULAR_APPS) {
            if (normalizedTarget.contains(key)) {
                for (pkg in pkgList) {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return Pair(true, key.uppercase())
                    }
                }
            }
        }

        // 3. Quét toàn bộ PackageManager tìm ứng dụng đã cài có tên hiển thị khớp gần đúng
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in installedApps) {
            // Chỉ xét các app có thể mở được (Launch Intent)
            if (pm.getLaunchIntentForPackage(appInfo.packageName) == null) continue

            val appLabel = pm.getApplicationLabel(appInfo).toString()
            val normalizedAppLabel = TextNormalizer.removeAccents(appLabel)

            if (normalizedAppLabel.contains(normalizedTarget) || normalizedTarget.contains(normalizedAppLabel)) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return Pair(true, appLabel)
                }
            }
        }

        return Pair(false, cleanName)
    }

    /**
     * Tra cứu số điện thoại theo tên trong danh bạ
     */
    fun findContactByName(context: Context, contactQuery: String): Pair<String, String>? {
        val normalizedQuery = TextNormalizer.removeAccents(contactQuery).trim()
        val resolver = context.contentResolver

        val cursor: Cursor? = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val displayName = it.getString(nameIndex) ?: ""
                val phoneNumber = it.getString(numberIndex) ?: ""

                val normalizedName = TextNormalizer.removeAccents(displayName)
                if (normalizedName.contains(normalizedQuery) || normalizedQuery.contains(normalizedName)) {
                    return Pair(displayName, phoneNumber.replace(" ", "").replace("-", ""))
                }
            }
        }
        return null
    }

    /**
     * Lấy chuỗi thông báo giờ hiện tại bằng tiếng Việt tự nhiên
     */
    fun getFormattedTimeVi(): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return if (minute == 0) {
            "Bây giờ là đúng $hour giờ."
        } else {
            "Bây giờ là $hour giờ $minute phút."
        }
    }

    /**
     * Lấy chuỗi thông báo ngày tháng hiện tại bằng tiếng Việt
     */
    fun getFormattedDateVi(): String {
        val cal = Calendar.getInstance()
        val dayOfWeekInt = cal.get(Calendar.DAY_OF_WEEK)
        val dayOfWeek = when (dayOfWeekInt) {
            Calendar.MONDAY -> "Thứ Hai"
            Calendar.TUESDAY -> "Thứ Ba"
            Calendar.WEDNESDAY -> "Thứ Tư"
            Calendar.THURSDAY -> "Thứ Năm"
            Calendar.FRIDAY -> "Thứ Sáu"
            Calendar.SATURDAY -> "Thứ Bảy"
            Calendar.SUNDAY -> "Chủ Nhật"
            else -> "Hôm nay"
        }

        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)

        return "$dayOfWeek, ngày $day tháng $month năm $year."
    }
}
