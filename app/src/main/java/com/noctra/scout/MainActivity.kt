package com.noctra.scout

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.noctra.scout.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val nf: NumberFormat = NumberFormat.getIntegerInstance()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> { SettingsDialog.show(this, prefs) { refreshFooter() }; true }
                R.id.action_export -> { exportAvailable(); true }
                R.id.action_clear_taken -> { confirmClearTaken(); true }
                R.id.action_reset -> { confirmReset(); true }
                else -> false
            }
        }

        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int) = ListFragment.newInstance(position == 0)
        }
        binding.pager.offscreenPageLimit = 1
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.tab_available)
            else getString(R.string.tab_tried)
        }.attach()

        binding.toggle.setOnClickListener {
            if (ScraperState.stats.value.running) {
                ScraperService.stop(this)
            } else {
                requestNotificationsIfNeeded()
                ScraperService.start(this)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScraperState.stats.collect { render(it) }
            }
        }

        primeCountsIfIdle()
        refreshFooter()
    }

    /** When the service is not running the counters come straight from the log. */
    private fun primeCountsIfIdle() {
        if (ScraperState.stats.value.running) return
        lifecycleScope.launch {
            val (checked, available) = withContext(Dispatchers.IO) { Db.get(this@MainActivity).counts() }
            val space = prefs.nameSpace()
            ScraperState.update {
                it.copy(
                    checked = checked,
                    available = available,
                    cursor = prefs.cursor,
                    total = space.total
                )
            }
        }
    }

    private fun render(s: Stats) {
        binding.statChecked.text = nf.format(s.checked)
        binding.statAvailable.text = nf.format(s.available)
        binding.statRate.text = if (s.ratePerMin > 0) "${s.ratePerMin}/min" else "—"

        binding.toggle.text = getString(if (s.running) R.string.stop else R.string.start)
        binding.status.text = when {
            s.running && s.current.isNotEmpty() && s.status.startsWith("Running") ->
                getString(R.string.trying, s.current)
            else -> s.status
        }
        binding.pulse.visibility = if (s.running) View.VISIBLE else View.INVISIBLE

        val total = if (s.total > 0) s.total else prefs.nameSpace().total
        val pct = if (total > 0) (s.cursor * 100.0 / total) else 0.0
        binding.progress.max = 1000
        binding.progress.progress = (pct * 10).toInt().coerceIn(0, 1000)
        binding.progressLabel.text = getString(
            R.string.progress_label,
            nf.format(s.cursor),
            nf.format(total),
            String.format("%.2f", pct)
        )

        // While a scan is live this shows the delay actually in use, which auto-pacing
        // may have raised above the configured one.
        binding.charsetLabel.text = if (s.running && s.paceMs > 0) {
            if (s.endpoint.isNotEmpty()) {
                getString(
                    R.string.charset_label_endpoint,
                    NameSpace.label(prefs.charsetId),
                    s.paceMs,
                    s.rateLimits,
                    s.endpoint
                )
            } else {
                getString(
                    R.string.charset_label_paced,
                    NameSpace.label(prefs.charsetId),
                    s.paceMs,
                    s.rateLimits
                )
            }
        } else {
            getString(R.string.charset_label, NameSpace.label(prefs.charsetId), prefs.delayMs)
        }
    }

    private fun refreshFooter() {
        val space = prefs.nameSpace()
        // Emitting new stats re-runs render(), which owns the footer text.
        ScraperState.update { it.copy(total = space.total, cursor = prefs.cursor) }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun exportAvailable() {
        lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) { Db.get(this@MainActivity).allAvailable() }
            if (names.isEmpty()) {
                Snackbar.make(binding.root, R.string.nothing_to_export, Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Available usernames")
                putExtra(Intent.EXTRA_TEXT, names.joinToString("\n"))
            }
            startActivity(Intent.createChooser(share, getString(R.string.action_export)))
        }
    }

    private fun confirmClearTaken() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_clear_taken)
            .setMessage(R.string.clear_taken_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { Db.get(this@MainActivity).clearTaken() }
                    primeCountsIfIdle()
                    ScraperState.notifyDataChanged()
                }
            }
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_reset)
            .setMessage(R.string.reset_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                ScraperService.stop(this)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { Db.get(this@MainActivity).clearAll() }
                    prefs.resetProgress()
                    ScraperState.update { Stats(total = prefs.nameSpace().total, status = "Idle") }
                    ScraperState.notifyDataChanged()
                    refreshFooter()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        primeCountsIfIdle()
    }
}
