package app.harvester.companion.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.databinding.FragmentBrowserBinding
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.prompt.ShareData
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.prompts.file.AndroidPhotoPicker
import mozilla.components.feature.prompts.share.ShareDelegate
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.sitepermissions.SitePermissionsRules
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val sitePermissionsFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    
    private val viewModel: BrowserViewModel by activityViewModels()
    private var initialUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUrl = arguments?.getString("url")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val downloadsFeature = ViewBoundFeatureWrapper<mozilla.components.feature.downloads.DownloadsFeature>()
    private var downloadDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val components = (context.applicationContext as UHApp).components

        val downloadFileUtils = mozilla.components.support.utils.DefaultDownloadFileUtils(context.applicationContext) {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
        }

        val downloadFeature = mozilla.components.feature.downloads.DownloadsFeature(
            context.applicationContext,
            store = components.store,
            useCases = components.useCases.downloadsUseCases,
            fragmentManager = childFragmentManager,
            tabId = null,
            downloadFileUtils = downloadFileUtils,
            downloadManager = mozilla.components.feature.downloads.manager.FetchDownloadManager(
                context.applicationContext,
                components.store,
                app.harvester.companion.core.downloads.UhDownloadService::class,
                notificationsDelegate = components.notificationsDelegate,
            ),
            shouldForwardToThirdParties = { false },
            promptsStyling = mozilla.components.feature.downloads.DownloadsFeature.PromptsStyling(
                gravity = android.view.Gravity.BOTTOM,
                shouldWidthMatchParent = true,
                positiveButtonBackgroundColor = R.color.accent,
                positiveButtonTextColor = R.color.bg,
            ),
            onDownloadStartedListener = { downloadId ->
                components.appStore.dispatch(app.harvester.companion.components.appstate.AppAction.SnackbarAction.ShowSnackbar("Download started"))
            },
            onNeedToRequestPermissions = { permissions ->
                requestPermissions(permissions, 1)
            },
            dismissCustomFirstPartyDownloadDialog = {
                dismissRenameDialog()
                dismissDownloadDialogs()
            },
            customFirstPartyDownloadDialog = { currentDownloadState, fileNameIfAlreadyDownloaded, positiveAction, negativeAction, openFileAction ->
                if (canShowDownloadDialog()) {
                    if (fileNameIfAlreadyDownloaded.value != null) {
                        downloadDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.mozac_feature_downloads_again_dialog_title_with_unknown_size)
                            .setMessage(getString(R.string.mozac_feature_downloads_already_exists_dialog_title, fileNameIfAlreadyDownloaded.value))
                            .setPositiveButton(R.string.mozac_feature_downloads_open_existing_file) { dialog, _ ->
                                openFileAction.value.invoke()
                                dialog.dismiss()
                            }
                            .setNegativeButton(R.string.mozac_feature_downloads_dialog_download_again) { dialog, _ ->
                                positiveAction.value.invoke(currentDownloadState.value)
                                dialog.dismiss()
                            }
                            .setNeutralButton(R.string.mozac_feature_downloads_dialog_cancel) { dialog, _ ->
                                negativeAction.value.invoke()
                                dialog.dismiss()
                            }
                            .show()
                    } else {
                        showRenameDownloadDialog(
                            fileName = currentDownloadState.value.fileName ?: "download",
                            currentDownloadState = currentDownloadState,
                            positiveAction = positiveAction,
                            negativeAction = negativeAction,
                        )
                    }
                }
            },
            customThirdPartyDownloadDialog = { downloaderApps, onAppSelected, negativeActionCallback ->
                if (canShowDownloadDialog()) {
                    downloadDialog = app.harvester.companion.downloads.createDownloadAppDialog(
                        context = context,
                        downloaderApps = downloaderApps.value,
                        onAppSelected = onAppSelected.value,
                        onDismiss = { downloadDialog = null },
                    )
                    downloadDialog?.show()
                }
            },
            fileHasNotEnoughStorageDialog = { filename ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.download_file_has_not_enough_storage_dialog_title)
                    .setMessage(getString(R.string.download_file_has_not_enough_storage_dialog_message, filename.value))
                    .setPositiveButton(R.string.download_file_has_not_enough_storage_dialog_confirm_button_text) { dialog, _ -> dialog.dismiss() }
                    .setNegativeButton(R.string.download_file_has_not_enough_storage_dialog_cancel_button_text) { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        )

        downloadsFeature.set(downloadFeature, owner = this, view = view)

        val initStart = System.currentTimeMillis()
        val engineView = components.engine.createView(context, null)
        binding.swipeRefresh.addView(engineView.asView())
        UHLog.d("perf", "Browser engine view created: ${System.currentTimeMillis() - initStart}ms")

        setupFeatures(components, engineView)

        if (initialUrl != null) {
            UHLog.d("web_debug", "Loading initial URL: $initialUrl")
            components.useCases.sessionUseCases.loadUrl(initialUrl!!)
        } else {
            UHLog.w("web_debug", "BrowserFragment created without an initial URL.")
        }

        setupLogging(components)
        ViewCompat.setOnApplyWindowInsetsListener(binding.browserLayout) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, top, 0, bottom)
            insets
        }
    }

    private fun setupFeatures(components: app.harvester.companion.components.Components, engineView: mozilla.components.concept.engine.EngineView) {
        sessionFeature.set(
            feature = SessionFeature(
                components.store,
                components.useCases.sessionUseCases.goBack,
                components.useCases.sessionUseCases.goForward,
                engineView,
                null
            ),
            owner = this,
            view = binding.root
        )

        binding.swipeRefresh.setColorSchemeResources(app.harvester.companion.R.color.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(app.harvester.companion.R.color.surface_2)

        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                components.store,
                components.useCases.sessionUseCases.reload,
                binding.swipeRefresh,
                null,
                null
            ),
            owner = this,
            view = binding.root
        )

        appLinksFeature.set(
            feature = AppLinksFeature(
                requireContext(),
                components.store,
                fragmentManager = parentFragmentManager,
                sessionId = null,
                loadUrlUseCase = components.useCases.sessionUseCases.loadUrl,
                shouldPrompt = { true },
                launchInApp = { false }
            ),
            owner = this,
            view = binding.root
        )

        promptsFeature.set(
            feature = PromptFeature(
                activity = requireActivity(),
                store = components.store,
                customTabId = null,
                fragmentManager = parentFragmentManager,
                fileUploadsDirCleaner = mozilla.components.feature.prompts.file.FileUploadsDirCleaner { requireContext().cacheDir },
                tabsUseCases = components.useCases.tabsUseCases,
                shareDelegate = object : ShareDelegate {
                    override fun showShareSheet(
                        context: Context,
                        shareData: ShareData,
                        onDismiss: () -> Unit,
                        onSuccess: () -> Unit
                    ) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareData.url)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share"))
                        onSuccess()
                    }
                },
                isEmailMaskFeatureEnabled = { false },
                isSuggestEmailMaskEnabled = { false },
                androidPhotoPicker = AndroidPhotoPicker(
                    requireContext(),
                    AndroidPhotoPicker.singleMediaPicker({ this }) { promptsFeature.get() },
                    AndroidPhotoPicker.multipleMediaPicker({ this }) { promptsFeature.get() }
                ),
                onNeedToRequestPermissions = { requestPermissions(it, 1) }
            ),
            owner = this,
            view = binding.root
        )

        val sitePermissionsStorage = mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage(requireContext())
        sitePermissionsFeature.set(
            feature = SitePermissionsFeature(
                context = requireContext(),
                storage = sitePermissionsStorage,
                sessionId = null,
                fragmentManager = parentFragmentManager,
                onNeedToRequestPermissions = { requestPermissions(it, 2) },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = components.store,
                sitePermissionsRules = SitePermissionsRules(
                    autoplayAudible = SitePermissionsRules.AutoplayAction.BLOCKED,
                    autoplayInaudible = SitePermissionsRules.AutoplayAction.BLOCKED,
                    camera = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    location = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    notification = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    microphone = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    persistentStorage = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    mediaKeySystemAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    crossOriginStorageAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    localDeviceAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    localNetworkAccess = SitePermissionsRules.Action.ASK_TO_ALLOW
                )
            ),
            owner = this,
            view = binding.root
        )
    }

    private fun setupLogging(components: app.harvester.companion.components.Components) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                components.store.stateFlow.collect { state ->
                    state.selectedTab?.content?.let { content ->
                        if (content.url != "about:blank" && content.url.isNotBlank()) {
                            UHLog.netNav("GET", content.url, null, false, false)
                        }
                        if (content.loading && content.progress == 100) {
                             UHLog.d("web", "Page loaded: ${content.url}")
                        }
                    }
                }
            }
        }
    }

    fun onBackPressed(): Boolean {
        val components = (requireContext().applicationContext as UHApp).components
        val state = components.store.state.selectedTab
        if (state != null && state.content.canGoBack) {
            components.useCases.sessionUseCases.goBack.invoke()
            return true
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        downloadsFeature.withFeature { it.start() }
    }

    override fun onStop() {
        super.onStop()
        downloadsFeature.withFeature { it.stop() }
        dismissDownloadDialogs()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            downloadsFeature.withFeature { it.onPermissionsResult(permissions, grantResults) }
        } else if (requestCode == 2) {
            sitePermissionsFeature.withFeature { it.onPermissionsResult(permissions, grantResults) }
        }
    }

    private fun canShowDownloadDialog(): Boolean {
        if (!isAdded || view == null || viewLifecycleOwner.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) return false
        return try {
            val isRenameFragmentShowing = childFragmentManager.findFragmentByTag(app.harvester.companion.downloads.RenameAndChangeLocationDialogFragment.RENAME_AND_CHANGE_LOCATION_DIALOG_TAG) != null
            downloadDialog == null && !isRenameFragmentShowing
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun dismissRenameDialog() {
        val renameDialog = childFragmentManager.findFragmentByTag(app.harvester.companion.downloads.RenameAndChangeLocationDialogFragment.RENAME_AND_CHANGE_LOCATION_DIALOG_TAG) as? app.harvester.companion.downloads.RenameAndChangeLocationDialogFragment
        renameDialog?.dismissAllowingStateLoss()
    }

    private fun dismissDownloadDialogs() {
        downloadDialog?.dismiss()
    }

    private fun showRenameDownloadDialog(
        fileName: String,
        currentDownloadState: mozilla.components.feature.downloads.CurrentDownloadState,
        positiveAction: mozilla.components.feature.downloads.PositiveActionCallback,
        negativeAction: mozilla.components.feature.downloads.NegativeActionCallback,
    ) {
        val existingFragment = childFragmentManager.findFragmentByTag(app.harvester.companion.downloads.RenameAndChangeLocationDialogFragment.RENAME_AND_CHANGE_LOCATION_DIALOG_TAG)
        if (existingFragment == null && isAdded && !childFragmentManager.isStateSaved) {
            val renameDialog = app.harvester.companion.downloads.RenameAndChangeLocationDialogFragment.newInstance(
                fileName = fileName,
                directoryPath = currentDownloadState.value.directoryPath,
                contentSize = currentDownloadState.value.contentLength ?: 0,
            )
            renameDialog.onConfirmSave = { newFileName, directoryPath ->
                val downloadState = currentDownloadState.value.copy(fileName = newFileName, directoryPath = directoryPath)
                positiveAction.value.invoke(downloadState)
            }
            renameDialog.onCancel = {
                negativeAction.value.invoke()
            }
            renameDialog.show(childFragmentManager, app.harvester.companion.downloads.RenameAndChangeLocationDialogFragment.RENAME_AND_CHANGE_LOCATION_DIALOG_TAG)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
