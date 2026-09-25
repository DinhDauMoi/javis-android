package com.dinh.javis.commands

/**
 * Định nghĩa tất cả các loại lệnh được hỗ trợ trong JAVIS
 */
sealed class Command {

    // 1. Mở ứng dụng
    data class OpenApp(val appName: String, val packageName: String? = null) : Command()

    // 2. Điều khiển ứng dụng qua Trợ năng
    object ScrollUp : Command()           // Lướt lên (nội dung tiếp theo, ví dụ video TikTok tiếp theo)
    object ScrollDown : Command()         // Lướt xuống (nội dung trước)
    data class ClickButton(val buttonText: String) : Command() // Bấm nút có text cụ thể
    object GoBack : Command()             // Quay lại
    object GoHome : Command()             // Về màn hình chính

    // 3. Điều khiển hệ thống
    enum class VolumeAction { UP, DOWN, MUTE, UNMUTE }
    data class ChangeVolume(val action: VolumeAction) : Command()
    data class ToggleWifi(val enable: Boolean) : Command()
    data class ToggleBluetooth(val enable: Boolean) : Command()
    data class ToggleTorch(val enable: Boolean) : Command()
    object LockScreen : Command()
    object TakeScreenshot : Command()

    // 4. Tiện ích thời gian, báo thức, cuộc gọi
    object GetTime : Command()
    object GetDate : Command()
    data class SetTimer(val totalSeconds: Int, val label: String) : Command()
    data class SetAlarm(val hour: Int, val minute: Int) : Command()
    data class MakeCall(val contactName: String) : Command()
    data class SendSms(val contactName: String, val messageBody: String) : Command()

    // 5. Lệnh tùy chỉnh do người dùng thêm
    data class Custom(val actionType: String, val targetParam: String, val trigger: String) : Command()

    // 6. Hỏi AI & Không xác định
    data class AskAi(val prompt: String) : Command()
    data class Unknown(val rawText: String) : Command()
}
