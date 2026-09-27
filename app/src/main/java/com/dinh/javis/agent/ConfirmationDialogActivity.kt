package com.dinh.javis.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Interactive fallback confirmation dialog activity (BA-01).
 * Used when overlay permissions are not granted or FloatingBubbleService is not active.
 * Presents a modal dialog requiring explicit user input (Đồng ý / Từ chối).
 */
class ConfirmationDialogActivity : AppCompatActivity() {

    private var responded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val question = intent.getStringExtra(EXTRA_QUESTION) ?: "Thao tác này cần bạn xác nhận trước khi tiếp tục."

        AlertDialog.Builder(this)
            .setTitle("⚠️ JAVIS Cần xác nhận")
            .setMessage(question)
            .setCancelable(false)
            .setPositiveButton("Đồng ý") { _, _ ->
                respond(true)
            }
            .setNegativeButton("Từ chối") { _, _ ->
                respond(false)
            }
            .setOnDismissListener {
                if (!responded) {
                    respond(false)
                }
            }
            .show()
    }

    private fun respond(approved: Boolean) {
        if (!responded) {
            responded = true
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(approved)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!responded) {
            responded = true
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(false)
        }
    }

    companion object {
        private const val EXTRA_QUESTION = "extra_confirmation_question"
        private var pendingCallback: ((Boolean) -> Unit)? = null

        fun show(context: Context, question: String, onUserResponse: (Boolean) -> Unit) {
            pendingCallback = onUserResponse
            val intent = Intent(context, ConfirmationDialogActivity::class.java).apply {
                putExtra(EXTRA_QUESTION, question)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }
}
