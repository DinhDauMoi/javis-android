package com.dinh.javis.voice

import android.content.Context
import android.util.Log

/**
 * Trình quản lý từ khóa đánh thức (Wake Word "Hey Javis")
 * Tính năng TÙY CHỌN (Optional) sử dụng công nghệ Porcupine (Picovoice).
 * Mặc định: TẮT.
 *
 * Để kích hoạt đầy đủ tính năng này với model thực tế:
 * 1. Mở file `app/build.gradle.kts` và bỏ comment dependency:
 *    `implementation("ai.picovoice:porcupine-android:3.0.0")`
 * 2. Đăng ký AccessKey miễn phí tại https://picovoice.ai/console/
 * 3. Tạo model từ khóa "Hey Javis" (.ppn file) đặt vào thư mục `assets`
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {

    private var isListening = false
    private var accessKey: String? = null

    /**
     * Khởi động lắng nghe từ khóa ngầm
     */
    fun start(picovoiceKey: String?) {
        this.accessKey = picovoiceKey
        if (picovoiceKey.isNullOrBlank()) {
            Log.i(TAG, "Chưa cấu hình Picovoice AccessKey. Chức năng Wake Word đang tắt.")
            return
        }

        try {
            // Khi người dùng thêm thư viện Porcupine, khởi tạo PorcupineManager ở đây:
            // porcupineManager = PorcupineManager.Builder()
            //     .setAccessKey(picovoiceKey)
            //     .setKeywordPath("hey_javis.ppn")
            //     .build(context) { onWakeWordDetected() }
            // porcupineManager?.start()

            isListening = true
            Log.i(TAG, "Đã bật chế độ lắng nghe từ khóa Wake Word 'Hey Javis'.")
        } catch (e: Exception) {
            Log.e(TAG, "Không thể khởi tạo Porcupine Wake Word", e)
            isListening = false
        }
    }

    /**
     * Dừng lắng nghe từ khóa
     */
    fun stop() {
        if (isListening) {
            // porcupineManager?.stop()
            isListening = false
            Log.i(TAG, "Đã dừng Wake Word Detector.")
        }
    }

    /**
     * Giải phóng tài nguyên khi onDestroy
     */
    fun destroy() {
        stop()
        // porcupineManager?.delete()
    }

    fun isRunning(): Boolean = isListening

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
