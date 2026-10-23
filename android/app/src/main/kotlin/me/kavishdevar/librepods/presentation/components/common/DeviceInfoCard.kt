package me.kavishdevar.librepods.presentation.components.common

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.utils.XposedState

@Composable
fun DeviceInfoCard() {
    val sdk = if (Build.VERSION.SDK_INT >= 36) Build.VERSION.SDK_INT_FULL else Build.VERSION.SDK_INT
    StyledList(title = stringResource(R.string.device_info)) {
        styledListItem(
            contentText = stringResource(R.string.manufacturer),
            supportingText = Build.MANUFACTURER,
            enabled = false
        )

        styledListItem(
            contentText = stringResource(R.string.model_number),
            supportingText = Build.MODEL,
            enabled = false
        )

        styledListItem(
            contentText = stringResource(R.string.build_id),
            supportingText = Build.DISPLAY,
            enabled = false
        )

        styledListItem(
            contentText = stringResource(R.string.version),
            supportingText = "${Build.ID} ($sdk)",
            enabled = false
        )

        styledListItem(
            contentText = stringResource(R.string.xposed_available),
            supportingText = if (XposedState.isAvailable) {
                stringResource(R.string.yes)
            } else {
                stringResource(R.string.no)
            },
            enabled = false
        )

        styledListItem(
            contentText = stringResource(R.string.app_enabled_in_xposed),
            supportingText = if (XposedState.bluetoothScopeEnabled) {
                stringResource(R.string.yes)
            } else {
                stringResource(R.string.no)
            },
            enabled = false
        )
    }
}
