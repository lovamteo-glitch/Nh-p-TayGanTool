package com.taygan.tool.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.taygan.tool.R
import com.taygan.tool.model.BadgeType
import com.taygan.tool.model.VoucherItem

class VoucherAdapter(
    private val voucherList: MutableList<VoucherItem>,
    private val onItemSelected: (VoucherItem) -> Unit
) : RecyclerView.Adapter<VoucherAdapter.VoucherViewHolder>() {

    inner class VoucherViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val itemRoot: View = itemView.findViewById(R.id.itemRoot)
        val tvBadgeVip: TextView = itemView.findViewById(R.id.tvBadgeVip)
        val ivBadgeIcon: ImageView = itemView.findViewById(R.id.ivBadgeIcon)
        val tvVoucherTitle: TextView = itemView.findViewById(R.id.tvVoucherTitle)
        val ivSelectedCheck: ImageView = itemView.findViewById(R.id.ivSelectedCheck)

        fun bind(item: VoucherItem) {
            tvVoucherTitle.text = item.code

            // Render Badge / Platform icon
            when (item.badgeType) {
                BadgeType.VIP, BadgeType.CUSTOM -> {
                    tvBadgeVip.visibility = View.VISIBLE
                    ivBadgeIcon.visibility = View.GONE
                    tvBadgeVip.text = if (item.isApiClaimable) "API" else "VIP"
                }
                BadgeType.INSTAGRAM -> {
                    tvBadgeVip.visibility = View.GONE
                    ivBadgeIcon.visibility = View.VISIBLE
                    ivBadgeIcon.setImageResource(R.drawable.ic_instagram)
                }
                BadgeType.FACEBOOK -> {
                    tvBadgeVip.visibility = View.GONE
                    ivBadgeIcon.visibility = View.VISIBLE
                    ivBadgeIcon.setImageResource(R.drawable.ic_facebook)
                }
            }

            // Selection state
            if (item.isSelected) {
                ivSelectedCheck.visibility = View.VISIBLE
                itemRoot.isSelected = true
            } else {
                ivSelectedCheck.visibility = View.GONE
                itemRoot.isSelected = false
            }

            itemRoot.setOnClickListener {
                // Single selection mode
                voucherList.forEach { it.isSelected = false }
                item.isSelected = true
                notifyDataSetChanged()
                onItemSelected(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoucherViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_voucher, parent, false)
        return VoucherViewHolder(view)
    }

    override fun onBindViewHolder(holder: VoucherViewHolder, position: Int) {
        holder.bind(voucherList[position])
    }

    override fun getItemCount(): Int = voucherList.size

    fun getSelectedItem(): VoucherItem? {
        return voucherList.firstOrNull { it.isSelected } ?: voucherList.firstOrNull()
    }

    fun addItem(item: VoucherItem) {
        voucherList.add(item)
        notifyItemInserted(voucherList.size - 1)
    }
}
