package com.kova.workstation.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.ui.WorkspaceTab
import com.kova.workstation.ui.theme.KovaPalette

/**
 * macOS / visionOS-inspired Floating Frosted-Glass Dock.
 * Replaces the heavy Material3 bottom bar with a sleek translucent glass capsule featuring
 * specular top highlights, glowing app tiles, active macOS indicator dots, and 1-tap access
 * to Stage, Mission Control Collage, Files (Forge), Shell, Live Keyboard, and Settings —
 * even when opened from inside True Fullscreen!
 */
@Composable
fun GlassWorkspaceDock(
    activeTab: WorkspaceTab,
    isFullscreen: Boolean,
    openWindowCount: Int,
    onSelectTab: (WorkspaceTab) -> Unit,
    onOpenCollage: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xE01A263C),
            Color(0xEE0C1322)
        )
    )
    val rimBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.24f),
            Color.White.copy(alpha = 0.06f)
        )
    )

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        shadowElevation = 14.dp,
        border = BorderStroke(1.dp, rimBrush)
    ) {
        Row(
            modifier = Modifier
                .background(glassBrush)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassDockIcon(
                label = "Stage",
                icon = Icons.Rounded.DesktopWindows,
                accent = KovaPalette.Cyan,
                selected = activeTab == WorkspaceTab.STAGE,
                onClick = { onSelectTab(WorkspaceTab.STAGE) }
            )
            GlassDockIcon(
                label = "Apps",
                icon = Icons.Rounded.GridView,
                accent = KovaPalette.Violet,
                selected = false,
                badgeCount = openWindowCount,
                onClick = onOpenCollage
            )
            GlassDockIcon(
                label = "Files",
                icon = Icons.Rounded.FolderOpen,
                accent = KovaPalette.Amber,
                selected = activeTab == WorkspaceTab.FORGE,
                onClick = { onSelectTab(WorkspaceTab.FORGE) }
            )
            GlassDockIcon(
                label = "Shell",
                icon = Icons.Rounded.Terminal,
                accent = KovaPalette.Emerald,
                selected = activeTab == WorkspaceTab.SHELL,
                onClick = { onSelectTab(WorkspaceTab.SHELL) }
            )
            GlassDockIcon(
                label = "Type",
                icon = Icons.Rounded.Keyboard,
                accent = KovaPalette.Cyan,
                selected = false,
                onClick = onOpenKeyboard
            )
            if (isFullscreen) {
                GlassDockIcon(
                    label = "Exit",
                    icon = Icons.Rounded.FullscreenExit,
                    accent = KovaPalette.Rose,
                    selected = false,
                    onClick = onExitFullscreen
                )
            } else {
                GlassDockIcon(
                    label = "Settings",
                    icon = Icons.Rounded.Settings,
                    accent = Color(0xFFCBD5E1),
                    selected = activeTab == WorkspaceTab.SETTINGS,
                    onClick = { onSelectTab(WorkspaceTab.SETTINGS) }
                )
            }
        }
    }
}

@Composable
private fun GlassDockIcon(
    label: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (selected) {
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.14f))
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.03f))
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) accent else Color(0xFFDCE6F4),
                    modifier = Modifier.size(20.dp)
                )
            }

            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badgeCount > 9) "9+" else "$badgeCount",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = KovaPalette.Obsidian
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else Color(0xFFA7B5CA),
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        // Iconic macOS active app indicator dot
        Box(
            modifier = Modifier
                .size(if (selected) 4.dp else 0.dp)
                .clip(CircleShape)
                .background(accent)
        )
    }
}

/**
 * Unobtrusive vertical frosted-glass edge handle resting at the middle-right bezel.
 * NEVER covers Windows's top-right Minimize/Maximize/Close buttons or bottom Send button!
 * One tap fans out the macOS Glass Dock & Stage controls from anywhere (including Fullscreen).
 */
@Composable
fun GlassEdgeHandle(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(end = 3.dp)
            .width(18.dp)
            .height(58.dp)
            .clickable(onClick = onExpand),
        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 6.dp, bottomEnd = 6.dp),
        color = Color(0x78142034),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .clip(CircleShape)
                    .background(KovaPalette.Cyan.copy(alpha = 0.85f))
            )
        }
    }
}
