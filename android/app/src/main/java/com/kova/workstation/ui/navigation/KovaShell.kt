package com.kova.workstation.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kova.workstation.ui.WorkspaceTab
import com.kova.workstation.ui.WorkstationViewModel
import com.kova.workstation.ui.connect.ConnectScreen
import com.kova.workstation.ui.files.CodeEditorScreen
import com.kova.workstation.ui.files.ForgeFilesScreen
import com.kova.workstation.ui.files.GitPanelSheet
import com.kova.workstation.ui.hub.PulseHubScreen
import com.kova.workstation.ui.settings.SettingsScreen
import com.kova.workstation.ui.stage.LensStageScreen
import com.kova.workstation.ui.stage.PromptComposerSheet
import com.kova.workstation.ui.stage.SemanticSheet
import com.kova.workstation.ui.terminal.ShellScreen
import com.kova.workstation.ui.theme.KovaPalette
import com.kova.workstation.ui.theme.KovaTheme

@Composable
fun KovaRoot(viewModel: WorkstationViewModel) {
    val state by viewModel.uiState.collectAsState()

    KovaTheme(darkTheme = state.preferences.darkTheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.activeProfile == null || (!state.connected && state.systemInfo == null)) {
                ConnectScreen(
                    state = state,
                    onConnect = viewModel::connect,
                    onSaveAndConnect = viewModel::saveAndConnectProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onPairingUri = { viewModel.handleIncomingPairingUri(it) }
                )
            } else {
                // Active Editor takes full focus over Forge
                val openEditor = state.editor
                if (openEditor != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        CodeEditorScreen(
                            editor = openEditor,
                            fontSizeSp = state.preferences.editorFontSp,
                            onChangeFontSize = { delta ->
                                viewModel.updatePreferences {
                                    it.copy(editorFontSp = (it.editorFontSp + delta).coerceIn(10f, 22f))
                                }
                            },
                            onContentChange = viewModel::updateEditorContent,
                            onSave = viewModel::saveEditorFile,
                            onToggleTail = viewModel::toggleEditorLiveTail,
                            onClose = viewModel::closeEditor
                        )
                    }
                } else {
                    Scaffold(
                        topBar = {
                            CompactStatusBar(
                                workstationName = state.activeProfile?.name ?: "Windows",
                                connected = state.connected,
                                latencyMs = state.latencyMs,
                                onOpenComposer = { viewModel.setPromptComposerVisible(true) }
                            )
                        },
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 4.dp
                            ) {
                                NavigationBarItem(
                                    selected = state.activeTab == WorkspaceTab.PULSE,
                                    onClick = { viewModel.selectTab(WorkspaceTab.PULSE) },
                                    icon = { Icon(Icons.Rounded.Dashboard, null) },
                                    label = { Text("Pulse") }
                                )
                                NavigationBarItem(
                                    selected = state.activeTab == WorkspaceTab.LENS,
                                    onClick = { viewModel.selectTab(WorkspaceTab.LENS) },
                                    icon = { Icon(Icons.Rounded.Visibility, null) },
                                    label = { Text("Lens") }
                                )
                                NavigationBarItem(
                                    selected = state.activeTab == WorkspaceTab.SHELL,
                                    onClick = { viewModel.selectTab(WorkspaceTab.SHELL) },
                                    icon = { Icon(Icons.Rounded.Terminal, null) },
                                    label = { Text("Shell") }
                                )
                                NavigationBarItem(
                                    selected = state.activeTab == WorkspaceTab.FORGE,
                                    onClick = { viewModel.selectTab(WorkspaceTab.FORGE) },
                                    icon = { Icon(Icons.Rounded.FolderOpen, null) },
                                    label = { Text("Forge") }
                                )
                                NavigationBarItem(
                                    selected = state.activeTab == WorkspaceTab.SETTINGS,
                                    onClick = { viewModel.selectTab(WorkspaceTab.SETTINGS) },
                                    icon = { Icon(Icons.Rounded.Settings, null) },
                                    label = { Text("Prefs") }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (state.activeTab) {
                                WorkspaceTab.PULSE -> PulseHubScreen(
                                    state = state,
                                    onRefreshWindows = { viewModel.refreshWindows(withThumbnails = true) },
                                    onOpenWindowStage = viewModel::openWindowInStage,
                                    onOpenFullDesktop = {
                                        viewModel.setStageSource("desktop", null)
                                        viewModel.selectTab(WorkspaceTab.LENS)
                                    },
                                    onWindowAction = { hwnd, act -> viewModel.windowAction(hwnd, act) },
                                    onLaunchApp = { app -> viewModel.launchApp(app.target, app.args) },
                                    onOpenPromptComposer = { viewModel.setPromptComposerVisible(true) },
                                    onPushClipboard = viewModel::pushAndroidClipboardToWindows,
                                    onPullClipboard = {
                                        viewModel.refreshClipboard()
                                        if (state.windowsClipboardText.isNotBlank()) {
                                            viewModel.copyToAndroidClipboard(state.windowsClipboardText)
                                        }
                                    },
                                    onCopyClipboardItem = { viewModel.copyToAndroidClipboard(it) }
                                )

                                WorkspaceTab.LENS -> LensStageScreen(
                                    state = state,
                                    onSelectSource = viewModel::setStageSource,
                                    onWindowAction = { hwnd, act -> viewModel.windowAction(hwnd, act) },
                                    onModeChange = viewModel::setStageInputMode,
                                    onSetRoi = viewModel::setStageRoi,
                                    onMouseMoveAbs = viewModel::sendMouseMoveAbs,
                                    onMouseMoveRel = viewModel::sendMouseMoveRel,
                                    onMouseClick = viewModel::sendMouseClick,
                                    onScroll = viewModel::sendScroll,
                                    onToggleDragLock = viewModel::toggleStageDragLock,
                                    onToggleXRay = viewModel::toggleXRayOverlay,
                                    onOpenControls = { viewModel.setSemanticSheetVisible(true) },
                                    onOpenComposer = { viewModel.setPromptComposerVisible(true) },
                                    onSendKeys = viewModel::sendKeyChord,
                                    onInvokeUia = viewModel::invokeUiaElement,
                                    onActivateDisplay = viewModel::activateLiveDisplay
                                )

                                WorkspaceTab.SHELL -> ShellScreen(
                                    state = state,
                                    onCreateTerminal = { shell -> viewModel.createTerminal(shell) },
                                    onSelectTerminal = viewModel::selectTerminal,
                                    onCloseTerminal = viewModel::closeTerminal,
                                    onSendInput = viewModel::sendTerminalInput,
                                    onResize = viewModel::resizeActiveTerminal,
                                    onCopyAll = viewModel::copyActiveTerminalText,
                                    onFontSizeDelta = { delta ->
                                        viewModel.updatePreferences {
                                            it.copy(terminalFontSp = (it.terminalFontSp + delta).coerceIn(9f, 20f))
                                        }
                                    },
                                    onAddSnippet = viewModel::addSnippet,
                                    onDeleteSnippet = viewModel::deleteSnippet
                                )

                                WorkspaceTab.FORGE -> ForgeFilesScreen(
                                    state = state,
                                    onBrowse = viewModel::browsePath,
                                    onSearch = viewModel::searchCurrentFolder,
                                    onToggleHidden = viewModel::toggleHiddenFiles,
                                    onOpenFile = viewModel::openFileInEditor,
                                    onCreateFolder = viewModel::createNewFolder,
                                    onCreateFile = viewModel::createNewFile,
                                    onRename = viewModel::renameEntry,
                                    onDelete = viewModel::deleteEntry,
                                    onUploadBytes = viewModel::uploadBytesToCurrentDir,
                                    onDownloadFile = viewModel::downloadFileBytes,
                                    onOpenTerminalHere = { dir -> viewModel.createTerminal("powershell", cwd = dir) },
                                    onCopyPath = { viewModel.copyToAndroidClipboard(it) },
                                    onOpenGit = viewModel::openGitPanel
                                )

                                WorkspaceTab.SETTINGS -> SettingsScreen(
                                    state = state,
                                    onUpdatePrefs = viewModel::updatePreferences,
                                    onDisconnect = viewModel::disconnect
                                )
                            }
                        }
                    }
                }

                // Shared Modals: Semantic UI Control Sheet, Smart Prompt Composer, Git Panel
                if (state.showSemanticSheet) {
                    SemanticSheet(
                        uiaData = state.uiaData,
                        loading = state.uiaLoading,
                        onRefresh = viewModel::inspectCurrentWindowUia,
                        onInvoke = viewModel::invokeUiaElement,
                        onCopyText = { viewModel.copyToAndroidClipboard(it) },
                        onDismiss = { viewModel.setSemanticSheetVisible(false) }
                    )
                }

                if (state.showPromptComposer) {
                    val activeTitle = state.windows.firstOrNull { it.hwnd == state.stageHwnd }?.title
                        ?: state.windows.firstOrNull { it.focused }?.title
                        ?: "active Windows app"
                    PromptComposerSheet(
                        targetTitle = activeTitle,
                        onDispatch = viewModel::dispatchPromptText,
                        onDismiss = { viewModel.setPromptComposerVisible(false) }
                    )
                }

                if (state.showGitSheet) {
                    GitPanelSheet(
                        status = state.gitStatus,
                        diffText = state.gitDiffText,
                        diffFile = state.gitDiffFile,
                        onRefresh = viewModel::refreshGitStatus,
                        onSelectDiff = viewModel::loadGitDiff,
                        onStageAll = { viewModel.runGitAction("stage") },
                        onCommit = { msg -> viewModel.runGitAction("commit", message = msg) },
                        onPull = { viewModel.runGitAction("pull") },
                        onPush = { viewModel.runGitAction("push") },
                        onDismiss = viewModel::closeGitPanel
                    )
                }
            }

            // Floating Toast Pill (non-blocking, crisp feedback)
            AnimatedVisibility(
                visible = !state.bannerMessage.isNullOrBlank(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 50.dp, start = 20.dp, end = 20.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = KovaPalette.SlateElevated,
                    shadowElevation = 8.dp,
                    modifier = Modifier.clickable { viewModel.dismissBanner() }
                ) {
                    Text(
                        text = state.bannerMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = KovaPalette.InkPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactStatusBar(
    workstationName: String,
    connected: Boolean,
    latencyMs: Long?,
    onOpenComposer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(46.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (connected) KovaPalette.Emerald else KovaPalette.Amber)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = workstationName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (connected) KovaPalette.Emerald.copy(alpha = 0.14f) else KovaPalette.Amber.copy(alpha = 0.16f)
            ) {
                Text(
                    text = if (connected) (latencyMs?.let { "${it}ms" } ?: "Live") else "Reconnecting...",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected) KovaPalette.Emerald else KovaPalette.Amber,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        IconButton(onClick = onOpenComposer, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = "Send Prompt", tint = KovaPalette.Cyan, modifier = Modifier.size(20.dp))
        }
    }
}
