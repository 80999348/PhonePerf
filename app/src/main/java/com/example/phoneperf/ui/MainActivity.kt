package com.example.phoneperf.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.phoneperf.R
import com.example.phoneperf.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: PerfAdapter
    private val vm: PerfViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响核心功能，只是运营商信息不可用 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    vm.refresh(manual = true)
                    true
                }
                R.id.action_exit -> {
                    exitApp()
                    true
                }
                else -> false
            }
        }

        adapter = PerfAdapter(this)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(false)
        binding.recycler.itemAnimator = null

        binding.swipe.setOnRefreshListener { vm.refresh(manual = true) }

        binding.retryButton.setOnClickListener { vm.refresh(manual = true) }

        observe()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }

        vm.refresh()
    }

    private fun observe() {
        vm.state.observe(this) { state ->
            when (state) {
                is UiState.Loading -> {
                    binding.progress.visibility = View.VISIBLE
                    binding.errorBox.visibility = View.GONE
                }
                is UiState.Ready -> {
                    binding.progress.visibility = View.GONE
                    binding.errorBox.visibility = View.GONE
                    binding.recycler.visibility = View.VISIBLE
                    adapter.submit(state.snapshot, vm.history.value ?: PerfViewModel.History())
                }
                is UiState.Error -> {
                    binding.progress.visibility = View.GONE
                    binding.errorBox.visibility = View.VISIBLE
                    binding.errorText.text = state.message
                }
            }
        }

        vm.refreshing.observe(this) { refreshing ->
            binding.swipe.isRefreshing = refreshing == true
        }

        vm.history.observe(this) { history ->
            (vm.state.value as? UiState.Ready)?.let { adapter.submit(it.snapshot, history) }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    /** 停止采集并退出应用（结束任务栈中的所有页面）。 */
    private fun exitApp() {
        vm.stopPolling()
        finishAffinity()
    }

    override fun onStart() {
        super.onStart()
        vm.startPolling()
    }

    override fun onStop() {
        super.onStop()
        vm.stopPolling()
    }
}
