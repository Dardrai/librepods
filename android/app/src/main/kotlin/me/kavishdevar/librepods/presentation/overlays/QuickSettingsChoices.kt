/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.kyant.backdrop.Backdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.appleGlassSurface
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import androidx.compose.ui.unit.dp

internal val quickSettingsOrder = listOf(QuickSetting.CONVERSATION_AWARENESS, QuickSetting.PERSONALIZED_VOLUME,
    QuickSetting.EAR_DETECTION, QuickSetting.GAME_MODE, QuickSetting.LOUD_SOUND_REDUCTION,
    QuickSetting.EN352_PROTECTION, QuickSetting.HEARING_AID, QuickSetting.MEDIA_ASSIST)

/** Presentation preferences only: these callbacks never change the AirPods settings. */
@Composable
internal fun QuickSettingsChoices(settings: List<QuickSetting>, hiddenSettings: Set<QuickSetting>,
    onVisibilityChanged: (QuickSetting, Boolean) -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (setting in settings) {
            val visible = setting !in hiddenSettings
            val interactions = remember(setting) { MutableInteractionSource() }
            val pressed by interactions.collectIsPressedAsState()
            val pressProgress by animateFloatAsState(if (pressed) 1f else 0f,
                spring(dampingRatio = 0.9f, stiffness = 700f), label = "quickVisibilityPress")
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .appleGlassSurface(backdrop, surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    .copy(alpha = if (dark) 0.24f else 0.36f), pressProgress = pressProgress, pressScale = 0.018f)
                .toggleable(visible, interactionSource = interactions, indication = null, role = Role.Checkbox,
                    onValueChange = { onVisibilityChanged(setting, it) })
                .testTag("quick-setting-choice-${setting.name}")
                .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(foreground.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center) {
                    Icon(quickSettingIcon(setting), null, Modifier.size(20.dp), tint = foreground)
                }
                Text(stringResource(quickSettingLabel(setting)), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = foreground)
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (visible) MaterialTheme.colorScheme.primary
                    else foreground.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    if (visible) Icon(LocalIcons.current.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

internal fun quickSettingLabel(setting: QuickSetting): Int = when (setting) {
    QuickSetting.CONVERSATION_AWARENESS -> R.string.conversation_awareness
    QuickSetting.EAR_DETECTION -> R.string.ear_detection
    QuickSetting.GAME_MODE -> R.string.native_game_mode
    QuickSetting.PERSONALIZED_VOLUME -> R.string.personalized_volume
    QuickSetting.LOUD_SOUND_REDUCTION -> R.string.loud_sound_reduction
    QuickSetting.EN352_PROTECTION -> R.string.ppe
    QuickSetting.HEARING_AID -> R.string.hearing_aid
    QuickSetting.MEDIA_ASSIST -> R.string.media_assist
}

@Composable
internal fun quickSettingIcon(setting: QuickSetting): ImageVector = when (setting) {
    QuickSetting.CONVERSATION_AWARENESS -> QuickSettingIcons.Conversation
    QuickSetting.EAR_DETECTION -> QuickSettingIcons.Ear
    QuickSetting.GAME_MODE -> QuickSettingIcons.Game
    QuickSetting.PERSONALIZED_VOLUME -> LocalIcons.current.SpeakerMax
    QuickSetting.LOUD_SOUND_REDUCTION -> QuickSettingIcons.Protection
    QuickSetting.EN352_PROTECTION -> QuickSettingIcons.Protection
    QuickSetting.HEARING_AID -> QuickSettingIcons.Hearing
    QuickSetting.MEDIA_ASSIST -> LocalIcons.current.Headphones
}
