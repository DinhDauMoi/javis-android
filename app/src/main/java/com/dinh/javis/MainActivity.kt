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
import com.dinh.javis.service.FloatingBubbleService
import com.dinh.javis.settings.SettingsActivity
import com.dinh.javis.ui.ChatAdapter
import com.dinh.javis.utils.PermissionHelper
import com.dinh.javis.voice.ContinuousVoiceListener
import com.dinh.javis.voice.Speaker
import com.dinh.javis.voice.WakeWordDetector
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Màn hình chính của JAVIS
 * Sử dụng ContinuousVoiceListener để nghe liên tục, không bao giờ dừng tự động.
 * TTS tích hợp pause/resume để tránh tự nghe giọng mình.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var speaker: Speaker
    private lateinit var continuousListener: ContinuousVoiceListener
    private lateinit var commandParser: CommandParser
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var database: AppDatabase
    private var wakeWordDetector: WakeWordDetector? = null

    private var pulseAnimator: ObjectAnimator? = null

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
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatusBadges()
    }

    private fun initViews() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.layoutManager = layoutManager
        binding.rvChat.adapter = chatAdapter

        appendMessage(
            "Xin chào anh Dinh! Tôi là JAVIS. Đang lắng nghe liên tục — cứ nói tự nhiên!",
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

        // Nút Mic: toggle nghe liên tục bật/tắt
        binding.btnMic.setOnClickListener {
            if (continuousListener.isListening) {
                continuousListener.stop()
                setMicUiListening(false)
            } else {
                continuousListener.start()
            }
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

        // ContinuousVoiceListener: callback khi có kết quả, khi UI thay đổi, khi nhận partial
        continuousListener = ContinuousVoiceListener(
            context = this,
            onResult = { text ->
                runOnUiThread { handleSpokenText(text) }
            },
            onStatusChange = { isListening ->
                runOnUiThread { setMicUiListening(isListening) }
            },
            onPartialResult = { partial ->
                runOnUiThread { binding.tvVoiceStatus.text = partial }
            }
        )

        // Kết nối Speaker và ContinuousVoiceListener để mic tự pause khi TTS nói
        speaker.continuousListener = continuousListener

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

        wakeWordDetector = WakeWordDetector(this) {
            runOnUiThread { continuousListener.start() }
        }
        if (preferenceManager.isWakeWordEnabled) {
            wakeWordDetector?.start(preferenceManager.picovoiceKey)
        }

        // Bắt đầu nghe liên tục ngay khi app khởi động (nếu có quyền)
        if (PermissionHelper.hasCorePermissions(this)) {
            continuousListener.start()
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
            binding.root.postDelayed({ continuousListener.start() }, 300)
        }
    }

    private fun checkAppPermissions() {
        if (!PermissionHelper.hasCorePermissions(this)) {
            PermissionHelper.requestCorePermissions(this)
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

    private fun setMicUiListening(listening: Boolean) {
        if (listening) {
            binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.mic_listening)
            binding.tvVoiceStatus.text = getString(R.string.status_listening)
            binding.pulseRing.visibility = View.VISIBLE
            pulseAnimator?.start()
        } else {
            binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            binding.tvVoiceStatus.text = getString(R.string.status_ready)
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
        appendMessage(text, isUser = true)
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
                continuousListener.start()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousListener.stop()
        speaker.shutdown()
        wakeWordDetector?.destroy()
        pulseAnimator?.cancel()
    }

    companion object {
        const val ACTION_TRIGGER_VOICE = "com.dinh.javis.ACTION_TRIGGER_VOICE"
    }
}
