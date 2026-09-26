package com.dinh.javis.commands

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CountDownTimer
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import com.dinh.javis.ai.OpenAiClient
import com.dinh.javis.data.CustomCommand
import com.dinh.javis.service.JavisAccessibilityService
import com.dinh.javis.service.JavisDeviceAdminReceiver
import com.dinh.javis.utils.AppHelper
import com.dinh.javis.utils.PermissionHelper
import com.dinh.javis.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bộ thực thi lệnh trung tâm của JAVIS
 * Tối ưu theo Prompt v4:
 * - Khi nhận đúng lệnh hành động (lướt, âm lượng, mở app): chỉ beep ngắn 100ms, KHÔNG TTS,
 *   giúp video TikTok/YouTube không bị dừng hay mất tiếng.
 * - TTS chỉ dùng khi: có lỗi, thông tin hỏi đáp (giờ/ngày), hoặc AI trả lời.
 * - Quy ước chiều lướt: "lướt lên" = xem nội dung mới phía dưới (giống vuốt ngón tay từ dưới lên).
 */
class CommandExecutor(
    private val context: Context,
    private val speaker: Speaker,
    private val openAiClient: OpenAiClient,
    private val scope: CoroutineScope,
    private val onLogMessage: (text: String, isUser: Boolean, tag: String?) -> Unit
) {

    private var activeTimer: CountDownTimer? = null

    /**
     * Thực thi lệnh và phản hồi bằng beep hoặc giọng nói + giao diện
     */
    fun execute(command: Command) {
        when (command) {
            is Command.OpenApp -> handleOpenApp(command.appName)
            is Command.ScrollUp -> handleScrollUp()
            is Command.ScrollDown -> handleScrollDown()
            is Command.ClickButton -> handleClickButton(command.buttonText)
            is Command.GoBack -> handleGoBack()
            is Command.GoHome -> handleGoHome()
            is Command.ChangeVolume -> handleVolume(command.action)
            is Command.ToggleWifi -> handleWifi(command.enable)
            is Command.ToggleBluetooth -> handleBluetooth(command.enable)
            is Command.ToggleTorch -> handleTorch(command.enable)
            is Command.LockScreen -> handleLockScreen()
            is Command.TakeScreenshot -> handleTakeScreenshot()
            is Command.GetTime -> handleGetTime()
            is Command.GetDate -> handleGetDate()
            is Command.SetTimer -> handleSetTimer(command.totalSeconds, command.label)
            is Command.SetAlarm -> handleSetAlarm(command.hour, command.minute)
            is Command.MakeCall -> handleMakeCall(command.contactName)
            is Command.SendSms -> handleSendSms(command.contactName, command.messageBody)
            is Command.Custom -> handleCustomCommand(command)
            is Command.RunBehaviorAgent -> handleRunBehaviorAgent(command.goal)
            is Command.AnalyzeScreen -> handleAnalyzeScreen(command.prompt)
            is Command.AskAi -> handleAskAi(command.prompt)
            is Command.Unknown -> handleUnknown(command.rawText)
        }
    }

    /**
     * Phản hồi bằng tiếng beep 100ms và ghi log (dành cho thao tác thành công, không ngắt video)
     */
    private fun respondWithBeep(logText: String, tag: String) {
        onLogMessage(logText, false, tag)
        speaker.playAckBeep()
    }

    /**
     * Phản hồi bằng giọng nói (dành cho lỗi, câu hỏi thời gian, AI trả lời)
     */
    private fun respondWithVoice(text: String, tag: String? = null) {
        onLogMessage(text, false, tag)
        speaker.speak(text)
    }

    // =========================================================================
    // 1. MỞ ỨNG DỤNG (YOUTUBE, TIKTOK, V.V.)
    // =========================================================================
    private fun handleOpenApp(appName: String) {
        val (success, appTitle) = AppHelper.openAppByName(context, appName)
        if (success) {
            respondWithBeep("Đang mở $appTitle", "MỞ APP")
        } else {
            respondWithVoice("Chưa cài $appName trên máy này.", "LỖI")
        }
    }

    // =========================================================================
    // 2. ĐIỀU KHIỂN TRỢ NĂNG (LƯỚT LÊN, LƯỚT XUỐNG, CLICK, NAV)
    // =========================================================================

    /**
     * Kiểm tra dịch vụ Trợ năng. Nếu chưa bật:
     * Nói: "Bạn chưa bật Trợ năng cho JAVIS" và tự động mở Cài đặt -> Trợ năng.
     */
    private fun ensureAccessibilityService(): JavisAccessibilityService? {
        val service = JavisAccessibilityService.instance
        if (service == null) {
            respondWithVoice("Bạn chưa bật Trợ năng cho JAVIS", "TRỢ NĂNG")
            PermissionHelper.openAccessibilitySettings(context)
            return null
        }
        return service
    }

    /**
     * "lướt lên", "vuốt lên", "cuộn lên":
     * Xem nội dung tiếp theo (video TikTok tiếp theo)
     * Thử ACTION_SCROLL_FORWARD qua AccessibilityService, fallback vuốt từ dưới lên giữa màn hình.
     * Quy ước: "lướt lên" = xem nội dung mới phía dưới (vuốt ngón tay từ dưới lên).
     */
    private fun handleScrollUp() {
        val service = ensureAccessibilityService() ?: return
        val ok = service.scrollForward()
        if (ok) {
            respondWithBeep("Đã lướt lên (video tiếp theo)", "LƯỚT LÊN")
        } else {
            respondWithVoice("Không thể cuộn màn hình lúc này", "LƯỚT LÊN")
        }
    }

    /**
     * "lướt xuống", "vuốt xuống", "cuộn xuống":
     * Xem nội dung trước đó (video TikTok trước)
     * ACTION_SCROLL_BACKWARD, fallback vuốt từ trên xuống.
     */
    private fun handleScrollDown() {
        val service = ensureAccessibilityService() ?: return
        val ok = service.scrollBackward()
        if (ok) {
            respondWithBeep("Đã lướt xuống (video trước)", "LƯỚT XUỐNG")
        } else {
            respondWithVoice("Không thể cuộn màn hình lúc này", "LƯỚT XUỐNG")
        }
    }

    private fun handleClickButton(buttonText: String) {
        val service = ensureAccessibilityService() ?: return
        val ok = service.clickNodeByText(buttonText)
        if (ok) {
            respondWithBeep("Đã bấm nút $buttonText", "BẤM NÚT")
        } else {
            respondWithVoice("Không tìm thấy nút \"$buttonText\" trên màn hình", "BẤM NÚT")
        }
    }

    private fun handleGoBack() {
        val service = ensureAccessibilityService() ?: return
        service.pressBack()
        respondWithBeep("Đã quay lại", "QUAY LẠI")
    }

    private fun handleGoHome() {
        val service = ensureAccessibilityService() ?: return
        service.goHome()
        respondWithBeep("Đã về màn hình chính", "TRANG CHỦ")
    }

    // =========================================================================
    // 3. ĐIỀU KHIỂN ÂM LƯỢNG (LOA MEDIA) & PHẦN CỨNG
    // =========================================================================
    private fun handleVolume(action: Command.VolumeAction) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        when (action) {
            Command.VolumeAction.UP -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                respondWithBeep("Đã tăng âm lượng", "ÂM LƯỢNG")
            }
            Command.VolumeAction.DOWN -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                respondWithBeep("Đã giảm âm lượng", "ÂM LƯỢNG")
            }
            Command.VolumeAction.MUTE -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                respondWithBeep("Đã tắt tiếng", "ÂM LƯỢNG")
            }
            Command.VolumeAction.UNMUTE -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                respondWithBeep("Đã bật lại tiếng", "ÂM LƯỢNG")
            }
        }
    }

    private fun handleWifi(enable: Boolean) {
        val actionText = if (enable) "bật" else "tắt"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                respondWithBeep("Đã mở bảng điều khiển Wi-Fi", "WIFI")
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiManager?.isWifiEnabled = enable
                respondWithBeep("Đã $actionText Wi-Fi", "WIFI")
            }
        } catch (e: Exception) {
            respondWithVoice("Không thể thay đổi Wi-Fi tự động", "WIFI")
        }
    }

    private fun handleBluetooth(enable: Boolean) {
        val actionText = if (enable) "bật" else "tắt"
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                respondWithBeep("Đã mở cài đặt Bluetooth", "BLUETOOTH")
            } else {
                @Suppress("DEPRECATION")
                if (enable) adapter?.enable() else adapter?.disable()
                respondWithBeep("Đã $actionText Bluetooth", "BLUETOOTH")
            }
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            respondWithBeep("Đã mở cài đặt Bluetooth", "BLUETOOTH")
        }
    }

    private fun handleTorch(enable: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, enable)
            respondWithBeep(if (enable) "Đã bật đèn pin" else "Đã tắt đèn pin", "ĐÈN PIN")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi điều khiển đèn pin", e)
            respondWithVoice("Không thể điều khiển đèn pin lúc này: ${e.localizedMessage}", "LỖI")
        }
    }

    private fun handleLockScreen() {
        val service = JavisAccessibilityService.instance
        if (service != null && service.lockScreen()) {
            respondWithBeep("Đang khóa màn hình", "KHÓA MÁY")
            return
        }

        // Fallback: Device Admin
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, JavisDeviceAdminReceiver::class.java)
        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            respondWithBeep("Đang khóa màn hình", "KHÓA MÁY")
            dpm.lockNow()
        } else {
            respondWithVoice("Bạn chưa bật quyền Trợ năng cho JAVIS để khóa màn hình.", "KHÓA MÁY")
            PermissionHelper.openAccessibilitySettings(context)
        }
    }

    private fun handleTakeScreenshot() {
        val service = ensureAccessibilityService() ?: return
        val ok = service.takeScreenshot()
        if (ok) {
            respondWithBeep("Đã chụp ảnh màn hình", "CHỤP MÀN HÌNH")
        } else {
            respondWithVoice("Không thể chụp ảnh màn hình lúc này", "LỖI")
        }
    }

    // =========================================================================
    // 4. TIỆN ÍCH THỜI GIAN, HẸN GIỜ, BÁO THỨC, CUỘC GỌI
    // =========================================================================
    private fun handleGetTime() {
        val timeString = AppHelper.getFormattedTimeVi()
        respondWithVoice(timeString, "XEM GIỜ")
    }

    private fun handleGetDate() {
        val dateString = AppHelper.getFormattedDateVi()
        respondWithVoice(dateString, "XEM NGÀY")
    }

    private fun handleSetTimer(totalSeconds: Int, label: String) {
        activeTimer?.cancel()
        respondWithVoice("Đã hẹn giờ $label, bắt đầu đếm ngược ngay bây giờ.", "HẸN GIỜ")

        activeTimer = object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                val alertMsg = "Đã hết thời gian hẹn giờ $label rồi bạn ơi!"
                onLogMessage(alertMsg, false, "HẾT GIỜ")
                speaker.speak(alertMsg)
            }
        }.start()
    }

    private fun handleSetAlarm(hour: Int, minute: Int) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "JAVIS Báo thức")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            respondWithVoice("Đã mở đặt báo thức lúc $hour giờ $minute phút.", "BÁO THỨC")
        } catch (e: Exception) {
            respondWithVoice("Không thể đặt báo thức tự động: ${e.localizedMessage}", "LỖI")
        }
    }

    private fun handleMakeCall(contactName: String) {
        val contact = AppHelper.findContactByName(context, contactName)
        if (contact == null) {
            respondWithVoice("Không tìm thấy số điện thoại của \"$contactName\" trong danh bạ.", "CUỘC GỌI")
            return
        }

        val (name, number) = contact
        respondWithVoice("Đang gọi cho $name số $number", "CUỘC GỌI")

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(callIntent)
        } catch (e: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        }
    }

    private fun handleSendSms(contactName: String, messageBody: String) {
        val contact = AppHelper.findContactByName(context, contactName)
        val phoneNumber = contact?.second ?: ""

        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = if (phoneNumber.isNotEmpty()) Uri.parse("smsto:$phoneNumber") else Uri.parse("smsto:")
            putExtra("sms_body", messageBody)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(smsIntent)
            val toWhom = contact?.first ?: contactName
            respondWithVoice("Đã mở tin nhắn gửi cho $toWhom với nội dung soạn sẵn.", "TIN NHẮN")
        } catch (e: Exception) {
            respondWithVoice("Không thể mở ứng dụng tin nhắn: ${e.localizedMessage}", "LỖI")
        }
    }

    // =========================================================================
    // 5. LỆNH TÙY CHỈNH & AI
    // =========================================================================
    private fun handleCustomCommand(custom: Command.Custom) {
        when (custom.actionType) {
            CustomCommand.ACTION_OPEN_APP -> {
                val (ok, appName) = AppHelper.openAppByName(context, custom.targetParam)
                if (ok) respondWithBeep("Đang mở $appName theo lệnh tùy chỉnh", "LỆNH TÙY CHỈNH")
                else respondWithVoice("Chưa cài ${custom.targetParam} trên máy này", "LỖI")
            }
            CustomCommand.ACTION_SCROLL_UP -> handleScrollUp()
            CustomCommand.ACTION_SCROLL_DOWN -> handleScrollDown()
            CustomCommand.ACTION_CLICK_TEXT -> handleClickButton(custom.targetParam)
            CustomCommand.ACTION_OPEN_URL -> {
                val url = if (!custom.targetParam.startsWith("http://") && !custom.targetParam.startsWith("https://")) {
                    "https://${custom.targetParam}"
                } else custom.targetParam
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                respondWithBeep("Đang mở liên kết trang web", "MỞ LIÊN KẾT")
            }
        }
    }

    private fun handleAskAi(prompt: String) {
        onLogMessage("Đang hỏi AI...", false, "AI...")
        scope.launch {
            val response = openAiClient.askAi(prompt)
            withContext(Dispatchers.Main) {
                respondWithVoice(response, "AI")
            }
        }
    }

    private fun handleAnalyzeScreen(prompt: String) {
        onLogMessage("Đang quan sát và phân tích màn hình...", false, "THỊ GIÁC")
        scope.launch {
            val observationEngine = com.dinh.javis.vision.ScreenObservationEngine(context)
            val modelRouter = com.dinh.javis.ai.ModelRouter.getInstance(context)
            val obsResult = observationEngine.observeScreen(captureVisual = true)
            val bitmap = obsResult.bitmap
            val nodeContext = obsResult.observation.nodeHierarchyText

            val response = if (bitmap != null) {
                modelRouter.analyzeScreen(prompt, bitmap, nodeContext).description
            } else if (!nodeContext.isNullOrBlank()) {
                modelRouter.askAi("Dựa trên màn hình đang hiển thị:\n$nodeContext\nHãy trả lời: $prompt")
            } else {
                "Chưa chụp được ảnh màn hình. Hãy bật tính năng thị giác và cấp quyền chụp màn hình nhé."
            }

            withContext(Dispatchers.Main) {
                respondWithVoice(response, "THỊ GIÁC")
            }
        }
    }

    private fun handleRunBehaviorAgent(goal: String) {
        onLogMessage("Bắt đầu tác vụ tự động: \"$goal\"", false, "AGENT")
        val orchestrator = com.dinh.javis.agent.AgentOrchestrator.getInstance(context)
        orchestrator.executeGoal(goal, object : com.dinh.javis.agent.AgentCallback {
            override fun onStepStarted(stepIndex: Int, maxSteps: Int) {
                onLogMessage("Bước $stepIndex/$maxSteps: Đang phân tích...", false, "AGENT")
            }

            override fun onThought(thought: String) {
                onLogMessage("Suy nghĩ: $thought", false, "AGENT")
            }

            override fun onActionExecuted(action: String, details: String) {
                onLogMessage("Thực hiện: $details", false, "HÀNH ĐỘNG")
            }

            override fun onConfirmationRequired(question: String, onUserResponse: (Boolean) -> Unit) {
                onLogMessage("Cần xác nhận: $question", false, "XÁC NHẬN")
                onUserResponse(true)
            }

            override fun onCompleted(success: Boolean, message: String) {
                if (success) {
                    respondWithVoice(message, "HOÀN THÀNH")
                } else {
                    respondWithVoice("Tác vụ dừng lại: $message", "THẤT BẠI")
                }
            }
        })
    }

    private fun handleUnknown(rawText: String) {
        respondWithVoice("Tôi chưa hiểu câu lệnh này. Bạn hãy vào Cài đặt để thêm lệnh tùy chỉnh nhé!", "CHƯA HIỂU")
    }

    companion object {
        private const val TAG = "CommandExecutor"
    }
}
