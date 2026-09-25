package com.dinh.javis.commands

import com.dinh.javis.data.CustomCommand
import com.dinh.javis.utils.TextNormalizer
import java.util.regex.Pattern

/**
 * Trình phân tích cú pháp câu nói người dùng thành đối tượng Command
 * Hỗ trợ nhận diện tiếng Việt có dấu, không dấu và loại bỏ các từ đệm tự nhiên.
 */
class CommandParser(private var customCommands: List<CustomCommand> = emptyList()) {

    fun updateCustomCommands(newList: List<CustomCommand>) {
        this.customCommands = newList
    }

    /**
     * Phân tích câu nói và trả về đối tượng Command tương ứng
     */
    fun parse(rawInput: String): Command {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Command.Unknown("")
        }

        // Bỏ từ đệm thừa ("ê javis", "làm ơn", "hộ", "dùm",...)
        val cleanInput = TextNormalizer.stripFillerWords(trimmed)
        val normalized = TextNormalizer.removeAccents(cleanInput)

        // 1. Ưu tiên kiểm tra các lệnh tùy chỉnh của người dùng trước
        for (custom in customCommands) {
            val normCustom = custom.normalizedPhrase
            if (normalized == normCustom || normalized.contains(normCustom)) {
                return Command.Custom(custom.actionType, custom.targetParam, custom.triggerPhrase)
            }
        }

        // 2. Điều khiển cử chỉ cuộn / lướt (TikTok, Facebook, YouTube Shorts, v.v.)
        if (normalized.contains("luot len") || normalized.contains("cuon len") ||
            normalized.contains("video tiep") || normalized.contains("tiep theo") ||
            normalized.contains("next video") || normalized == "len"
        ) {
            return Command.ScrollUp
        }

        if (normalized.contains("luot xuong") || normalized.contains("cuon xuong") ||
            normalized.contains("video truoc") || normalized.contains("quay lai video") ||
            normalized == "xuong"
        ) {
            return Command.ScrollDown
        }

        // 3. Bấm nút theo text hiển thị
        val clickRegex = Pattern.compile("^(?:bam|nhan|click|cham|an|chon)(?: vao)?(?: nut)?\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val clickMatcher = clickRegex.matcher(normalized)
        if (clickMatcher.find()) {
            val buttonText = clickMatcher.group(1)?.trim() ?: ""
            if (buttonText.isNotEmpty()) {
                return Command.ClickButton(buttonText)
            }
        }

        // 4. Điều hướng Back & Home
        if (normalized.contains("quay lai") || normalized.contains("tro ve") || normalized == "back") {
            return Command.GoBack
        }
        if (normalized.contains("ve man hinh chinh") || normalized.contains("ve trang chu") ||
            normalized.contains("ve home") || normalized == "man hinh chinh" || normalized == "home"
        ) {
            return Command.GoHome
        }

        // 5. Điều khiển âm lượng
        if (normalized.contains("tang am luong") || normalized.contains("bat tieng to hon") || normalized.contains("to len")) {
            return Command.ChangeVolume(Command.VolumeAction.UP)
        }
        if (normalized.contains("giam am luong") || normalized.contains("cho tieng nho lai") || normalized.contains("nho lai")) {
            return Command.ChangeVolume(Command.VolumeAction.DOWN)
        }
        if (normalized.contains("tat tieng") || normalized.contains("im lang") || normalized.contains("mute")) {
            return Command.ChangeVolume(Command.VolumeAction.MUTE)
        }
        if (normalized.contains("bat tieng") || normalized.contains("unmute")) {
            return Command.ChangeVolume(Command.VolumeAction.UNMUTE)
        }

        // 6. Điều khiển kết nối & thiết bị (Wifi, Bluetooth, Đèn pin, Khóa màn hình)
        if (normalized.contains("bat wifi")) return Command.ToggleWifi(true)
        if (normalized.contains("tat wifi")) return Command.ToggleWifi(false)

        if (normalized.contains("bat bluetooth")) return Command.ToggleBluetooth(true)
        if (normalized.contains("tat bluetooth")) return Command.ToggleBluetooth(false)

        if (normalized.contains("bat den pin") || normalized.contains("mo den pin")) return Command.ToggleTorch(true)
        if (normalized.contains("tat den pin") || normalized.contains("dong den pin")) return Command.ToggleTorch(false)

        if (normalized.contains("khoa man hinh") || normalized.contains("tat man hinh")) {
            return Command.LockScreen
        }

        if (normalized.contains("chup man hinh") || normalized.contains("chup anh man hinh") || normalized.contains("screenshot")) {
            return Command.TakeScreenshot
        }

        // 7. Tiện ích thời gian & ngày tháng
        if (normalized.contains("may gio") || normalized.contains("xem gio") || normalized.contains("bay gio la may gio")) {
            return Command.GetTime
        }
        if (normalized.contains("ngay may") || normalized.contains("ngay bao nhieu") || normalized.contains("xem ngay") || normalized.contains("hom nay thu may")) {
            return Command.GetDate
        }

        // 8. Hẹn giờ đếm ngược: "hẹn giờ 10 giây", "hẹn giờ 5 phút", "đếm ngược 30 giây"
        val timerRegex = Pattern.compile("(?:hen gio|dem nguoc)\\s+(\\d+)\\s*(phut|giay|s|m)?", Pattern.CASE_INSENSITIVE)
        val timerMatcher = timerRegex.matcher(normalized)
        if (timerMatcher.find()) {
            val amount = timerMatcher.group(1)?.toIntOrNull() ?: 0
            val unit = timerMatcher.group(2)?.lowercase() ?: "phut"
            val totalSeconds = if (unit == "giay" || unit == "s") amount else amount * 60
            if (totalSeconds > 0) {
                return Command.SetTimer(totalSeconds, "$amount $unit")
            }
        }

        // 9. Đặt báo thức: "đặt báo thức 6 giờ", "đặt báo thức 7 giờ 30", "báo thức lúc 6h15"
        val alarmRegex = Pattern.compile("(?:dat bao thuc|bao thuc|hen bao thuc)(?: luc)?\\s+(\\d+)(?:\\s*gio|h)(?:\\s*(\\d+))?", Pattern.CASE_INSENSITIVE)
        val alarmMatcher = alarmRegex.matcher(normalized)
        if (alarmMatcher.find()) {
            val hour = alarmMatcher.group(1)?.toIntOrNull() ?: 7
            val minute = alarmMatcher.group(2)?.toIntOrNull() ?: 0
            return Command.SetAlarm(hour, minute)
        }

        // 10. Gọi điện: "gọi cho Mẹ", "gọi điện cho Nam", "gọi Lan"
        val callRegex = Pattern.compile("^(?:goi dien cho|goi cho|goi)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val callMatcher = callRegex.matcher(normalized)
        if (callMatcher.find()) {
            val contactName = callMatcher.group(1)?.trim() ?: ""
            if (contactName.isNotEmpty() && !contactName.contains("wifi") && !contactName.contains("bluetooth")) {
                return Command.MakeCall(contactName)
            }
        }

        // 11. Nhắn tin: "nhắn tin cho [tên]: [nội dung]" hoặc "nhắn [tên] [nội dung]"
        val smsRegex = Pattern.compile("^(?:nhan tin cho|nhan tin|gui tin nhan cho)\\s+([^:]+)(?::|noi dung|la)\\s*(.*)$", Pattern.CASE_INSENSITIVE)
        val smsMatcher = smsRegex.matcher(cleanInput)
        if (smsMatcher.find()) {
            val contactName = smsMatcher.group(1)?.trim() ?: ""
            val messageBody = smsMatcher.group(2)?.trim() ?: ""
            if (contactName.isNotEmpty()) {
                return Command.SendSms(contactName, messageBody)
            }
        }

        // 12. Mở ứng dụng: "mở tiktok", "mở youtube", "mở zalo", "mở camera",...
        val openAppRegex = Pattern.compile("^(?:mo|khoi dong|chay app|vao app|vao)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val openAppMatcher = openAppRegex.matcher(cleanInput)
        if (openAppMatcher.find()) {
            val appTarget = openAppMatcher.group(1)?.trim() ?: ""
            if (appTarget.isNotEmpty()) {
                return Command.OpenApp(appTarget)
            }
        }

        // 13. Mặc định: Gửi cho AI trả lời thông minh
        return Command.AskAi(trimmed)
    }
}
