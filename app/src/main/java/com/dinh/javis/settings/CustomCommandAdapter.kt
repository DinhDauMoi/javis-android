package com.dinh.javis.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dinh.javis.data.CustomCommand
import com.dinh.javis.databinding.ItemCustomCommandBinding

/**
 * Adapter hiển thị danh sách các lệnh tùy chỉnh trong màn hình Cài đặt
 */
class CustomCommandAdapter(
    private val onDeleteClick: (CustomCommand) -> Unit
) : RecyclerView.Adapter<CustomCommandAdapter.ViewHolder>() {

    private val commandList = mutableListOf<CustomCommand>()

    fun submitList(newList: List<CustomCommand>) {
        commandList.clear()
        commandList.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCustomCommandBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(commandList[position])
    }

    override fun getItemCount(): Int = commandList.size

    inner class ViewHolder(private val binding: ItemCustomCommandBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(cmd: CustomCommand) {
            binding.tvTriggerPhrase.text = "\"${cmd.triggerPhrase}\""

            val actionDesc = when (cmd.actionType) {
                CustomCommand.ACTION_OPEN_APP -> "Mở ứng dụng"
                CustomCommand.ACTION_SCROLL_UP -> "Vuốt lên (Nội dung tiếp)"
                CustomCommand.ACTION_SCROLL_DOWN -> "Vuốt xuống (Nội dung trước)"
                CustomCommand.ACTION_CLICK_TEXT -> "Bấm nút chứa chữ: \"${cmd.targetParam}\""
                CustomCommand.ACTION_OPEN_URL -> "Mở URL: ${cmd.targetParam}"
                else -> cmd.actionType
            }
            binding.tvActionSummary.text = "Hành động: $actionDesc"

            if (cmd.targetParam.isNotEmpty() && cmd.actionType != CustomCommand.ACTION_CLICK_TEXT) {
                binding.tvTargetParam.visibility = View.VISIBLE
                binding.tvTargetParam.text = "Tham số: ${cmd.targetParam}"
            } else {
                binding.tvTargetParam.visibility = View.GONE
            }

            binding.btnDeleteCommand.setOnClickListener {
                onDeleteClick(cmd)
            }
        }
    }
}
