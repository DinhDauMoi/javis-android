package com.dinh.javis.commands

import com.dinh.javis.data.CustomCommand
import com.dinh.javis.utils.TextNormalizer
import java.util.regex.Pattern

/**
 * Trình phân tích cú pháp câu nói người dùng thành đối tượng Command.
 * Hỗ trợ nhận diện tiếng Việt có dấu, không dấu ("tang am luong" = "tăng âm lượng")
 * và loại bỏ các từ đệm tự nhiên ("ê javis lướt lên dùm", "javis lướt lên" -> "lướt lên").
 *
 * Config sẵn các câu lệnh tiêu chuẩn theo Prompt v4:
 * - "lướt lên", "vuốt lên", "cuộn lên": xem video tiếp theo (scroll forward / swipe up)
 * - "lướt xuống", "vuốt xuống", "cuộn xuống": xem video trước đó (scroll backward / swipe down)
 * - "mở youtube": mở com.google.android.youtube
 * - "mở tiktok", "mở tik tok": mở com.zhiliaoapp.musically
 * - "tăng âm lượng", "to lên", "âm lượng to lên": tăng âm lượng media
 * - "giảm âm lượng", "nhỏ lại", "nhỏ xuống", "âm lượng nhỏ xuống": giảm âm lượng media
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

        // Bỏ từ đệm và từ đánh thức ("ê javis", "javis", "jarvis", "dùm", "hộ",...)
        val cleanInput = TextNormalizer.stripFillerWords(trimmed)
        val normalized = TextNormalizer.removeAccents(cleanInput)

        // 1. Ưu tiên kiểm tra các lệnh tùy chỉnh của người dùng trước
        for (custom in customCommands) {
            val normCustom = custom.normalizedPhrase
            if (normalized == normCustom || normalized.contains(normCustom)) {
                return Command.Custom(custom.actionType, custom.targetParam, custom.triggerPhrase)
            }
        }

        // 2. Nhận diện lệnh mua sắm / tìm sản phẩm Shopee (Section 4)
        if (com.dinh.javis.agent.shopping.ShoppingTaskParser.isShoppingIntent(cleanInput)) {
            val request = com.dinh.javis.agent.shopping.ShoppingTaskParser.parse(cleanInput)
            return Command.FindProduct(request)
        }

        // 2. Điều khiển cử chỉ cuộn / lướt (TikTok, Facebook Reels, YouTube Shorts, v.v.)
        //
        // QUY ƯỚC CHIỀU LƯỚT:
        //   "lướt lên", "vuốt lên", "cuộn lên" = xem nội dung mới phía dưới (giống vuốt ngón tay từ dưới lên)
        //   "lướt xuống", "vuốt xuống", "cuộn xuống" = xem nội dung trước đó phía trên (vuốt từ trên xuống)
        if (normalized.contains("luot len") || normalized.contains("vuot len") ||
            normalized.contains("cuon len") || normalized.contains("next") ||
            normalized.contains("video tiep") || normalized.contains("tiep theo") ||
            normalized.contains("next video") || normalized.contains("sang video") ||
            normalized.contains("xem tiep") || normalized == "len"
        ) {
            return Command.ScrollUp
        }

        if (normalized.contains("luot xuong") || normalized.contains("vuot xuong") ||
            normalized.contains("cuon xuong") || normalized.contains("previous") ||
            normalized.contains("video truoc") || normalized.contains("quay lai video") ||
            normalized.contains("xem lai") || normalized == "xuong"
        ) {
            return Command.ScrollDown
        }

        // 3. Screen capture service activation intent: "bật dịch vụ màn hình", "turn on screenCaptureService", etc.
        if (normalized.contains("screencaptureservice") ||
            normalized.contains("screen capture") ||
            (normalized.contains("bat") && (normalized.contains("dich vu man hinh") || normalized.contains("quan sat man hinh") || normalized.contains("thi giac man hinh"))) ||
            (normalized.contains("cap quyen") && (normalized.contains("man hinh") || normalized.contains("quan sat")))
        ) {
            return Command.StartScreenCapture
        }

        // 4. Computer vision & screen analysis: "nhìn màn hình", "xem màn hình", "màn hình có gì"
        if (normalized.contains("nhin man hinh") || normalized.contains("xem man hinh") ||
            normalized.contains("man hinh co gi") || normalized.contains("phan tich man hinh") ||
            normalized.contains("doc man hinh") || normalized.contains("tren man hinh co")
        ) {
            return Command.AnalyzeScreen(cleanInput)
        }

        // 4. Tác vụ đa bước Behavior Agent: "tự động ...", "thực hiện tác vụ ...", "agent ..."
        if (normalized.startsWith("tu dong ") || normalized.contains("thuc hien tac vu ") ||
            normalized.contains("tim va bam ") || normalized.startsWith("agent ")
        ) {
            val goal = cleanInput.removePrefix("tự động").removePrefix("agent").trim()
            return Command.RunBehaviorAgent(goal.ifBlank { cleanInput })
        }

        // 5. Mở nhanh các ứng dụng phổ biến và mở theo từ khóa "mở" + tên app (chấp nhận chứa từ khóa, không dấu, chữ thường)
        if (normalized.contains("youtube") || normalized.contains("you tube")) {
            return Command.OpenApp("youtube")
        }
        if (normalized.contains("tiktok") || normalized.contains("tik tok")) {
            return Command.OpenApp("tiktok")
        }
        if (normalized.contains("facebook") || normalized.contains("face book") || normalized.contains(" fb")) {
            return Command.OpenApp("facebook")
        }
        if (normalized.contains("zalo")) {
            return Command.OpenApp("zalo")
        }
        if (normalized.contains("chrome") || normalized.contains("trinh duyet")) {
            return Command.OpenApp("chrome")
        }
        if (normalized.contains("camera") || normalized.contains("may anh")) {
            return Command.OpenApp("camera")
        }
        if (normalized.contains("cai dat") || normalized.contains("settings")) {
            return Command.OpenApp("settings")
        }
        if (normalized.contains("tin nhan") && !normalized.contains("nhan tin cho")) {
            return Command.OpenApp("messages")
        }

        // Chấp nhận chứa từ khóa ("mở" / "vào" / "khởi động" + tên app) thay vì khớp tuyệt đối
        val openAppRegex = Pattern.compile("(?:mo|khoi dong|chay app|chay|vao app|vao|open)\\s+([a-zA-Z0-9_\\s]+)", Pattern.CASE_INSENSITIVE)
        val openAppMatcher = openAppRegex.matcher(normalized)
        if (openAppMatcher.find()) {
            val appTarget = openAppMatcher.group(1)?.trim() ?: ""
            if (appTarget.isNotEmpty() && !appTarget.contains("den pin") && !appTarget.contains("wifi") && !appTarget.contains("bluetooth") && !appTarget.contains("am luong")) {
                return Command.OpenApp(appTarget)
            }
        }

        // 4. Điều khiển âm lượng (Nhận cả có dấu và không dấu)
        // Lệnh tăng âm lượng: "tăng âm lượng", "to lên", "âm lượng to lên",...
        if (normalized.contains("tang am luong") || normalized.contains("tang am") ||
            normalized.contains("am luong to len") || normalized.contains("to len") ||
            normalized.contains("cho to len") || normalized.contains("bat to len") ||
            normalized.contains("am luong len") || normalized.contains("to hon") ||
            normalized.contains("lon hon") || normalized.contains("volume up") || normalized.contains("louder")
        ) {
            return Command.ChangeVolume(Command.VolumeAction.UP)
        }

        // Lệnh giảm âm lượng: "giảm âm lượng", "nhỏ lại", "nhỏ xuống", "âm lượng nhỏ xuống",...
        if (normalized.contains("giam am luong") || normalized.contains("giam am") ||
            normalized.contains("am luong nho xuong") || normalized.contains("nho xuong") ||
            normalized.contains("nho lai") || normalized.contains("cho nho lai") ||
            normalized.contains("am luong nho lai") || normalized.contains("am luong xuong") ||
            normalized.contains("nho hon") || normalized.contains("be lai") ||
            normalized.contains("volume down") || normalized.contains("quieter")
        ) {
            return Command.ChangeVolume(Command.VolumeAction.DOWN)
        }

        if (normalized.contains("tat tieng") || normalized.contains("im lang") ||
            normalized.contains("mute") || normalized.contains("tat am")
        ) {
            return Command.ChangeVolume(Command.VolumeAction.MUTE)
        }
        if (normalized.contains("bat tieng") || normalized.contains("unmute") ||
            normalized.contains("bat am") || normalized.contains("co tieng")
        ) {
            return Command.ChangeVolume(Command.VolumeAction.UNMUTE)
        }

        // 5. Bấm nút theo text hiển thị
        val clickRegex = Pattern.compile("^(?:bam|nhan|click|cham|an|chon)(?: vao)?(?: nut)?\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val clickMatcher = clickRegex.matcher(normalized)
        if (clickMatcher.find()) {
            val buttonText = clickMatcher.group(1)?.trim() ?: ""
            if (buttonText.isNotEmpty()) {
                return Command.ClickButton(buttonText)
            }
        }

        // 6. Điều hướng Back & Home
        if (normalized.contains("quay lai") || normalized.contains("tro ve") || normalized == "back") {
            return Command.GoBack
        }
        if (normalized.contains("ve man hinh chinh") || normalized.contains("ve trang chu") ||
            normalized.contains("ve home") || normalized == "man hinh chinh" || normalized == "home"
        ) {
            return Command.GoHome
        }

        // 7. Điều khiển kết nối & thiết bị (Wifi, Bluetooth, Đèn pin, Khóa màn hình)
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

        // 8. Tiện ích thời gian & ngày tháng
        if (normalized.contains("may gio") || normalized.contains("xem gio") || normalized.contains("bay gio la may gio")) {
            return Command.GetTime
        }
        if (normalized.contains("ngay may") || normalized.contains("ngay bao nhieu") || normalized.contains("xem ngay") || normalized.contains("hom nay thu may")) {
            return Command.GetDate
        }

        // 9. Hẹn giờ đếm ngược: "hẹn giờ 10 giây", "hẹn giờ 5 phút", "đếm ngược 30 giây"
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

        // 10. Đặt báo thức: "đặt báo thức 6 giờ", "đặt báo thức 7 giờ 30", "báo thức lúc 6h15"
        val alarmRegex = Pattern.compile("(?:dat bao thuc|bao thuc|hen bao thuc)(?: luc)?\\s+(\\d+)(?:\\s*gio|h)(?:\\s*(\\d+))?", Pattern.CASE_INSENSITIVE)
        val alarmMatcher = alarmRegex.matcher(normalized)
        if (alarmMatcher.find()) {
            val hour = alarmMatcher.group(1)?.toIntOrNull() ?: 7
            val minute = alarmMatcher.group(2)?.toIntOrNull() ?: 0
            return Command.SetAlarm(hour, minute)
        }

        // 11. Gọi điện: "gọi cho Mẹ", "gọi điện cho Nam", "gọi Lan"
        val callRegex = Pattern.compile("^(?:goi dien cho|goi cho|goi)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val callMatcher = callRegex.matcher(normalized)
        if (callMatcher.find()) {
            val contactName = callMatcher.group(1)?.trim() ?: ""
            if (contactName.isNotEmpty() && !contactName.contains("wifi") && !contactName.contains("bluetooth")) {
                return Command.MakeCall(contactName)
            }
        }

        // 12. Nhắn tin: "nhắn tin cho [tên]: [nội dung]" hoặc "nhắn [tên] [nội dung]"
        val smsRegex = Pattern.compile("^(?:nhan tin cho|nhan tin|gui tin nhan cho)\\s+([^:]+)(?::|noi dung|la)\\s*(.*)$", Pattern.CASE_INSENSITIVE)
        val smsMatcher = smsRegex.matcher(cleanInput)
        if (smsMatcher.find()) {
            val contactName = smsMatcher.group(1)?.trim() ?: ""
            val messageBody = smsMatcher.group(2)?.trim() ?: ""
            if (contactName.isNotEmpty()) {
                return Command.SendSms(contactName, messageBody)
            }
        }

        // 15. Mặc định: Gửi cho AI trả lời thông minh
        return Command.AskAi(trimmed)
    }
}
