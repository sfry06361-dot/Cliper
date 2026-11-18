package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.ExportProduceScreen
import com.example.ui.screens.MontageEditorScreen
import com.example.ui.screens.ProjectsLibraryScreen
import com.example.ui.screens.StudioLinkScreen
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.serialization.Serializable

@Serializable
sealed interface ClipCraftRoute {
    @Serializable
    data object StudioLink : ClipCraftRoute

    @Serializable
    data object MontageEditor : ClipCraftRoute

    @Serializable
    data object ExportProduce : ClipCraftRoute

    @Serializable
    data object ProjectsLibrary : ClipCraftRoute
}

private data class NavDestinationItem(
    val route: ClipCraftRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val navItems = listOf(
    NavDestinationItem(
        route = ClipCraftRoute.StudioLink,
        label = "الرابط",
        selectedIcon = Icons.Rounded.Link,
        unselectedIcon = Icons.Outlined.Link,
        testTag = "nav_link"
    ),
    NavDestinationItem(
        route = ClipCraftRoute.MontageEditor,
        label = "المونتاج",
        selectedIcon = Icons.Rounded.ContentCut,
        unselectedIcon = Icons.Outlined.ContentCut,
        testTag = "nav_editor"
    ),
    NavDestinationItem(
        route = ClipCraftRoute.ExportProduce,
        label = "التصدير",
        selectedIcon = Icons.Rounded.RocketLaunch,
        unselectedIcon = Icons.Outlined.RocketLaunch,
        testTag = "nav_export"
    ),
    NavDestinationItem(
        route = ClipCraftRoute.ProjectsLibrary,
        label = "المشاريع",
        selectedIcon = Icons.Rounded.FolderOpen,
        unselectedIcon = Icons.Outlined.FolderOpen,
        testTag = "nav_projects"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipCraftApp(
    viewModel: ClipCraftViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.toastMessage) {
        val msg = uiState.toastMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeToast()
        }
    }

    fun navigateTo(route: ClipCraftRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        val useRail = maxWidth >= 680.dp

        Scaffold(
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = StudioSurface,
                        titleContentColor = TextPrimary
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(CyanContainer, RoundedCornerShape(10.dp))
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MovieFilter,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "كليب كرافت • ClipCraft",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "PRECISION VIDEO STUDIO",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = SpaceGroteskFontFamily
                                    ),
                                    color = ElectricCyan
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = StudioSurface,
                        contentColor = TextPrimary,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = item.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = CyanContainer,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = StudioSurface,
                        contentColor = TextPrimary,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(0.1f))
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = CyanContainer,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ClipCraftRoute.StudioLink,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable<ClipCraftRoute.StudioLink> {
                        StudioLinkScreen(
                            uiState = uiState,
                            onUrlChange = viewModel::updateUrlInput,
                            onInspectAndDownload = viewModel::startInspectAndDownload,
                            onCancelDownload = viewModel::cancelDownload,
                            onPickLocalVideo = viewModel::importLocalVideo,
                            onNavigateToEditor = { navigateTo(ClipCraftRoute.MontageEditor) }
                        )
                    }
                    composable<ClipCraftRoute.MontageEditor> {
                        MontageEditorScreen(
                            uiState = uiState,
                            onSeekPreview = viewModel::seekPreviewTo,
                            onReAnalyzeHighlights = viewModel::reAnalyzeHighlights,
                            onToggleClip = viewModel::toggleClipIncluded,
                            onUpdateClipRange = viewModel::updateClipRange,
                            onUpdateClipSpeed = viewModel::updateClipSpeed,
                            onMoveClipUp = viewModel::moveClipUp,
                            onMoveClipDown = viewModel::moveClipDown,
                            onAddCustomClip = viewModel::addCustomClip,
                            onRemoveClip = viewModel::removeClip,
                            onApplyPacingMode = viewModel::applyPacingMode,
                            onApplyQualityPreset = viewModel::applyQualityPreset,
                            onUpdateQuality = viewModel::updateQualitySettings,
                            onUpdateDetails = viewModel::updateDetailsSpec,
                            onToggleFeature = viewModel::toggleFeature,
                            onActivateProBundle = viewModel::activateProMontageBundle,
                            onGoToLinkTab = { navigateTo(ClipCraftRoute.StudioLink) },
                            onGoToExportTab = { navigateTo(ClipCraftRoute.ExportProduce) }
                        )
                    }
                    composable<ClipCraftRoute.ExportProduce> {
                        ExportProduceScreen(
                            uiState = uiState,
                            onStartExport = viewModel::startExport,
                            onCancelExport = viewModel::cancelExport,
                            onSaveToGallery = viewModel::saveExportedToGallery,
                            onGoToLinkTab = { navigateTo(ClipCraftRoute.StudioLink) }
                        )
                    }
                    composable<ClipCraftRoute.ProjectsLibrary> {
                        ProjectsLibraryScreen(
                            uiState = uiState,
                            onOpenProject = { project ->
                                viewModel.loadSavedProject(project)
                                navigateTo(ClipCraftRoute.MontageEditor)
                            },
                            onDeleteProject = viewModel::deleteSavedProject,
                            onDeleteStoredFile = viewModel::deleteStoredFile
                        )
                    }
                }
            }
        }
    }
}
