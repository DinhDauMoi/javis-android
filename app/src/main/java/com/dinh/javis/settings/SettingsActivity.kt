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

/**
 * Màn hình Cài đặt của JAVIS
 * Cho phép cấu hình OpenAI API, từ khóa đánh thức, nút mic nổi và quản lý lệnh tùy chỉnh
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var database: AppDatabase
    private lateinit var commandAdapter: CustomCommandAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        database = AppDatabase.getDatabase(this)

        setupToolbar()
        loadAiSettings()
        setupWakeWordSection()
        setupFloatingMicSection()
        setupCustomCommandsRecycler()
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
}
