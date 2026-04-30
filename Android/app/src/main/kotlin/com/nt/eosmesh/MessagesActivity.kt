package com.nt.eosmesh

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.nt.eosmesh.databinding.ActivityMessagesBinding
import com.nt.eosmesh.databinding.LayoutTabRequestsBinding
import com.nt.eosmesh.databinding.LayoutTabVerifyBinding
import com.nt.eosmesh.model.ApiResult
import com.nt.eosmesh.utils.ApiClient
import com.nt.eosmesh.utils.SecureStorage
import kotlinx.coroutines.launch

class MessagesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessagesBinding
    private var verifyTabBinding: LayoutTabVerifyBinding? = null
    private var requestsTabBinding: LayoutTabRequestsBinding? = null
    private val adapter = FriendRequestAdapter { requestId -> acceptRequest(requestId) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 返回按钮
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 添加两个 Tab
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("好友验证方式"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("好友申请列表"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showVerifyTab()
                    1 -> showRequestsTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // 默认选中第一个
        showVerifyTab()
    }

    private fun showVerifyTab() {
        if (verifyTabBinding == null) {
            verifyTabBinding = LayoutTabVerifyBinding.inflate(layoutInflater, binding.contentFrame, false)
            binding.contentFrame.addView(verifyTabBinding!!.root)
            setupVerifyListeners()
        }
        verifyTabBinding!!.root.visibility = View.VISIBLE
        requestsTabBinding?.root?.visibility = View.GONE
        loadVerifySetting()
    }

    private fun showRequestsTab() {
        if (requestsTabBinding == null) {
            requestsTabBinding = LayoutTabRequestsBinding.inflate(layoutInflater, binding.contentFrame, false)
            binding.contentFrame.addView(requestsTabBinding!!.root)
            requestsTabBinding!!.recyclerView.layoutManager = LinearLayoutManager(this)
            requestsTabBinding!!.recyclerView.adapter = adapter
        }
        verifyTabBinding?.root?.visibility = View.GONE
        requestsTabBinding!!.root.visibility = View.VISIBLE
        loadFriendRequests()
    }

    private fun setupVerifyListeners() {
        val radioGroup = verifyTabBinding!!.radioGroup
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioAllowAll -> "allow_all"
                R.id.radioNeedVerify -> "need_verify"
                R.id.radioDenyAll -> "deny_all"
                else -> return@setOnCheckedChangeListener
            }
            setVerifyMode(mode)
        }
    }

    private fun loadVerifySetting() {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        verifyTabBinding?.progressVerify?.visibility = View.VISIBLE

        lifecycleScope.launch {
            when (val result = ApiClient.getVerifySetting(serverUrl, token)) {
                is ApiResult.Success -> {
                    verifyTabBinding?.progressVerify?.visibility = View.GONE
                    val mode = result.data
                    verifyTabBinding?.radioGroup?.post {
                        when (mode) {
                            "allow_all" -> verifyTabBinding?.radioAllowAll?.isChecked = true
                            "need_verify" -> verifyTabBinding?.radioNeedVerify?.isChecked = true
                            "deny_all" -> verifyTabBinding?.radioDenyAll?.isChecked = true
                        }
                    }
                }
                else -> {
                    verifyTabBinding?.progressVerify?.visibility = View.GONE
                    showError("获取验证方式失败")
                }
            }
        }
    }

    private fun setVerifyMode(mode: String) {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        verifyTabBinding?.progressVerify?.visibility = View.VISIBLE

        lifecycleScope.launch {
            when (val result = ApiClient.setVerifySetting(serverUrl, token, mode)) {
                is ApiResult.Success -> {
                    verifyTabBinding?.progressVerify?.visibility = View.GONE
                    //Toast.makeText(this@MessagesActivity, "验证方式已更新", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    verifyTabBinding?.progressVerify?.visibility = View.GONE
                    showError("保存失败")
                }
            }
        }
    }

    private fun loadFriendRequests() {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        requestsTabBinding?.progressRequests?.visibility = View.VISIBLE
        requestsTabBinding?.tvEmptyRequests?.visibility = View.GONE

        lifecycleScope.launch {
            when (val result = ApiClient.getFriendRequests(serverUrl, token)) {
                is ApiResult.Success -> {
                    requestsTabBinding?.progressRequests?.visibility = View.GONE
                    val list = result.data
                    if (list.isEmpty()) {
                        requestsTabBinding?.tvEmptyRequests?.visibility = View.VISIBLE
                        adapter.submitList(emptyList())
                    } else {
                        adapter.submitList(list)
                    }
                }
                else -> {
                    requestsTabBinding?.progressRequests?.visibility = View.GONE
                    showError("获取好友申请失败")
                }
            }
        }
    }

    private fun acceptRequest(requestId: String) {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)

        lifecycleScope.launch {
            when (val result = ApiClient.acceptFriendRequest(serverUrl, token, requestId)) {
                is ApiResult.Success -> {
                    Toast.makeText(this@MessagesActivity, "已添加为好友", Toast.LENGTH_SHORT).show()
                    loadFriendRequests() // 刷新列表
                }
                else -> {
                    showError("操作失败")
                }
            }
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}