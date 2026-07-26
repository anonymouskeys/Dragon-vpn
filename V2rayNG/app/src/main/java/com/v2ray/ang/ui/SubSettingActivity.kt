package com.v2ray.ang.ui
import com.anonymouskeys.monstervpn.R
import com.anonymouskeys.monstervpn.databinding.*

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SubSettingActivity : BaseActivity() {
    private val binding by lazy { ActivitySubSettingBinding.inflate(layoutInflater) }
    private val ownerActivity: SubSettingActivity
        get() = this
    private val viewModel: SubscriptionsViewModel by viewModels()
    private lateinit var adapter: SubSettingRecyclerAdapter
    private var mItemTouchHelper: ItemTouchHelper? = null
    private val share_method: Array<out String> by lazy {
        resources.getStringArray(R.array.share_sub_method)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_sub_setting))

        adapter = SubSettingRecyclerAdapter(
            viewModel,
            ActivityAdapterListener(),
            ::updateSingleSubscription,
            ::shareGroupProfiles
        )

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        binding.fabAddGroup.setOnClickListener {
            startActivity(Intent(this, SubEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_sub_setting, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_config -> {
            startActivity(Intent(this, SubEditActivity::class.java))
            true
        }

        R.id.sub_import_clipboard -> {
            importSubscriptionFromClipboard()
            true
        }

        R.id.sub_export_all -> {
            exportAllSubscriptions()
            true
        }

        R.id.sub_update -> {
            showLoading()

            lifecycleScope.launch(Dispatchers.IO) {
                val result = AngConfigManager.updateConfigViaSubAll()
                delay(500L)
                launch(Dispatchers.Main) {
                    if (result.successCount + result.failureCount + result.skipCount == 0) {
                        toast(R.string.title_update_subscription_no_subscription)
                    } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                        toast(getString(R.string.title_update_config_count, result.configCount))
                    } else {
                        toast(
                            getString(
                                R.string.title_update_subscription_result,
                                result.configCount, result.successCount, result.failureCount, result.skipCount
                            )
                        )
                    }
                    hideLoading()
                    refreshData()
                }
            }

            true
        }

        else -> super.onOptionsItemSelected(item)

    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()
        val isEmpty = viewModel.getAll().isEmpty()
        binding.emptyState.visibility = if (isEmpty) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerView.visibility = if (isEmpty) android.view.View.GONE else android.view.View.VISIBLE
    }



    private fun importSubscriptionFromClipboard() {
        val url = Utils.getClipboard(this).trim()
        if (!Utils.isValidUrl(url)) {
            toast(R.string.dragon_group_clipboard_no_url)
            return
        }
        startActivity(
            Intent(this, SubEditActivity::class.java)
                .putExtra("prefillUrl", url)
        )
    }

    private fun exportAllSubscriptions() {
        val content = viewModel.getAll()
            .mapNotNull { cache ->
                val item = cache.subscription
                item.url.takeIf { it.isNotBlank() }?.let { "${item.remarks}=$it" }
            }
            .joinToString("\n")
        if (content.isBlank()) {
            toast(R.string.title_update_subscription_no_subscription)
            return
        }
        Utils.setClipboard(this, content)
        toast(R.string.dragon_group_exported)
    }

    private fun shareGroupProfiles(subId: String) {
        val content = MmkvManager.decodeServerList(subId)
            .asSequence()
            .map(AngConfigManager::shareConfig)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        if (content.isBlank()) {
            toast(R.string.dragon_group_no_profiles_to_share)
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.dragon_group_share_profiles)))
    }

    private fun clearGroupProfiles(subId: String, position: Int) {
        AlertDialog.Builder(this)
            .setMessage(R.string.dragon_group_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MmkvManager.removeServerViaSubid(subId)
                adapter.notifyItemChanged(position)
                toast(R.string.dragon_group_cleared)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSingleSubscription(subId: String, position: Int) {
        val subscription = viewModel.getAll().firstOrNull { it.guid == subId } ?: return
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.updateConfigViaSub(subscription)
            launch(Dispatchers.Main) {
                hideLoading()
                if (result.successCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                }
                refreshData()
                adapter.notifyItemChanged(position)
            }
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, SubEditActivity::class.java)
                    .putExtra("subId", guid)
            )
        }

        override fun onRemove(guid: String, position: Int) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
                AlertDialog.Builder(ownerActivity)
                    .setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.remove(guid)
                        refreshData()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                viewModel.remove(guid)
                refreshData()
            }
        }

        override fun onShare(url: String) {
            AlertDialog.Builder(ownerActivity)
                .setItems(share_method.asList().toTypedArray()) { _, i ->
                    try {
                        when (i) {
                            0 -> {
                                val ivBinding =
                                    ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
                                ivBinding.ivQcode.setImageBitmap(
                                    QRCodeDecoder.createQRCode(
                                        url

                                    )
                                )
                                AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
                            }

                            1 -> {
                                Utils.setClipboard(ownerActivity, url)
                            }

                            else -> ownerActivity.toast("else")
                        }
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Share subscription failed", e)
                    }
                }.show()
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
