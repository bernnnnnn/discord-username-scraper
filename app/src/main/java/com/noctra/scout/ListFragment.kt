package com.noctra.scout

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.noctra.scout.databinding.FragmentListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One tab: either the available names or everything that has been tried. */
class ListFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ResultAdapter
    private var availableOnly = false
    private var loadedPages = 0
    private var endReached = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        availableOnly = requireArguments().getBoolean(ARG_AVAILABLE_ONLY)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ResultAdapter { entry -> copy(entry.name) }
        val lm = LinearLayoutManager(requireContext())
        binding.list.layoutManager = lm
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(true)
        binding.list.itemAnimator = null
        binding.empty.setText(
            if (availableOnly) R.string.empty_available else R.string.empty_tried
        )

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || endReached) return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 20) loadNextPage()
            }
        })

        binding.swipe.setOnRefreshListener { reload() }
        binding.swipe.setColorSchemeColors(0xFFD8E8FF.toInt())
        binding.swipe.setProgressBackgroundColorSchemeColor(0xFF15181D.toInt())

        reload()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Only redraw when the user is already at the top, so an active scan never
                // yanks the list out from under a scroll.
                var lastRefresh = 0L
                ScraperState.dataChanged.conflate().collect {
                    val now = System.currentTimeMillis()
                    if (now - lastRefresh < REFRESH_THROTTLE_MS) return@collect
                    if (lm.findFirstCompletelyVisibleItemPosition() <= 0) {
                        lastRefresh = now
                        reload()
                    }
                }
            }
        }
    }

    private fun reload() {
        loadedPages = 0
        endReached = false
        loadPage(replace = true)
    }

    private fun loadNextPage() = loadPage(replace = false)

    private fun loadPage(replace: Boolean) {
        if (loading) return
        loading = true
        val offset = loadedPages * PAGE_SIZE
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                Db.get(requireContext()).page(availableOnly, PAGE_SIZE, offset)
            }
            val b = _binding
            if (b != null) {
                if (replace) adapter.replaceAll(rows) else adapter.append(rows)
                if (rows.size < PAGE_SIZE) endReached = true
                loadedPages++
                b.empty.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
                b.swipe.isRefreshing = false
            }
            loading = false
        }
    }

    private fun copy(name: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("username", name))
        Snackbar.make(binding.root, getString(R.string.copied, name), Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val PAGE_SIZE = 150
        private const val REFRESH_THROTTLE_MS = 1_500L
        private const val ARG_AVAILABLE_ONLY = "available_only"

        fun newInstance(availableOnly: Boolean) = ListFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_AVAILABLE_ONLY, availableOnly) }
        }
    }
}
