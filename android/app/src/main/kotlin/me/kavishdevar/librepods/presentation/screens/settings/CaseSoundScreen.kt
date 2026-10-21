package me.kavishdevar.librepods.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.cases.CaseSoundRoute
import me.kavishdevar.librepods.bluetooth.cases.CaseSoundViewModel
import me.kavishdevar.librepods.bluetooth.cases.CaseSoundState
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem

@Composable
fun CaseSoundScreen(navigateBack: (() -> Unit)?) {
    val context = LocalContext.current.applicationContext
    val controller: CaseSoundViewModel = viewModel(factory = viewModelFactory { initializer { CaseSoundViewModel(context) } })
    val state by controller.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.stop() }
    }
    CaseSoundContent(state, controller, navigateBack)
}

@Composable
internal fun CaseSoundContent(state: CaseSoundState, controller: CaseSoundViewModel, navigateBack: (() -> Unit)?) {
    var confirmed by remember { mutableStateOf(false) }

    StyledScaffold(title = stringResource(R.string.case_sound_title), navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(top = top, bottom = bottom)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.case_sound_experimental), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.case_sound_limits), style = MaterialTheme.typography.bodyMedium)

            StyledList(title = stringResource(R.string.case_sound_route)) {
                CaseSoundRoute.entries.forEach { route ->
                    styledListItem(contentText = route.label, enabled = !state.busy,
                        selected = state.route == route, onClick = { controller.route(route) },
                        trailingContent = { if (state.route == route) Text(stringResource(R.string.case_sound_selected)) })
                }
            }
            StyledButton(Modifier.fillMaxWidth(), enabled = !state.busy, onClick = { confirmed = false; controller.find() }) {
                Text(stringResource(R.string.case_sound_find))
            }
            if (state.ready) {
                StyledList {
                    styledToggle(label = stringResource(R.string.case_sound_confirm), checked = confirmed,
                        enabled = !state.busy, onCheckedChange = { confirmed = it })
                }
                StyledButton(Modifier.fillMaxWidth(), enabled = confirmed && !state.busy, onClick = controller::start) {
                    Text(stringResource(R.string.case_sound_start))
                }
                if (state.canPair) StyledButton(Modifier.fillMaxWidth(), enabled = !state.busy, onClick = controller::pair) {
                    Text(stringResource(R.string.case_sound_pair))
                }
            }
            if (!state.busy) StyledButton(Modifier.fillMaxWidth(), enabled = false, onClick = controller::stop) {
                Text(stringResource(R.string.case_sound_stop))
            }
            if (state.outcomeEnabled) {
                Text(stringResource(R.string.case_sound_outcome), style = MaterialTheme.typography.titleMedium)
                listOf("heard" to R.string.case_sound_heard, "silent" to R.string.case_sound_silent,
                    "unsure" to R.string.case_sound_unsure).forEach { (outcome, label) ->
                    StyledButton(Modifier.fillMaxWidth(), onClick = { controller.heard(outcome) }) { Text(stringResource(label)) }
                }
            }
            if (state.entries.isNotEmpty()) {
                Text(stringResource(R.string.case_sound_results), style = MaterialTheme.typography.titleMedium)
                Text(state.entries.joinToString("\n\n"), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (state.busy) StyledButton(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = controller::stop) {
            Text(stringResource(R.string.case_sound_stop))
        }
        }
    }
}
