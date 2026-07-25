package com.v2ray.ang.ui
import com.anonymouskeys.monstervpn.R
import com.anonymouskeys.monstervpn.databinding.*

import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel

class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?,
    private val onUpdate: (String, Int) -> Unit,
    private val onClearProfiles: (String, Int) -> Unit
) : RecyclerView.Adapter<SubSettingRecyclerAdapter.MainViewHolder>(), ItemTouchHelperAdapter {

    override fun getItemCount() = viewModel.getAll().size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val subscriptions = viewModel.getAll()
        val subId = subscriptions[position].guid
        val subItem = subscriptions[position].subscription
        holder.itemSubSettingBinding.tvName.text = subItem.remarks
        holder.itemSubSettingBinding.tvUrl.text = subItem.url
        holder.itemSubSettingBinding.chkEnable.isChecked = subItem.enabled
        holder.itemSubSettingBinding.tvLastUpdated.text = holder.itemView.context.getString(
            R.string.dragon_group_last_updated,
            Utils.formatTimestamp(subItem.lastUpdated)
        )
        val proxyCount = com.v2ray.ang.handler.MmkvManager.decodeServerList(subId).size
        holder.itemSubSettingBinding.tvProxyCount.text = holder.itemView.context.resources.getQuantityString(
            R.plurals.dragon_group_proxy_count,
            proxyCount,
            proxyCount
        )
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)

        holder.itemSubSettingBinding.infoContainer.setOnClickListener {
            adapterListener?.onEdit(subId, position)
        }

        holder.itemSubSettingBinding.layoutUpdate.setOnClickListener {
            onUpdate(subId, position)
        }

        holder.itemSubSettingBinding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit(subId, position)
        }

        holder.itemSubSettingBinding.layoutMore.setOnClickListener { anchor ->
            PopupMenu(anchor.context, anchor).apply {
                menuInflater.inflate(R.menu.action_sub_group_item, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.group_share -> {
                            adapterListener?.onShare(subItem.url)
                            true
                        }
                        R.id.group_export_clipboard -> {
                            Utils.setClipboard(anchor.context, subItem.url)
                            true
                        }
                        R.id.group_clear_profiles -> {
                            onClearProfiles(subId, position)
                            true
                        }
                        R.id.group_delete -> {
                            adapterListener?.onRemove(subId, position)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }

        holder.itemSubSettingBinding.chkEnable.setOnCheckedChangeListener { it, isChecked ->
            if (!it.isPressed) return@setOnCheckedChangeListener
            subItem.enabled = isChecked
            viewModel.update(subId, subItem)
        }

        if (TextUtils.isEmpty(subItem.url)) {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.GONE
            holder.itemSubSettingBinding.layoutMore.visibility = View.VISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.INVISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.INVISIBLE
        } else {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutMore.visibility = View.VISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.VISIBLE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerSubSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerSubSettingBinding) :
        BaseViewHolder(itemSubSettingBinding.root), ItemTouchHelperViewHolder

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        viewModel.swap(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
    }
}
