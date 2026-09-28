package com.dinh.javis

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dinh.javis.ai.OpenAiClient
import com.dinh.javis.commands.CommandExecutor
import com.dinh.javis.commands.CommandParser
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.Message
import com.dinh.javis.data.PreferenceManager
import com.dinh.javis.databinding.ActivityMainBinding
import com.dinh.javis.settings.SettingsActivity
import com.dinh.javis.ui.ChatAdapter
import androidx.appcompat.app.AlertDialog
import com.dinh.javis.utils.AppUpdateManager
import com.dinh.javis.utils.PermissionHelper
import java.util.Locale
import com.dinh.javis.voice.HotwordManager
import com.dinh.javis.voice.Speaker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Màn hình chính của JAVIS
 * Tích hợp HotwordManager (openWakeWord on-device) chạy nền:
 * - Chờ từ đánh thức "javis" (hoặc Hey Jarvis).
 * - Bắt được -> beep thức dậy -> nghe 1 lệnh (6s) -> xử lý -> trở về chờ "javis".
 * - Nút gạt "Chờ gọi 'javis'" mặc định BẬT, tắt -> dừng hẳn mic.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var speaker: Speaker
    private lateinit var hotwordManager: HotwordManager
    private lateinit var commandParser: CommandParser
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var database: AppDatabase

    private var pulseAnimator: ObjectAnimator? = null

    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, com.dinh.javis.vision.ScreenCaptureService::class.java).apply {
                putExtra(com.dinh.javis.vision.ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(com.dinh.javis.vision.ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Đã kích hoạt chế độ Thị giác màn hình JAVIS", Toast.LENGTH_SHORT).show()

            // Resume pending shopping request if held in memory waiting for consent
            val orchestrator = com.dinh.javis.agent.AgentOrchestrator.getInstance(this)
            val pending = orchestrator.pendingShoppingRequest
            if (pending != null) {
                orchestrator.pendingShoppingRequest = null
                orchestrator.executeShopping(pending)
            }
        } else {
            // Consent denied or cancelled: clean up pending request without auto-approval
            val orchestrator = com.dinh.javis.agent.AgentOrchestrator.getInstance(this)
            orchestrator.pendingShoppingRequest = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        database = AppDatabase.getDatabase(this)

        initViews()
        initServicesAndParsers()
        checkAppPermissions()
        handleLaunchIntent(intent)
        observeCustomCommands()
        checkAppUpdateOnStartup()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatusBadges()
        // Cập nhật trạng thái switch theo cài đặt
        binding.switchWakeWord.isChecked = preferenceManager.isWakeWordEnabled
        if (::hotwordManager.isInitialized) {
            hotwordManager.updateThreshold()
        }
    }

    private fun initViews() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.layoutManager = layoutManager
        binding.rvChat.adapter = chatAdapter

        appendMessage(
            "Xin chào! Tôi là JAVIS. Gọi \"javis\" để đánh thức và ra lệnh rảnh tay khi xem TikTok!",
            isUser = false,
            tag = "JAVIS"
        )

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.badgeAccessibility.setOnClickListener {
            if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
                PermissionHelper.openAccessibilitySettings(this)
            } else {
                Toast.makeText(this, "Dịch vụ Trợ năng JAVIS đang hoạt động tốt!", Toast.LENGTH_SHORT).show()
            }
        }

        // Nút bật/tắt "Chờ gọi 'javis'" trên giao diện (mặc định BẬT)
        binding.switchWakeWord.isChecked = preferenceManager.isWakeWordEnabled
        binding.switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.isWakeWordEnabled = isChecked
            if (isChecked) {
                if (!PermissionHelper.hasPermission(this, android.Manifest.permission.RECORD_AUDIO)) {
                    PermissionHelper.requestCorePermissions(this)
                } else {
                    hotwordManager.start()
                }
            } else {
                hotwordManager.stop()
            }
        }

        // Nút Mic lớn: Bấm để nói 1 lệnh trực tiếp mà không cần gọi "javis"
        binding.btnMic.setOnClickListener {
            hotwordManager.triggerOneShotCommand()
        }

        binding.btnSend.setOnClickListener { processManualTextInput() }
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                processManualTextInput()
                true
            } else false
        }

        setupMicPulseAnimation()
    }

    private fun setupMicPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.pulseRing,
            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.45f),
            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.45f),
            PropertyValuesHolder.ofFloat("alpha", 0.8f, 0.0f)
        ).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
    }

    private fun initServicesAndParsers() {
        speaker = Speaker(this)
        commandParser = CommandParser()
        val openAiClient = OpenAiClient(preferenceManager)

        commandExecutor = CommandExecutor(
            context = this,
            speaker = speaker,
            openAiClient = openAiClient,
            scope = lifecycleScope,
            onLogMessage = { text, isUser, tag ->
                runOnUiThread { appendMessage(text, isUser, tag) }
            }
        )

        hotwordManager = HotwordManager(
            context = this,
            speaker = speaker,
            preferenceManager = preferenceManager,
            commandParser = commandParser,
            onCommandRecognized = { text ->
                runOnUiThread { handleSpokenText(text) }
            },
            onStatusChange = { statusText, isListeningCommand, isWaitingHotword ->
                runOnUiThread {
                    binding.tvVoiceStatus.text = statusText
                    setMicUiState(isListeningCommand, isWaitingHotword)
                }
            },
            onLogMessage = { text, isUser, tag ->
                val showLog = preferenceManager.isDebugModeEnabled ||
                        (tag != "HOTWORD" && tag != "SCORE" && tag != "WAKE" && tag != "TIMING")
                if (showLog) {
                    runOnUiThread { appendMessage(text, isUser, tag) }
                }
            }
        )

        // Start Hotword service if permissions are granted and feature is enabled (enabled by default)
        if (preferenceManager.isWakeWordEnabled && PermissionHelper.hasCorePermissions(this)) {
            hotwordManager.start()
        }
    }

    private fun observeCustomCommands() {
        lifecycleScope.launch {
            database.customCommandDao().getAllAsFlow().collectLatest { commands ->
                commandParser.updateCustomCommands(commands)
            }
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_TRIGGER_VOICE || intent.action == "com.dinh.javis.ACTION_START_VOICE") {
            binding.root.postDelayed({ hotwordManager.triggerOneShotCommand() }, 300)
        } else if (intent.action == "com.dinh.javis.ACTION_REQUEST_SCREEN_CAPTURE") {
            requestScreenCaptureConsent()
        }
    }

    private fun checkAppPermissions() {
        if (!PermissionHelper.hasCorePermissions(this)) {
            PermissionHelper.requestCorePermissions(this)
        } else {
            checkOverlayAndBatteryPermissions()
        }
    }

    private fun checkOverlayAndBatteryPermissions() {
        // 1. Xin quyền vẽ đè để hiển thị viền sáng màn hình và nút mic nổi
        if (!PermissionHelper.canDrawOverlays(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Hiệu ứng viền sáng & Nút nổi")
                .setMessage("JAVIS cần quyền 'Vẽ lên trên ứng dụng khác' để hiển thị hiệu ứng viền màn hình phát sáng đa sắc (kiểu Siri / Google Assistant) khi bạn gọi 'javis' lúc xem TikTok hoặc dùng app khác.")
                .setPositiveButton("Cấp quyền") { _, _ ->
                    PermissionHelper.requestOverlayPermission(this)
                }
                .setNegativeButton("Để sau") { _, _ ->
                    checkBatteryOptimizationPermission()
                }
                .show()
        } else {
            checkBatteryOptimizationPermission()
        }
    }

    private fun checkBatteryOptimizationPermission() {
        // 2. Xin quyền bỏ qua tối ưu pin (tránh ColorOS bóp service ngầm khi ở nền)
        if (!PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Tối ưu chạy nền (ColorOS / OPPO)")
                .setMessage(
                    "Để JAVIS luôn nhận diện 'javis' tức thì khi đang xem TikTok hoặc tắt màn hình:\n\n" +
                    "1. Cho phép 'Tắt tối ưu pin' (bấm nút bên dưới).\n" +
                    "2. Khóa ứng dụng trong màn hình Đa nhiệm (vuốt mở đa nhiệm → kéo app JAVIS xuống hoặc bấm ⋮ → chọn Khóa 🔒).\n" +
                    "3. Bật 'Cho phép tự khởi chạy' và 'Cho phép chạy ngầm' trong Cài đặt pin của máy."
                )
                .setPositiveButton("Tắt tối ưu pin") { _, _ ->
                    PermissionHelper.requestIgnoreBatteryOptimizations(this)
                }
                .setNegativeButton("Đã hiểu", null)
                .show()
        }
    }

    fun requestScreenCaptureConsent() {
        if (!com.dinh.javis.vision.ScreenCaptureService.isCapturing()) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    private fun updateStatusBadges() {
        val isAccessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        if (isAccessibilityEnabled) {
            binding.badgeAccessibility.text = getString(R.string.badge_accessibility_on)
            binding.badgeAccessibility.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.badgeAccessibility.text = getString(R.string.badge_accessibility_off)
            binding.badgeAccessibility.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }

        val isFloating = preferenceManager.isFloatingMicEnabled && PermissionHelper.canDrawOverlays(this)
        binding.badgeFloating.text = if (isFloating) getString(R.string.badge_overlay_on) else getString(R.string.badge_overlay_off)
        binding.badgeFloating.setTextColor(
            ContextCompat.getColor(this, if (isFloating) R.color.status_green else R.color.text_secondary)
        )
    }

    private fun setMicUiState(isListeningCommand: Boolean, isWaitingHotword: Boolean) {
        if (isListeningCommand) {
            binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.mic_listening)
            binding.pulseRing.visibility = View.VISIBLE
            pulseAnimator?.start()
        } else {
            binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(
                this,
                if (isWaitingHotword) R.color.primary else R.color.bg_card
            )
            pulseAnimator?.cancel()
            binding.pulseRing.visibility = View.INVISIBLE
            binding.pulseRing.scaleX = 1f
            binding.pulseRing.scaleY = 1f
            binding.pulseRing.alpha = 1f
        }
    }

    private fun processManualTextInput() {
        val input = binding.etCommand.text?.toString()?.trim() ?: ""
        if (input.isNotEmpty()) {
            binding.etCommand.setText("")
            handleSpokenText(input)
        }
    }

    private fun handleSpokenText(text: String) {
        val displayMessage = com.dinh.javis.utils.TextNormalizer.stripHotword(text)
        Toast.makeText(this, "JAVIS nghe: \"$displayMessage\"", Toast.LENGTH_SHORT).show()
        appendMessage(displayMessage, isUser = true)
        binding.tvVoiceStatus.text = getString(R.string.status_processing)

        val command = commandParser.parse(text)
        commandExecutor.execute(command)
    }

    private fun appendMessage(text: String, isUser: Boolean, tag: String? = null) {
        val msg = Message(text = text, isUser = isUser, actionTag = tag)
        chatAdapter.addMessage(msg)
        binding.rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQUEST_CODE_CORE_PERMISSIONS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Toast.makeText(this, "Đã cấp quyền Micro thành công!", Toast.LENGTH_SHORT).show()
                if (preferenceManager.isWakeWordEnabled) {
                    hotwordManager.start()
                }
                checkOverlayAndBatteryPermissions()
            } else {
                val deniedMsg = "Bạn đã từ chối quyền Micro. JAVIS không thể nghe bạn nói nếu không có quyền này."
                appendMessage(deniedMsg, isUser = false, tag = "CẢNH BÁO")
                speaker.speak(deniedMsg)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hotwordManager.stop()
        speaker.shutdown()
        pulseAnimator?.cancel()
    }

    private fun checkAppUpdateOnStartup() {
        lifecycleScope.launch {
            try {
                val updateManager = AppUpdateManager(this@MainActivity)
                val result = updateManager.checkForUpdate()
                result.onSuccess { updateInfo ->
                    if (updateInfo != null && !isFinishing && !isDestroyed) {
                        showStartupUpdateDialog(updateInfo)
                    }
                }
            } catch (e: Exception) {
                // Silently ignore network or GitHub connection errors on background check
            }
        }
    }

    private fun showStartupUpdateDialog(update: AppUpdateManager.UpdateInfo) {
        val sizeMb = String.format(Locale.US, "%.1f MB", update.apkSize / (1024f * 1024f))
        AlertDialog.Builder(this)
            .setTitle("🎉 Có bản cập nhật mới!")
            .setMessage("Bản dựng: ${update.releaseName} (Build #${update.remoteVersionCode})\nDung lượng: $sizeMb\n\nBạn có muốn cập nhật ứng dụng ngay bây giờ không?")
            .setPositiveButton("Cập nhật ngay") { _, _ ->
                val intent = Intent(this, SettingsActivity::class.java).apply {
                    putExtra(SettingsActivity.EXTRA_AUTO_UPDATE, true)
                }
                startActivity(intent)
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    companion object {
        const val ACTION_TRIGGER_VOICE = "com.dinh.javis.ACTION_TRIGGER_VOICE"
    }
}
