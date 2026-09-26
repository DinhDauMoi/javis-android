package com.dinh.javis.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dinh.javis.R
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.CustomCommand
import com.dinh.javis.data.PreferenceManager
import com.dinh.javis.databinding.ActivitySettingsBinding
import com.dinh.javis.databinding.DialogAddCustomCommandBinding
import com.dinh.javis.service.FloatingBubbleService
import com.dinh.javis.utils.PermissionHelper
import com.dinh.javis.utils.TextNormalizer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import java.io.File
import com.dinh.javis.utils.AppUpdateManager

/**
 * Màn hình Cài đặt của JAVIS
 * Cho phép cấu hình OpenAI API, từ khóa đánh thức, nút mic nổi, quản lý lệnh tùy chỉnh và cập nhật ứng dụng
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

        // Quan sát danh sách lệnh tùy biến từ Room DB
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
                // Tự động mở màn hình cài đặt của hệ thống
                updateManager.installApk(this@SettingsActivity, apkFile)
            }.onFailure { err ->
                binding.layoutUpdateProgress.visibility = View.GONE
                Toast.makeText(this@SettingsActivity, "Tải bản cập nhật thất bại: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

