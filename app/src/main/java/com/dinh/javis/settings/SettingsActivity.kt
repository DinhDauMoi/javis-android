package com.dinh.javis.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dinh.javis.R
import com.dinh.javis.agent.BehaviorAggregator
import com.dinh.javis.agent.PolicyGuard
import com.dinh.javis.ai.OpenAiCompatibleClient
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.CustomCommand
import com.dinh.javis.data.PolicyRule
import com.dinh.javis.data.PreferenceManager
import com.dinh.javis.data.TaskRun
import com.dinh.javis.databinding.ActivitySettingsBinding
import com.dinh.javis.databinding.DialogAddCustomCommandBinding
import com.dinh.javis.service.FloatingBubbleService
import com.dinh.javis.utils.AppUpdateManager
import com.dinh.javis.utils.PermissionHelper
import com.dinh.javis.utils.TextNormalizer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình Cài đặt của JAVIS
 * Cho phép cấu hình OpenAI/BYOK endpoints, kiểm tra kết nối AI, chế độ Thị giác & Behavior Agent,
 * từ khóa đánh thức, nút mic nổi, quản lý lệnh tùy chỉnh và cập nhật ứng dụng.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var database: AppDatabase
    private lateinit var commandAdapter: CustomCommandAdapter
    private lateinit var updateManager: AppUpdateManager
    private var downloadedApkFile: File? = null

    companion object {
        const val EXTRA_AUTO_UPDATE = "extra_auto_update"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        database = AppDatabase.getDatabase(this)
        updateManager = AppUpdateManager(this)

        setupToolbar()
        loadAiSettings()
        setupVisionAndAgentSection()
        setupWakeWordSection()
        setupFloatingMicSection()
        setupCustomCommandsRecycler()
        setupUpdateSection()

        if (intent.getBooleanExtra(EXTRA_AUTO_UPDATE, false)) {
            checkAppUpdate(isManual = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val apk = downloadedApkFile
            if (apk != null && apk.exists() && packageManager.canRequestPackageInstalls()) {
                binding.btnInstallUpdate.visibility = View.VISIBLE
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadAiSettings() {
        binding.etBaseUrl.setText(preferenceManager.openAiBaseUrl)
        binding.etApiKey.setText(preferenceManager.openAiApiKey)
        binding.etModelName.setText(preferenceManager.openAiModel)

        val updateApiKeyHelperNote = {
            val typedKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            val activeKey = typedKey.ifBlank { preferenceManager.openAiApiKey }
            if (activeKey.isNotBlank()) {
                val masked = if (activeKey.length > 6) "...${activeKey.takeLast(4)}" else "***"
                binding.tilApiKey.helperText = "🔑 API Key: ĐÃ NẠP (${activeKey.length} ký tự | $masked) - Sẵn sàng gửi trong Header"
                binding.tilApiKey.setHelperTextColor(ColorStateList.valueOf(getColor(R.color.status_green)))
            } else {
                binding.tilApiKey.helperText = "⚠️ API Key: ĐANG ĐỂ TRỐNG - Yêu cầu sẽ gửi không có khóa xác thực (Nặc danh)"
                binding.tilApiKey.setHelperTextColor(ColorStateList.valueOf(getColor(android.R.color.holo_orange_light)))
            }
        }

        updateApiKeyHelperNote()

        binding.etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateApiKeyHelperNote()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnPresetProvider.setOnClickListener {
            showPresetProviderDialog()
        }

        binding.btnSaveAiConfig.setOnClickListener {
            val url = binding.etBaseUrl.text?.toString()?.trim() ?: ""
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            val model = binding.etModelName.text?.toString()?.trim() ?: "gpt-4o-mini"

            val finalUrl = if (url.isNotEmpty()) url else "https://api.openai.com/v1"
            val finalModel = if (model.isNotEmpty()) model else "gpt-4o-mini"

            preferenceManager.openAiBaseUrl = finalUrl
            preferenceManager.openAiModel = finalModel
            if (key.isNotBlank()) {
                preferenceManager.openAiApiKey = key
            }
            val activeKey = key.ifBlank { preferenceManager.openAiApiKey }

            lifecycleScope.launch {
                val activeProfile = database.aiModelProfileDao().getActiveProfile()
                if (activeProfile != null) {
                    val alias = activeProfile.secretKeyAlias.ifBlank { "default_key_alias" }
                    if (activeKey.isNotBlank()) {
                        com.dinh.javis.security.KeystoreManager(this@SettingsActivity).encrypt(alias, activeKey)
                    }
                    val updatedProfile = activeProfile.copy(
                        baseUrl = finalUrl,
                        chatModelId = finalModel,
                        visionModelId = finalModel,
                        planningModelId = finalModel
                    )
                    database.aiModelProfileDao().insertProfile(updatedProfile)
                }
                updateApiKeyHelperNote()
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestAiConnection.setOnClickListener {
            val urlInput = binding.etBaseUrl.text?.toString()?.trim() ?: ""
            val keyInput = binding.etApiKey.text?.toString()?.trim() ?: ""
            val modelInput = binding.etModelName.text?.toString()?.trim() ?: ""

            val url = urlInput.ifBlank { preferenceManager.openAiBaseUrl }
            val model = modelInput.ifBlank { preferenceManager.openAiModel }

            // Auto-persist new key typed in etApiKey if non-empty
            if (keyInput.isNotBlank()) {
                preferenceManager.openAiApiKey = keyInput
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val activeProfile = database.aiModelProfileDao().getActiveProfile()
                        if (activeProfile != null) {
                            val alias = activeProfile.secretKeyAlias.ifBlank { "default_key_alias" }
                            com.dinh.javis.security.KeystoreManager(this@SettingsActivity).encrypt(alias, keyInput)
                        }
                    } catch (_: Exception) {}
                }
            }
            val key = keyInput.ifBlank { preferenceManager.openAiApiKey }

            android.util.Log.i("JAVIS_TEST", "=== BUTTON TEST AI CONNECTION CLICKED ===")
            android.util.Log.i("JAVIS_TEST", "URL: $url | Model: $model | Key Length: ${key.length}")

            val keyNotice = if (key.isNotBlank()) {
                val maskedKey = if (key.length > 6) "...${key.takeLast(4)}" else "***"
                "🔑 API Key: ĐÃ NẠP (${key.length} ký tự | $maskedKey)"
            } else {
                "⚠️ API Key: ĐANG ĐỂ TRỐNG (Gửi dưới dạng Nặc danh/Anonymous)"
            }

            binding.btnTestAiConnection.isEnabled = false
            binding.btnTestAiConnection.text = "⏳ Đang kiểm tra kết nối..."
            binding.tvConnectionResult.visibility = View.VISIBLE
            binding.tvConnectionResult.text = "$keyNotice\n⏳ Đang gửi yêu cầu tới máy chủ AI..."
            binding.tvConnectionResult.setTextColor(getColor(R.color.text_secondary))

            lifecycleScope.launch {
                val client = OpenAiCompatibleClient(url, key, model, model, model)
                val testResult = client.testConnection(url, key, model)

                binding.btnTestAiConnection.isEnabled = true
                binding.btnTestAiConnection.text = "⚡ KIỂM TRA KẾT NỐI AI"
                updateApiKeyHelperNote()

                testResult.onSuccess { pair ->
                    android.util.Log.i("JAVIS_TEST", "Test Result SUCCESS: ${pair.second}")
                    binding.tvConnectionResult.text = "$keyNotice\n✅ ${pair.second}"
                    binding.tvConnectionResult.setTextColor(getColor(R.color.status_green))
                }.onFailure { err ->
                    android.util.Log.e("JAVIS_TEST", "Test Result FAILURE: ${err.message}", err)
                    binding.tvConnectionResult.text = "$keyNotice\n❌ ${err.message}"
                    binding.tvConnectionResult.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }

    private fun showPresetProviderDialog() {
        val presets = arrayOf(
            "GenroStore Gateway (Mistral Pixtral 12B)\nURL: https://gateway.genrostore.com/v1 | Model: mistral/pixtral-12b-2409",
            "Mistral AI Trực tiếp (Pixtral 12B - Vision & OCR)\nURL: https://api.mistral.ai/v1 | Model: pixtral-12b-2409",
            "OpenAI (GPT-4o mini - Vision & Chat)\nURL: https://api.openai.com/v1 | Model: gpt-4o-mini",
            "Mistral AI (Pixtral Large - Thị giác cao cấp)\nURL: https://api.mistral.ai/v1 | Model: pixtral-large-latest",
            "Groq Vision (Llama 3.2 11B Vision - Tốc độ cao)\nURL: https://api.groq.com/openai/v1 | Model: llama-3.2-11b-vision-preview",
            "Ollama Cục bộ (LLaVA - Mạng nội bộ/Offline)\nURL: http://192.168.1.100:11434/v1 | Model: llava"
        )

        AlertDialog.Builder(this)
            .setTitle("Chọn mẫu cấu hình nhà cung cấp AI")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> {
                        binding.etBaseUrl.setText("https://gateway.genrostore.com/v1")
                        binding.etModelName.setText("mistral/pixtral-12b-2409")
                        Toast.makeText(this, "Đã chọn GenroStore Mistral Pixtral. Hãy nhập API Key và lưu!", Toast.LENGTH_LONG).show()
                    }
                    1 -> {
                        binding.etBaseUrl.setText("https://api.mistral.ai/v1")
                        binding.etModelName.setText("pixtral-12b-2409")
                        Toast.makeText(this, "Đã chọn Mistral AI Trực tiếp. Hãy nhập Mistral API Key và lưu!", Toast.LENGTH_LONG).show()
                    }
                    2 -> {
                        binding.etBaseUrl.setText("https://api.openai.com/v1")
                        binding.etModelName.setText("gpt-4o-mini")
                        Toast.makeText(this, "Đã chọn OpenAI GPT-4o mini", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        binding.etBaseUrl.setText("https://api.mistral.ai/v1")
                        binding.etModelName.setText("pixtral-large-latest")
                        Toast.makeText(this, "Đã chọn Mistral Pixtral Large", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        binding.etBaseUrl.setText("https://api.groq.com/openai/v1")
                        binding.etModelName.setText("llama-3.2-11b-vision-preview")
                        Toast.makeText(this, "Đã chọn Groq Vision", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        binding.etBaseUrl.setText("http://192.168.1.100:11434/v1")
                        binding.etModelName.setText("llava")
                        Toast.makeText(this, "Đã chọn Ollama Cục bộ", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun setupVisionAndAgentSection() {
        binding.switchVisionMode.isChecked = preferenceManager.isVisionEnabled
        binding.switchVisionMode.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.isVisionEnabled = isChecked
        }

        binding.switchBehaviorAgent.isChecked = preferenceManager.isBehaviorAgentEnabled
        binding.switchBehaviorAgent.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.isBehaviorAgentEnabled = isChecked
        }

        binding.switchBehaviorAnalytics.isChecked = preferenceManager.isBehaviorAnalyticsEnabled
        binding.switchBehaviorAnalytics.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.isBehaviorAnalyticsEnabled = isChecked
        }

        val initialSteps = preferenceManager.agentMaxSteps
        binding.seekAgentMaxSteps.progress = (initialSteps - 3).coerceIn(0, 12)
        binding.tvAgentMaxStepsValue.text = "$initialSteps bước"

        binding.seekAgentMaxSteps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val steps = progress + 3
                binding.tvAgentMaxStepsValue.text = "$steps bước"
                if (fromUser) {
                    preferenceManager.agentMaxSteps = steps
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnManageGuardrails.setOnClickListener {
            showGuardrailsDialog()
        }

        binding.btnViewTaskHistory.setOnClickListener {
            showTaskHistoryDialog()
        }

        binding.btnClearAllHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa lịch sử tác vụ")
                .setMessage("Xóa lịch sử tác vụ và nhật ký hành động (7 ngày qua). Thống kê hành vi và lệnh tùy chỉnh của bạn sẽ không bị ảnh hưởng.")
                .setPositiveButton("Xóa lịch sử") { _, _ ->
                    lifecycleScope.launch {
                        BehaviorAggregator(this@SettingsActivity).clearTaskHistory()
                        Toast.makeText(this@SettingsActivity, "Đã xóa lịch sử tác vụ thành công!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    private fun showGuardrailsDialog() {
        lifecycleScope.launch {
            val rules = database.policyRuleDao().getAllRules()
            if (rules.isEmpty()) {
                PolicyGuard(this@SettingsActivity).initDefaultRulesIfEmpty()
            }
            val currentRules = database.policyRuleDao().getAllRules()
            val ruleItems = currentRules.map { rule ->
                val badge = when (rule.policy) {
                    PolicyRule.POLICY_DENY -> "🔴 CHẶN"
                    PolicyRule.POLICY_REQUIRE_CONFIRM -> "🟡 HỎI TRƯỚC"
                    else -> "🟢 CHO PHÉP"
                }
                "$badge | ${rule.packageName}\n(${rule.notes ?: "Không có ghi chú"})"
            }.toTypedArray()

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("🛡️ Quy tắc bảo vệ ứng dụng (Guardrails)")
                .setItems(ruleItems) { _, which ->
                    val selected = currentRules[which]
                    showEditRuleDialog(selected)
                }
                .setPositiveButton("Đóng", null)
                .show()
        }
    }

    private fun showEditRuleDialog(rule: PolicyRule) {
        val policies = arrayOf("🟢 Cho phép (ALLOW)", "🟡 Hỏi xác nhận (REQUIRE_CONFIRM)", "🔴 Chặn tuyệt đối (DENY)")
        val initialIndex = when (rule.policy) {
            PolicyRule.POLICY_REQUIRE_CONFIRM -> 1
            PolicyRule.POLICY_DENY -> 2
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("Cập nhật quy tắc cho:\n${rule.packageName}")
            .setSingleChoiceItems(policies, initialIndex) { dialog, which ->
                val newPolicy = when (which) {
                    1 -> PolicyRule.POLICY_REQUIRE_CONFIRM
                    2 -> PolicyRule.POLICY_DENY
                    else -> PolicyRule.POLICY_ALLOW
                }
                lifecycleScope.launch {
                    database.policyRuleDao().insertRule(rule.copy(policy = newPolicy))
                    Toast.makeText(this@SettingsActivity, "Đã cập nhật quy tắc cho ${rule.packageName}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showTaskHistoryDialog() {
        lifecycleScope.launch {
            val runs = database.taskRunDao().getAllRuns()
            if (runs.isEmpty()) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("📜 Lịch sử tác vụ (Task History)")
                    .setMessage("Chưa có tác vụ tự động nào được ghi nhận.")
                    .setPositiveButton("Đóng", null)
                    .show()
                return@launch
            }

            val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            val items = runs.take(20).map { run ->
                val timeStr = dateFormat.format(Date(run.startTime))
                val statusBadge = when (run.status) {
                    TaskRun.STATUS_SUCCESS -> "✅ Thành công"
                    TaskRun.STATUS_CANCELLED -> "⏹️ Đã hủy"
                    else -> "❌ Thất bại"
                }
                "[$timeStr] $statusBadge (${run.stepCount} bước)\nMục tiêu: ${run.taskGoal}"
            }.toTypedArray()

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("📜 Lịch sử tác vụ gần đây")
                .setItems(items) { _, which ->
                    val selectedRun = runs[which]
                    showTaskRunDetailDialog(selectedRun)
                }
                .setPositiveButton("Đóng", null)
                .show()
        }
    }

    private fun showTaskRunDetailDialog(run: TaskRun) {
        lifecycleScope.launch {
            val logs = database.actionLogDao().getLogsForRun(run.runId)
            val builder = StringBuilder()
            builder.append("Mục tiêu: ").append(run.taskGoal).append("\n")
            builder.append("Trạng thái: ").append(run.status).append("\n")
            builder.append("Số bước thực hiện: ").append(run.stepCount).append("\n")
            if (!run.failureReason.isNullOrBlank()) {
                builder.append("Nguyên nhân/Kết quả: ").append(run.failureReason).append("\n")
            }
            builder.append("\nChi tiết các hành động:\n")
            if (logs.isEmpty()) {
                builder.append("(Không có chi tiết hành động)\n")
            } else {
                for ((idx, log) in logs.withIndex()) {
                    builder.append("${idx + 1}. [${log.actionType}] ${log.sanitizedDetails} (App: ${log.targetPackage})\n")
                }
            }

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Chi tiết phiên tác vụ")
                .setMessage(builder.toString())
                .setPositiveButton("Đóng", null)
                .show()
        }
    }

    private fun setupWakeWordSection() {
        binding.switchWakeWord.isChecked = preferenceManager.isWakeWordEnabled
        binding.tilPicovoiceKey.visibility = View.GONE

        binding.switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.isWakeWordEnabled = isChecked
        }

        // Cấu hình thanh trượt độ nhạy Wake Word (0.10 - 0.70)
        val currentThreshold = preferenceManager.wakeWordThreshold
        val initialProgress = ((currentThreshold - 0.10f) * 100).toInt().coerceIn(0, 60)
        binding.seekWakeWordThreshold.progress = initialProgress
        binding.tvThresholdValue.text = String.format(Locale.US, "%.2f", currentThreshold)

        binding.seekWakeWordThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = 0.10f + (progress / 100f)
                binding.tvThresholdValue.text = String.format(Locale.US, "%.2f", threshold)
                if (fromUser) {
                    preferenceManager.wakeWordThreshold = threshold
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalThreshold = 0.10f + ((seekBar?.progress ?: 20) / 100f)
                preferenceManager.wakeWordThreshold = finalThreshold
                Toast.makeText(this@SettingsActivity, "Đã lưu độ nhạy: ${String.format(Locale.US, "%.2f", finalThreshold)}", Toast.LENGTH_SHORT).show()
            }
        })

        // Bật/tắt viền sáng màn hình khi gọi AI
        binding.switchGlowOverlay.isChecked = preferenceManager.isGlowOverlayEnabled
        binding.switchGlowOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !PermissionHelper.canDrawOverlays(this)) {
                Toast.makeText(this, "Vui lòng cấp quyền 'Vẽ lên trên ứng dụng khác' để hiển thị viền sáng", Toast.LENGTH_LONG).show()
                PermissionHelper.requestOverlayPermission(this)
                binding.switchGlowOverlay.isChecked = false
                return@setOnCheckedChangeListener
            }
            preferenceManager.isGlowOverlayEnabled = isChecked
        }

        // Nút hướng dẫn tối ưu chạy nền ColorOS / OPPO
        binding.btnBatteryOptimization.setOnClickListener {
            showBatteryOptimizationGuide()
        }
    }

    private fun showBatteryOptimizationGuide() {
        AlertDialog.Builder(this)
            .setTitle("Tối ưu chạy nền (ColorOS / OPPO)")
            .setMessage(
                "Để JAVIS luôn nhận diện 'javis' tức thì khi đang xem TikTok hoặc tắt màn hình:\n\n" +
                "1. Tắt tối ưu hóa pin (bấm nút bên dưới).\n" +
                "2. Khóa ứng dụng trong màn hình Đa nhiệm (vuốt mở đa nhiệm → kéo app JAVIS xuống hoặc bấm ⋮ → chọn Khóa 🔒).\n" +
                "3. Bật 'Cho phép tự khởi chạy' và 'Cho phép chạy ngầm' trong Cài đặt pin của máy."
            )
            .setPositiveButton("Tắt tối ưu pin") { _, _ ->
                PermissionHelper.requestIgnoreBatteryOptimizations(this)
            }
            .setNeutralButton("Cài đặt ứng dụng") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Đã hiểu", null)
            .show()
    }

    private fun setupFloatingMicSection() {
        binding.switchFloating.isChecked = preferenceManager.isFloatingMicEnabled

        binding.switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!PermissionHelper.canDrawOverlays(this)) {
                    Toast.makeText(this, "Vui lòng cấp quyền vẽ lên ứng dụng khác để bật nút nổi", Toast.LENGTH_LONG).show()
                    PermissionHelper.requestOverlayPermission(this)
                    binding.switchFloating.isChecked = false
                    return@setOnCheckedChangeListener
                }
                preferenceManager.isFloatingMicEnabled = true
                startService(Intent(this, FloatingBubbleService::class.java))
            } else {
                preferenceManager.isFloatingMicEnabled = false
                stopService(Intent(this, FloatingBubbleService::class.java))
            }
        }
    }

    private fun setupCustomCommandsRecycler() {
        commandAdapter = CustomCommandAdapter { commandToDelete ->
            lifecycleScope.launch {
                database.customCommandDao().delete(commandToDelete)
                Toast.makeText(this@SettingsActivity, "Đã xóa lệnh \"${commandToDelete.triggerPhrase}\"", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rvCustomCommands.layoutManager = LinearLayoutManager(this)
        binding.rvCustomCommands.adapter = commandAdapter

        lifecycleScope.launch {
            database.customCommandDao().getAllAsFlow().collectLatest { list ->
                commandAdapter.submitList(list)
                if (list.isEmpty()) {
                    binding.tvEmptyCommands.visibility = View.VISIBLE
                    binding.rvCustomCommands.visibility = View.GONE
                } else {
                    binding.tvEmptyCommands.visibility = View.GONE
                    binding.rvCustomCommands.visibility = View.VISIBLE
                }
            }
        }

        binding.btnAddCommand.setOnClickListener {
            showAddCommandDialog()
        }
    }

    private fun showAddCommandDialog() {
        val dialogBinding = DialogAddCustomCommandBinding.inflate(layoutInflater)

        val actionOptions = listOf(
            "Mở ứng dụng (Nhập tên app hoặc package)" to CustomCommand.ACTION_OPEN_APP,
            "Lướt lên (Video tiếp theo)" to CustomCommand.ACTION_SCROLL_UP,
            "Lướt xuống (Video trước đó)" to CustomCommand.ACTION_SCROLL_DOWN,
            "Bấm nút có chữ (Nhập text trên nút)" to CustomCommand.ACTION_CLICK_TEXT,
            "Mở trang web (Nhập đường dẫn URL)" to CustomCommand.ACTION_OPEN_URL
        )

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            actionOptions.map { it.first }
        )
        dialogBinding.spinnerActionType.adapter = spinnerAdapter

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Thêm") { _, _ ->
                val phrase = dialogBinding.etDialogPhrase.text?.toString()?.trim() ?: ""
                val param = dialogBinding.etDialogParam.text?.toString()?.trim() ?: ""
                val selectedIndex = dialogBinding.spinnerActionType.selectedItemPosition
                val actionType = actionOptions[selectedIndex].second

                if (phrase.isBlank()) {
                    Toast.makeText(this, "Vui lòng nhập câu lệnh bạn muốn nói!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val normalized = TextNormalizer.removeAccents(phrase)
                val newCommand = CustomCommand(
                    triggerPhrase = phrase,
                    normalizedPhrase = normalized,
                    actionType = actionType,
                    targetParam = param
                )

                lifecycleScope.launch {
                    database.customCommandDao().insert(newCommand)
                    Toast.makeText(this@SettingsActivity, "Đã thêm lệnh mới thành công!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun setupUpdateSection() {
        val currentCode = updateManager.getCurrentVersionCode()
        val currentName = updateManager.getCurrentVersionName()
        binding.tvCurrentVersion.text = "Phiên bản hiện tại: v$currentName (Build #$currentCode)"

        binding.btnCheckUpdate.setOnClickListener {
            checkAppUpdate(isManual = true)
        }

        binding.btnInstallUpdate.setOnClickListener {
            downloadedApkFile?.let { file ->
                updateManager.installApk(this, file)
            }
        }
    }

    private fun checkAppUpdate(isManual: Boolean) {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "⏳ Đang kiểm tra..."

        lifecycleScope.launch {
            val result = updateManager.checkForUpdate()
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "🔄 KIỂM TRA CẬP NHẬT"

            result.onSuccess { updateInfo ->
                if (updateInfo != null) {
                    showUpdateAvailableDialog(updateInfo)
                } else {
                    if (isManual) {
                        val currentCode = updateManager.getCurrentVersionCode()
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Đã là bản mới nhất")
                            .setMessage("Ứng dụng JAVIS của bạn đang ở phiên bản mới nhất (Build #$currentCode).")
                            .setPositiveButton("Đóng", null)
                            .show()
                    }
                }
            }.onFailure { error ->
                if (isManual) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Kiểm tra cập nhật")
                        .setMessage("Không thể kiểm tra bản mới: ${error.message}")
                        .setPositiveButton("Đóng", null)
                        .show()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(update: AppUpdateManager.UpdateInfo) {
        val sizeMb = String.format(Locale.US, "%.1f MB", update.apkSize / (1024f * 1024f))
        val notes = if (update.releaseNotes.isNotBlank()) "\n\nNội dung mới:\n${update.releaseNotes}" else ""

        AlertDialog.Builder(this)
            .setTitle("🎉 Có bản cập nhật mới!")
            .setMessage("Bản dựng: ${update.releaseName} (Build #${update.remoteVersionCode})\nDung lượng: $sizeMb$notes\n\nBạn có muốn tải về và cài đặt ngay không?")
            .setPositiveButton("Tải & Cài đặt") { _, _ ->
                startDownloadApk(update)
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun startDownloadApk(update: AppUpdateManager.UpdateInfo) {
        binding.layoutUpdateProgress.visibility = View.VISIBLE
        binding.progressBarUpdate.progress = 0
        binding.tvUpdateProgress.text = "Đang chuẩn bị tải..."
        binding.btnCheckUpdate.isEnabled = false
        binding.btnInstallUpdate.visibility = View.GONE

        lifecycleScope.launch {
            val result = updateManager.downloadApk(update.apkDownloadUrl) { percent, downloaded, total ->
                binding.progressBarUpdate.progress = percent
                val downMb = String.format(Locale.US, "%.1f", downloaded / (1024f * 1024f))
                val totalMb = String.format(Locale.US, "%.1f", total / (1024f * 1024f))
                binding.tvUpdateProgress.text = "Đang tải: $percent% ($downMb MB / $totalMb MB)"
            }

            binding.btnCheckUpdate.isEnabled = true

            result.onSuccess { apkFile ->
                downloadedApkFile = apkFile
                binding.tvUpdateProgress.text = "✅ Đã tải xong! Sẵn sàng cài đặt."
                binding.btnInstallUpdate.visibility = View.VISIBLE
                Toast.makeText(this@SettingsActivity, "Tải bản cập nhật thành công! Đang mở cài đặt...", Toast.LENGTH_SHORT).show()
                updateManager.installApk(this@SettingsActivity, apkFile)
            }.onFailure { err ->
                binding.layoutUpdateProgress.visibility = View.GONE
                Toast.makeText(this@SettingsActivity, "Tải bản cập nhật thất bại: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
