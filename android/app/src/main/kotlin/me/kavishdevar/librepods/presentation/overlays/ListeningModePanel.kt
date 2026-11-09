/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.appleGlassSurface
import me.kavishdevar.librepods.presentation.icons.LocalIcons

@Composable
internal fun ListeningModePanel(snapshot: ListeningModeSnapshot?, commandFailed: Boolean = false,
    onMode: (Int) -> Unit, onStrength: (Int) -> Unit, onToggle: (QuickSetting, Boolean) -> Unit,
    modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    val backdrop = rememberLayerBackdrop()
    val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(160.dp)
    val closeLabel = stringResource(R.string.quick_settings_close)
    Box(modifier.fillMaxWidth().heightIn(max = maximumHeight).clip(RoundedCornerShape(32.dp))
        .testTag("listening-mode-panel")) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop)
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.18f)))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(LocalIcons.current.Headphones, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                Text(snapshot?.metadata?.name ?: stringResource(R.string.app_name),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                StyledButton(onClick = onDismiss, backdrop = backdrop,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = closeLabel }) {
                    Icon(LocalIcons.current.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.noise_control), Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ListeningModeContent(snapshot, commandFailed, onMode, onStrength)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val twoColumns = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.2f
                val states = snapshot?.quickSettings.orEmpty().associateBy { it.setting }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pairs = listOf(
                        listOf(QuickSetting.CONVERSATION_AWARENESS, QuickSetting.PERSONALIZED_VOLUME),
                        listOf(QuickSetting.EAR_DETECTION, QuickSetting.GAME_MODE),
                        listOf(QuickSetting.LOUD_SOUND_REDUCTION),
                        listOf(QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST))
                    for (pair in pairs) {
                        if (twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (setting in pair) QuickSettingsToggle(
                                states[setting] ?: QuickSettingState(setting, null, supported = true, enabled = false, reading = true),
                                backdrop, onToggle, Modifier.weight(1f), compact = pair.size > 1)
                        } else for (setting in pair) QuickSettingsToggle(
                            states[setting] ?: QuickSettingState(setting, null, supported = true, enabled = false, reading = true),
                            backdrop, onToggle, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSettingsToggle(state: QuickSettingState, backdrop: Backdrop,
    onToggle: (QuickSetting, Boolean) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val title = stringResource(when (state.setting) {
        QuickSetting.CONVERSATION_AWARENESS -> R.string.conversation_awareness
        QuickSetting.EAR_DETECTION -> R.string.ear_detection
        QuickSetting.GAME_MODE -> R.string.native_game_mode
        QuickSetting.PERSONALIZED_VOLUME -> R.string.personalized_volume
        QuickSetting.LOUD_SOUND_REDUCTION -> R.string.loud_sound_reduction
        QuickSetting.HEARING_AID -> R.string.hearing_aid
        QuickSetting.MEDIA_ASSIST -> R.string.media_assist
    })
    val description = stringResource(when {
        !state.supported -> R.string.not_available
        state.pending -> R.string.hearing_mode_pending
        state.requested -> R.string.quick_setting_requested
        state.checked == null && state.reading -> R.string.hearing_modes_unknown
        state.checked == null -> R.string.hearing_mode_unconfirmed
        state.checked -> R.string.on
        else -> R.string.off
    })
    val canCancelGameMode = state.setting == QuickSetting.GAME_MODE && state.checked == true
    val enabled = state.supported && state.enabled && (!state.pending || canCancelGameMode) && state.checked != null
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(if (enabled && pressed) 1f else 0f,
        spring(dampingRatio = 0.9f, stiffness = 700f), label = "quickControlPress")
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (dark) 0.24f else 0.36f)
    val foreground = MaterialTheme.colorScheme.onSurface
    val iconSurface by animateColorAsState(if (state.checked == true) MaterialTheme.colorScheme.primary
        else foreground.copy(alpha = 0.08f), tween(180), label = "quickControlIconGlass")
    val iconColor by animateColorAsState(if (state.checked == true) MaterialTheme.colorScheme.onPrimary
        else foreground, tween(180), label = "quickControlIcon")
    val haptics = LocalHapticFeedback.current
    Row(modifier.heightIn(min = if (compact) 96.dp else 72.dp)
        .appleGlassSurface(backdrop, enabled = state.supported, surfaceColor = color,
            pressProgress = pressProgress, pressScale = 0.018f)
        .triStateToggleable(state = when (state.checked) {
            true -> ToggleableState.On; false -> ToggleableState.Off; null -> ToggleableState.Indeterminate
        }, enabled = enabled, role = Role.Switch, interactionSource = interactions, indication = null) {
            val value = state.checked
            if (value != null) {
                haptics.performHapticFeedback(if (value) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onToggle(state.setting, !value)
            }
        }
        .semantics(mergeDescendants = true) { stateDescription = description }
        .testTag("quick-setting-${state.setting.name}")
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(iconSurface), contentAlignment = Alignment.Center) {
            Icon(quickSettingIcon(state.setting), null, Modifier.size(20.dp), tint = iconColor)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = foreground)
            AnimatedContent(description, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) },
                label = "quickControlState") { value ->
                Text(value, style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = 0.8f))
            }
        }
        if (!compact && state.checked == true) Icon(LocalIcons.current.Check, null, Modifier.size(18.dp), tint = foreground)
    }
}

@Composable
private fun quickSettingIcon(setting: QuickSetting): ImageVector = when (setting) {
    QuickSetting.CONVERSATION_AWARENESS -> QuickSettingIcons.Conversation
    QuickSetting.EAR_DETECTION -> QuickSettingIcons.Ear
    QuickSetting.GAME_MODE -> QuickSettingIcons.Game
    QuickSetting.PERSONALIZED_VOLUME -> LocalIcons.current.SpeakerMax
    QuickSetting.LOUD_SOUND_REDUCTION -> QuickSettingIcons.Protection
    QuickSetting.HEARING_AID -> QuickSettingIcons.Hearing
    QuickSetting.MEDIA_ASSIST -> LocalIcons.current.Headphones
}
