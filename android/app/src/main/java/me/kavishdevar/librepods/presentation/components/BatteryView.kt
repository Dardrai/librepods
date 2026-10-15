/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay

@Composable
fun BatteryView(
    batteryList: List<Battery>,
    budsRes: Int,
    caseRes: Int
) {
    val left = batteryList.find { it.component == BatteryComponent.LEFT }
    val right = batteryList.find { it.component == BatteryComponent.RIGHT }
    val case = batteryList.find { it.component == BatteryComponent.CASE }

    val leftLevel = left?.level ?: 0
    val rightLevel = right?.level ?: 0
    val caseLevel = case?.level ?: 0
    val now = produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }.value
    val leftKnown = left?.updatedAt != null
    val rightKnown = right?.updatedAt != null
    val singleDisplayed = leftKnown && rightKnown && left?.isLive(now) == right?.isLive(now) &&
        left?.status == right?.status && (leftLevel - rightLevel) in -3..3

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.widthIn(max = 500.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = ImageBitmap.imageResource(budsRes),
                    contentDescription = stringResource(R.string.buds),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )

                if (singleDisplayed) {
                    BatteryIndicator(
                        leftLevel.coerceAtMost(rightLevel),
                        if (left?.isLive(now) == true) left.status else BatteryStatus.NOT_CHARGING
                    )
                } else {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (leftKnown) {
                            BatteryIndicator(
                                leftLevel,
                                if (left?.isLive(now) == true) left.status else BatteryStatus.NOT_CHARGING,
                                "\uDBC6\uDCE5"
                            )
                        }

                        if (leftKnown && rightKnown) {
                            Spacer(modifier = Modifier.width(16.dp))
                        }

                        if (rightKnown) {
                            BatteryIndicator(
                                rightLevel,
                                if (right?.isLive(now) == true) right.status else BatteryStatus.NOT_CHARGING,
                                "\uDBC6\uDCE8"
                            )
                        }
                    }
                }
                if (!leftKnown && !rightKnown) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BatteryReadingAge(left, now, stringResource(R.string.left))
                BatteryReadingAge(right, now, stringResource(R.string.right))
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = ImageBitmap.imageResource(caseRes),
                    contentDescription = stringResource(R.string.case_alt),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )

                if (case?.updatedAt != null) {
                    BatteryIndicator(
                        caseLevel,
                        if (case.isLive(now)) case.status else BatteryStatus.NOT_CHARGING,
                        prefix = if (!singleDisplayed) "\uDBC3\uDE6C" else ""
                    )
                } else Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BatteryReadingAge(case, now, "")
            }
        }
    }
}

@Composable
private fun BatteryReadingAge(battery: Battery?, now: Long, prefix: String) {
    val at = battery?.updatedAt ?: return
    if (battery.isLive(now)) return
    Text(
        text = listOf(prefix, stringResource(R.string.battery_cached_age, ((now - at) / 60_000).coerceAtLeast(0)))
            .filter { it.isNotEmpty() }.joinToString(": "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BatteryViewPreview() {
    val fakeBattery = listOf(
        Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING),
        Battery(BatteryComponent.RIGHT, 40, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING)
    )

    val bg = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)

    Box(
        modifier = Modifier
            .background(bg)
            .padding(16.dp)
    ) {
        BatteryView(
            batteryList = fakeBattery,
            budsRes = R.drawable.airpods_pro_2_buds,
            caseRes = R.drawable.airpods_pro_2_case
        )
    }
}
