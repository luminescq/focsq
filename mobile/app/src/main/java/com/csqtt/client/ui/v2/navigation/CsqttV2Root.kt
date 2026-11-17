package com.csqtt.client.ui.v2.navigation

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.csqtt.client.AppReleaseInfo
import com.csqtt.client.BuildConfig
import com.csqtt.client.CsqttConstants
import com.csqtt.client.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
import com.csqtt.client.RemoteVersionSource
import com.csqtt.client.SettingsStore
import com.csqtt.client.SubscriptionSync
import com.csqtt.client.TunnelManager
import com.csqtt.client.UPDATE_CHECK_NEVER
import com.csqtt.client.UPDATE_DIALOG_ACTION_POSTPONED
import com.csqtt.client.UPDATE_DIALOG_ACTION_UPDATE
import com.csqtt.client.fetchLatestReleaseInfo
import com.csqtt.client.isNewerVersion
import com.csqtt.client.showRaisedToast
import com.csqtt.client.updateIntervalHoursToMillis
import com.csqtt.client.ui.v2.components.V2BottomBar
import com.csqtt.client.ui.v2.components.V2Tab
import com.csqtt.client.ui.v2.design.CsqttV2Theme
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.design.V2Transitions
import com.csqtt.client.ui.v2.dialogs.V2AppUpdateDialog
import com.csqtt.client.ui.v2.dialogs.V2VersionSheet
import com.csqtt.client.ui.v2.screens.connection.V2ConnectionScreen
import com.csqtt.client.ui.v2.screens.deploy.V2DeployScreen
import com.csqtt.client.ui.v2.screens.logs.V2LogsScreen
import com.csqtt.client.ui.v2.screens.routing.V2RoutingScreen
import com.csqtt.client.ui.v2.screens.settings.V2AboutScreen
import com.csqtt.client.ui.v2.screens.settings.V2SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val OVERLAY_SETTINGS = "settings"
private const val OVERLAY_ABOUT = "about"

private val AppReleaseSaver: Saver<AppReleaseInfo?, Any> = listSaver(
    save = { release ->
        if (release == null) {
            emptyList()
        } else {
            listOf(release.versionTag, release.releaseUrl, release.source.name)
        }
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            AppReleaseInfo(
                versionTag = saved[0] as String,
                releaseUrl = saved[1] as String,
                source = RemoteVersionSource.valueOf(saved[2] as String),
            )
        }
    },
)

@Composable
fun CsqttV2Root(
    settingsStore: SettingsStore,
    onVkAuthRequested: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.Theme.MODE_SYSTEM)
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        CsqttConstants.Theme.MODE_LIGHT -> false
        CsqttConstants.Theme.MODE_DARK -> true
        else -> isSystemDark
    }
    CsqttV2Theme(
        darkTheme = isDark,
        themePalette = "focsq",
    ) {
        val colors = V2Theme.colors
        val context = LocalContext.current.applicationContext
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val participantMode by settingsStore.csqttLinkMode.collectAsStateWithLifecycle(initialValue = false)
        val tabs = remember(participantMode) {
            V2Tab.entries.filter { tab ->
                when (tab) {
                    V2Tab.Deploy -> !participantMode
                    else -> true
                }
            }
        }
        var selectedTabName by rememberSaveable { mutableStateOf(V2Tab.Connection.name) }
        val tabStateHolder = rememberSaveableStateHolder()
        var overlay by rememberSaveable { mutableStateOf<String?>(null) }
        var profilePickerRequest by rememberSaveable { mutableIntStateOf(0) }
        var pendingRelease by rememberSaveable(stateSaver = AppReleaseSaver) {
            mutableStateOf<AppReleaseInfo?>(null)
        }
        var showVersionSheet by rememberSaveable { mutableStateOf(false) }
        val version = remember { "V ${BuildConfig.VERSION_NAME.removePrefix("v")}" }
        val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
        val selectedTab = remember(tabs, selectedTabName) {
            tabs.firstOrNull { it.name == selectedTabName } ?: tabs.first()
        }

        LaunchedEffect(selectedTab) {
            selectedTabName = selectedTab.name
            if (selectedTab == V2Tab.Logs) TunnelManager.clearUnreadErrors()
        }

        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    SubscriptionSync.refreshDue(settingsStore)
                    delay(30_000L)
                }
            }
        }

        LaunchedEffect(Unit) {
            val intervalHours = settingsStore.updateCheckIntervalHours.first()
            if (intervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

            val intervalMillis = updateIntervalHoursToMillis(intervalHours)
                ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
                ?: 12L * 60L * 60L * 1000L

            suspend fun runUpdateCheck(reason: String) {
                val checkedAt = System.currentTimeMillis()
                val release = fetchLatestReleaseInfo(currentVersion)
                settingsStore.saveUpdateState(
                    lastCheckAt = checkedAt,
                    latestVersion = release?.versionTag ?: "",
                    error = if (release == null) "Не удалось проверить" else "",
                )
                if (release == null) return

                val hasUpdate = isNewerVersion(currentVersion, release.versionTag)
                val postponeVersion = settingsStore.updatePostponeVersion.first()
                val postponeUntil = settingsStore.updatePostponeUntil.first()
                val isPostponed = postponeVersion == release.versionTag && checkedAt < postponeUntil
                if (hasUpdate && !isPostponed) {
                    settingsStore.saveUpdateDialogShown(release.versionTag, checkedAt)
                    pendingRelease = release
                }
            }

            runUpdateCheck("startup")
            while (isActive) {
                val now = System.currentTimeMillis()
                val lastCheck = settingsStore.updateLastCheckAt.first()
                val waitMs = (lastCheck + intervalMillis - now).coerceAtLeast(intervalMillis)
                delay(waitMs)
                if (isActive) runUpdateCheck("periodic")
            }
        }

        if (showVersionSheet) {
            V2VersionSheet(
                version = version,
                onDismiss = { showVersionSheet = false },
            )
        }

        pendingRelease?.let { release ->
            V2AppUpdateDialog(
                release = release,
                onPostpone = {
                    pendingRelease = null
                    context.showRaisedToast("Обновление отложено на 24 часа.", Toast.LENGTH_SHORT)
                    scope.launch {
                        val now = System.currentTimeMillis()
                        settingsStore.saveUpdatePostpone(
                            version = release.versionTag,
                            until = now + 24L * 60L * 60L * 1000L,
                        )
                        settingsStore.saveUpdateDialogAction(
                            version = release.versionTag,
                            action = UPDATE_DIALOG_ACTION_POSTPONED,
                            actedAt = now,
                        )
                    }
                },
                onUpdate = {
                    pendingRelease = null
                    scope.launch {
                        settingsStore.saveUpdateDialogAction(
                            version = release.versionTag,
                            action = UPDATE_DIALOG_ACTION_UPDATE,
                            actedAt = System.currentTimeMillis(),
                        )
                        openReleaseUrl(context, release.releaseUrl)
                    }
                },
            )
        }

        BackHandler(enabled = overlay != null) {
            overlay = when (overlay) {
                OVERLAY_ABOUT -> OVERLAY_SETTINGS
                else -> null
            }
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AnimatedContent(
                targetState = overlay,
                transitionSpec = {
                    val initialDepth = when (initialState) {
                        OVERLAY_ABOUT -> 2
                        OVERLAY_SETTINGS -> 1
                        else -> 0
                    }
                    val targetDepth = when (targetState) {
                        OVERLAY_ABOUT -> 2
                        OVERLAY_SETTINGS -> 1
                        else -> 0
                    }
                    V2Transitions.overlayTransition(isDeeper = targetDepth > initialDepth)
                },
                label = "v2_overlay_transition",
                modifier = Modifier.fillMaxSize(),
            ) { currentOverlay ->
                when (currentOverlay) {
                    OVERLAY_SETTINGS -> V2SettingsScreen(
                        settingsStore = settingsStore,
                        version = version,
                        onBack = { overlay = null },
                        onAbout = { overlay = OVERLAY_ABOUT },
                        onVkAuthRequested = onVkAuthRequested,
                    )

                    OVERLAY_ABOUT -> V2AboutScreen(
                        version = version,
                        onBack = { overlay = OVERLAY_SETTINGS },
                    )

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = { V2Transitions.tabTransition() },
                                label = "v2_tab_transition",
                                modifier = Modifier.fillMaxSize(),
                            ) { tab ->
                                tabStateHolder.SaveableStateProvider(key = tab.name) {
                                    when (tab) {
                                        V2Tab.Connection -> V2ConnectionScreen(
                                            settingsStore = settingsStore,
                                            version = version,
                                            onSettings = { overlay = OVERLAY_SETTINGS },
                                            onVersionClick = { showVersionSheet = true },
                                            profilePickerRequest = profilePickerRequest,
                                            onProfilePickerRequestHandled = {
                                                profilePickerRequest = 0
                                            },
                                            onInvalidConfiguration = {
                                                selectedTabName = V2Tab.Connection.name
                                                profilePickerRequest += 1
                                            },
                                        )

                                        V2Tab.Logs -> V2LogsScreen(
                                            version = version,
                                            onSettings = { overlay = OVERLAY_SETTINGS },
                                            onVersionClick = { showVersionSheet = true },
                                        )

                                        V2Tab.Deploy -> V2DeployScreen(
                                            settingsStore = settingsStore,
                                            version = version,
                                            onSettings = { overlay = OVERLAY_SETTINGS },
                                            onVersionClick = { showVersionSheet = true },
                                        )

                                        V2Tab.Routing -> V2RoutingScreen(
                                            settingsStore = settingsStore,
                                            version = version,
                                            onSettings = { overlay = OVERLAY_SETTINGS },
                                            onVersionClick = { showVersionSheet = true },
                                        )
                                    }
                                }
                            }
                        }
                        V2BottomBar(
                            selected = selectedTab,
                            onSelect = { tab ->
                                selectedTabName = tab.name
                            },
                            tabs = tabs,
                            modifier = Modifier.padding(
                                horizontal = V2Sizes.ScreenPadding,
                                vertical = 8.dp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun openReleaseUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        context.showRaisedToast("Не удалось открыть ссылку", Toast.LENGTH_SHORT)
    }
}
