package com.joshuacerdenia.android.nicefeed.ui.fragment

import android.content.Context
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.joshuacerdenia.android.nicefeed.R
import com.joshuacerdenia.android.nicefeed.data.local.FeedPreferences
import com.joshuacerdenia.android.nicefeed.data.model.entry.EntryLight
import com.joshuacerdenia.android.nicefeed.data.model.feed.Feed
import com.joshuacerdenia.android.nicefeed.data.model.feed.FeedManageable
import com.joshuacerdenia.android.nicefeed.databinding.FragmentEntryListBinding
import com.joshuacerdenia.android.nicefeed.databinding.ToolbarBinding
import com.joshuacerdenia.android.nicefeed.ui.OnHomePressed
import com.joshuacerdenia.android.nicefeed.ui.OnToolbarInflated
import com.joshuacerdenia.android.nicefeed.ui.adapter.EntryListAdapter
import com.joshuacerdenia.android.nicefeed.ui.dialog.ConfirmActionFragment
import com.joshuacerdenia.android.nicefeed.ui.dialog.ConfirmActionFragment.Companion.REMOVE
import com.joshuacerdenia.android.nicefeed.ui.dialog.EditFeedFragment
import com.joshuacerdenia.android.nicefeed.ui.dialog.FilterEntriesFragment
import com.joshuacerdenia.android.nicefeed.ui.menu.EntryPopupMenu
import com.joshuacerdenia.android.nicefeed.ui.viewmodel.EntryListViewModel
import com.joshuacerdenia.android.nicefeed.util.Utils
import com.joshuacerdenia.android.nicefeed.util.extensions.hide
import com.joshuacerdenia.android.nicefeed.util.extensions.setSimpleVisibility
import com.joshuacerdenia.android.nicefeed.util.extensions.show
import com.joshuacerdenia.android.nicefeed.util.work.BackgroundSyncWorker

open class EntryListFragment : VisibleFragment(),
    EntryListAdapter.OnEntrySelected,
    EntryPopupMenu.OnPopupMenuItemClicked,
    FilterEntriesFragment.Callbacks,
    EditFeedFragment.Callback,
    ConfirmActionFragment.OnRemoveConfirmed
{

    interface Callbacks: OnHomePressed, OnToolbarInflated {

        fun onFeedLoaded(feedId: String)

        fun onEntrySelected(entryId: String)

        fun onCategoriesNeeded(): Array<String>

        fun onFeedRemoved()
    }

    private var _binding: FragmentEntryListBinding? = null
    private var _toolbarBinding: ToolbarBinding? = null
    private val binding get() = _binding!!
    private val toolbarBinding get() = _toolbarBinding!!

    private val viewModel: EntryListViewModel by viewModels()
    private lateinit var adapter: EntryListAdapter
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var searchItem: MenuItem
    private var markAllOptionsItem: MenuItem? = null
    private var starAllOptionsItem: MenuItem? = null
    private var autoUpdateOnLaunch = true
    private var feedId: String? = null
    private var callbacks: Callbacks? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as Callbacks?
        adapter = EntryListAdapter(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadEntryOnStart()

        viewModel.setOrder(FeedPreferences.entryListOrder)
        viewModel.keepOldUnreadEntries(FeedPreferences.shouldKeepOldUnreadEntries)
        autoUpdateOnLaunch = FeedPreferences.shouldAutoUpdate

        feedId = arguments?.getString(ARG_FEED_ID)
        setHasOptionsMenu(feedId != null)

        val blockAutoUpdate = arguments?.getBoolean(ARG_BLOCK_AUTO_UPDATE) ?: false
        if (blockAutoUpdate || !autoUpdateOnLaunch) viewModel.isAutoUpdating = false
    }

    private fun loadEntryOnStart() {
        // If there is an entryID argument, load immediately and only once
        arguments?.getString(ARG_ENTRY_ID)?.let { entryId ->
            arguments?.remove(ARG_ENTRY_ID)
            onEntryClicked(entryId, null)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentEntryListBinding.inflate(inflater, container, false)
        _toolbarBinding = binding.toolbar
        setupToolbar()
        setupRecyclerView()
        return binding.root
    }

    private fun setupToolbar() {
        toolbarBinding.toolbar.title = getString(R.string.loading)
        callbacks?.onToolbarInflated(toolbarBinding.toolbar, false)
        toolbarBinding.toolbar.apply {
            setNavigationIcon(R.drawable.ic_menu)
            setNavigationOnClickListener { callbacks?.onHomePressed() }
            setOnClickListener { binding.recyclerView.smoothScrollToPosition(0) }
        }
    }

    private fun setupRecyclerView() {
        val isPortrait = resources.configuration.orientation == ORIENTATION_PORTRAIT
        val layoutManager = if (isPortrait) LinearLayoutManager(context) else GridLayoutManager(context, 2)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.feedLiveData.observe(viewLifecycleOwner) { feed: Feed ->
            binding.progressBar.hide()
            binding.masterProgressBar.hide()
            viewModel.onFeedRetrieved(feed)
            restoreToolbar()
            feed?.let { callbacks?.onFeedLoaded(it.url) } ?: run {
                if (feedId?.startsWith(FOLDER) == false) {
                    callbacks?.onFeedRemoved()
                }
            }
        }

        viewModel.entriesLightLiveData.observe(viewLifecycleOwner) { entries ->
            binding.noItemsTextView.setSimpleVisibility(entries.isNullOrEmpty())
            binding.progressBar.hide()
            adapter.submitList(entries)
            showUpdateNotice()
            toggleOptionsItems()

            if (adapter.lastClickedPosition == 0) {
                handler.postDelayed({
                    binding.recyclerView.scrollToPosition(0)
                }, 250)
            }
        }

        viewModel.updateResultLiveData.observe(viewLifecycleOwner) { results ->
            binding.progressBar.hide()
            results?.let { viewModel.onUpdatesDownloaded(results) }
            restoreToolbar()
        }
    }

    override fun onStart() {
        super.onStart()

        feedId?.let { feedId ->
            viewModel.getFeedWithEntries(feedId)
            if (feedId.startsWith(FOLDER)) callbacks?.onFeedLoaded(feedId)
            if (viewModel.isAutoUpdating) { // Auto-update on launch:
                handler.postDelayed({
                    handleCheckForUpdates(feedId)
                }, 750)
            }
        } ?: run {
            // If there is no feed to load:
            binding.masterProgressBar.hide()
            binding.noItemsTextView.show()
            restoreToolbar()
        }
    }

    private fun restoreToolbar() {
        toolbarBinding.toolbar.title = when (feedId) {
            FOLDER_NEW -> getString(R.string.new_entries)
            FOLDER_STARRED -> getString(R.string.starred_entries)
            null -> getString(R.string.app_name)
            else -> viewModel.getCurrentFeed()?.title
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setOrder(FeedPreferences.entryListOrder)
        viewModel.keepOldUnreadEntries(FeedPreferences.shouldKeepOldUnreadEntries)
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.fragment_entry_list, menu)
        searchItem = menu.findItem(R.id.menuItem_search)
        markAllOptionsItem = menu.findItem(R.id.mark_all_item)
        starAllOptionsItem = menu.findItem(R.id.star_all_item)
        toggleOptionsItems()

        if (feedId?.startsWith(FOLDER) == true) {
            menu.findItem(R.id.visit_website_item).isVisible = false
            menu.findItem(R.id.about_feed_item).isVisible = false
            menu.findItem(R.id.remove_feed_item).isVisible = false
        }

        if (feedId == FOLDER_STARRED) menu.findItem(R.id.update_item).isVisible = false

//        searchItem.setOnActionExpandListener(object: MenuItem.OnActionExpandListener {
//            override fun onMenuItemActionExpand(item: MenuItem?): Boolean = true
//
//            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
//                viewModel.clearQuery()
//                return true
//            }
//        })

        (searchItem.actionView as SearchView).apply {
            if (viewModel.query.isNotEmpty()) {
                searchItem.expandActionView()
                setQuery(viewModel.query, false)
                clearFocus()
            }

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextChange(queryText: String): Boolean = true

                override fun onQueryTextSubmit(queryText: String): Boolean {
                    viewModel.submitQuery(queryText)
                    this@apply.clearFocus()
                    return true
                }
            })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.update_item -> handleCheckForUpdates()
            R.id.about_feed_item -> handleShowFeedInfo(viewModel.getCurrentFeed())
            R.id.filter_item -> handleFilter()
            R.id.mark_all_item -> handleMarkAll()
            R.id.star_all_item -> handleStarAll()
            R.id.visit_website_item -> handleVisitWebsite(viewModel.getCurrentFeed()?.website)
            R.id.remove_feed_item -> handleRemoveFeed()
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleOptionsItems() {
        markAllOptionsItem?.apply {
            if (viewModel.allIsRead()) {
                title = getString(R.string.mark_all_as_unread)
                setIcon(R.drawable.ic_check_circle_outline)
            } else {
                title = getString(R.string.mark_all_as_read)
                setIcon(R.drawable.ic_check_circle)
            }
        }

        starAllOptionsItem?.apply {
            if (viewModel.allIsStarred()) {
                title = getString(R.string.unstar_all)
                setIcon(R.drawable.ic_star)
            } else {
                title = getString(R.string.star_all)
                setIcon(R.drawable.ic_star_border)
            }
        }
    }

    private fun handleCheckForUpdates(
        url: String? = viewModel.getCurrentFeed()?.url
    ): Boolean {
        searchItem.collapseActionView()
        viewModel.clearQuery()

        if (feedId == FOLDER_NEW) {
            context?.let { context ->
                BackgroundSyncWorker.runOnce(context)
                Snackbar.make(
                    binding.root,
                    getString(R.string.updating_all_feeds),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        } else {
            if (url == null) return false
            toolbarBinding.toolbar.title = getString(R.string.updating)
            binding.progressBar.show()
            viewModel.requestUpdate(url)
        }

        return true
    }
    
    private fun showUpdateNotice() {
        val count = viewModel.updateValues
        if (count.isEmpty()) return
        val itemsAddedString = resources.getQuantityString(R.plurals.numberOfNewEntries, count.added, count.added)
        val itemsUpdatedString = resources.getQuantityString(R.plurals.numberOfEntries, count.updated, count.updated)
        val message = when {
            count.added > 0 && count.updated == 0 -> getString(R.string.added, itemsAddedString)
            count.added == 0 && count.updated > 0 -> getString(R.string.updated, itemsUpdatedString)
            else -> getString(R.string.added_and_updated, itemsAddedString, count.updated)
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        viewModel.updateValues.clear()
    }

    private fun handleShowFeedInfo(feed: Feed?): Boolean {
        return if (feed != null) {
            val mFeed = FeedManageable(url = feed.url, title = feed.title, website = feed.website,
                imageUrl = feed.imageUrl, description = feed.description, category = feed.category)
            val categories = callbacks?.onCategoriesNeeded() ?: emptyArray()
            EditFeedFragment.newInstance(mFeed, categories).apply {
                setTargetFragment(this@EntryListFragment, 0)
                show(this@EntryListFragment.parentFragmentManager, null)
            }
            true
        } else false
    }

    override fun onFeedInfoSubmitted(title: String, category: String, isChanged: Boolean) {
        if (!isChanged) return
        viewModel.getCurrentFeed()?.let { currentFeed ->
            val editedFeed = currentFeed.apply {
                this.title = title
                this.category = category
            }
            viewModel.updateFeed(editedFeed)
            handler.postDelayed({
                Snackbar.make(binding.root, getString(R.string.saved_changes_to, title), Snackbar.LENGTH_SHORT).show()
            }, 250)
        }
    }

    private fun handleFilter(): Boolean {
        FilterEntriesFragment.newInstance(viewModel.filter).apply {
            setTargetFragment(this@EntryListFragment, 0)
            show(this@EntryListFragment.parentFragmentManager, null)
        }
        return true
    }

    private fun handleMarkAll(): Boolean {
        viewModel.markAllCurrentEntriesAsRead()
        adapter.notifyDataSetChanged()
        return true
    }

    private fun handleStarAll(): Boolean {
        viewModel.starAllCurrentEntries()
        adapter.notifyDataSetChanged()
        return true
    }

    private fun handleVisitWebsite(website: String?): Boolean {
        return if (website != null) {
            Utils.openLink(requireActivity(), binding.recyclerView, Uri.parse(website))
            true
        } else false
    }

    private fun handleRemoveFeed(): Boolean {
        val feed = viewModel.getCurrentFeed()
        return if (feed != null) {
            ConfirmActionFragment.newInstance(REMOVE, feed.title).apply {
                setTargetFragment(this@EntryListFragment, 0)
                show(this@EntryListFragment.parentFragmentManager,null)
            }
            true
        } else false
    }

    override fun onRemoveConfirmed() {
        val title = viewModel.getCurrentFeed()?.title
        Snackbar.make(binding.recyclerView, getString(R.string.unsubscribed_message, title), Snackbar.LENGTH_SHORT).show()
        viewModel.deleteFeedAndEntries()
        callbacks?.onFeedRemoved()
    }

    override fun onEntryClicked(entryId: String, view: View?) {
        if (FeedPreferences.shouldViewInBrowser) {
            Utils.openLink(requireContext(), view, Uri.parse(entryId))
            viewModel.updateEntryIsRead(entryId, true)
        } else {
            callbacks?.onEntrySelected(entryId)
        }
    }

    override fun onEntryLongClicked(entry: EntryLight, view: View?) {
        view?.let { EntryPopupMenu(requireContext(), it, this, entry).show() }
    }

    override fun onPopupMenuItemClicked(entry: EntryLight, action: Int) {
        val url = entry.url
        when (action) {
            EntryPopupMenu.ACTION_STAR -> viewModel.updateEntryIsStarred(url, !entry.isStarred)
            EntryPopupMenu.ACTION_MARK_AS -> viewModel.updateEntryIsRead(url, !entry.isRead)
            else -> {
                onEntryClicked(entry.url, binding.recyclerView)
                return
            }
        }
        adapter.notifyDataSetChanged()
    }

    override fun onFilterSelected(filter: Int) {
        viewModel.setFilter(filter)
    }

    override fun onStop() {
        super.onStop()
        FeedPreferences.lastViewedFeedId = feedId
    }

    override fun onDetach() {
        super.onDetach()
        callbacks = null
    }

    companion object {

        private const val TAG = "EntryListFragment"
        private const val ARG_FEED_ID = "ARG_FEED_ID"
        private const val ARG_ENTRY_ID = "ARG_ENTRY_ID"
        private const val ARG_BLOCK_AUTO_UPDATE = "ARG_BLOCK_AUTO_UPDATE"

        const val FOLDER = "FOLDER"
        const val FOLDER_NEW = "FOLDER_NEW"
        const val FOLDER_STARRED = "FOLDER_STARRED"

        fun newInstance(
            feedId: String?,
            entryId: String? = null,
            blockAutoUpdate: Boolean = false
        ): EntryListFragment {
            return EntryListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FEED_ID, feedId)
                    putString(ARG_ENTRY_ID, entryId)
                    putBoolean(ARG_BLOCK_AUTO_UPDATE, blockAutoUpdate)
                }
            }
        }
    }
}