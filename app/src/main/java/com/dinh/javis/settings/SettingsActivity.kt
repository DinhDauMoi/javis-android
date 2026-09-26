package com.dinh.javis.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
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

        binding.btnSaveAiConfig.setOnClickListener {
            val url = binding.etBaseUrl.text?.toString()?.trim() ?: ""
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            val model = binding.etModelName.text?.toString()?.trim() ?: "gpt-4o-mini"

            preferenceManager.openAiBaseUrl = if (url.isNotEmpty()) url else "https://api.openai.com/v1"
            preferenceManager.openAiApiKey = key
            preferenceManager.openAiModel = if (model.isNotEmpty()) model else "gpt-4o-mini"

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }

        binding.btnTestAiConnection.setOnClickListener {
            val url = binding.etBaseUrl.text?.toString()?.trim() ?: "https://api.openai.com/v1"
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            val model = binding.etModelName.text?.toString()?.trim() ?: "gpt-4o-mini"

            binding.btnTestAiConnection.isEnabled = false
            binding.btnTestAiConnection.text = "⏳ Đang kiểm tra kết nối..."
            binding.tvConnectionResult.visibility = View.VISIBLE
            binding.tvConnectionResult.text = "Đang gửi yêu cầu thử nghiệm..."
            binding.tvConnectionResult.setTextColor(getColor(R.color.text_secondary))

            lifecycleScope.launch {
                val client = OpenAiCompatibleClient(url, key, model, model, model)
                val testResult = client.testConnection(url, key, model)

                binding.btnTestAiConnection.isEnabled = true
                binding.btnTestAiConnection.text = "⚡ KIỂM TRA KẾT NỐI AI"

                testResult.onSuccess { pair ->
                    binding.tvConnectionResult.text = "✅ ${pair.second}"
                    binding.tvConnectionResult.setTextColor(getColor(R.color.status_green))
                }.onFailure { err ->
                    binding.tvConnectionResult.text = "❌ ${err.message}"
                    binding.tvConnectionResult.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
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
                .setTitle("Xóa toàn bộ lịch sử tác vụ")
                .setMessage("Bạn có chắc chắn muốn xóa toàn bộ lịch sử các tác vụ tự động hóa và nhật ký hành động không?")
                .setPositiveButton("Xóa toàn bộ") { _, _ ->
                    lifecycleScope.launch {
                        BehaviorAggregator(this@SettingsActivity).clearAllStats()
                        Toast.makeText(this@SettingsActivity, "Đã xóa toàn bộ lịch sử tác vụ thành công!", Toast.LENGTH_SHORT).show()
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
