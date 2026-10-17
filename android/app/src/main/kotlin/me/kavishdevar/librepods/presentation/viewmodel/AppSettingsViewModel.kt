package me.kavishdevar.librepods.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import me.kavishdevar.librepods.data.xposed.XposedRemotePrefProvider
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.database.app.AppStateEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.services.LibrePodsAccessibilityService

data class AppUiState(
    val state: AppStateEntity = AppStateEntity(),
    val settings: AppSettingsEntity = AppSettingsEntity(),

    val accessibilityServiceAvailable: Boolean = false,
    val vendorIdHook: Boolean = false,

    val showResetDialog: Boolean = false,
)

class AppSettingsViewModel(
    private val appDataRepository: AppDataRepository,
) : ViewModel() {

    private val xposedRemotePref = XposedRemotePrefProvider.create()

    private val vendorIdHook = MutableStateFlow(
        xposedRemotePref.getBoolean("vendor_id_hook", false)
    )

    private var _showResetDialog = MutableStateFlow(false)

    private val appData = combine(
        appDataRepository.state,
        appDataRepository.settings
    ) { appState, appSettings ->
        Pair(appState, appSettings)
    }

    private val extras = combine(
        vendorIdHook,
        LibrePodsAccessibilityService.instance,
    ) {
        Pair(vendorIdHook, LibrePodsAccessibilityService.instance)
    }

    val uiState = combine(
        appData,
        extras,
        _showResetDialog
    ) { (state, settings), (vendorIdHook, accessibilityService), _showResetDialog ->
        AppUiState(
            state = state,
            settings = settings,
            vendorIdHook = vendorIdHook.value,
            accessibilityServiceAvailable = accessibilityService.value != null,
            showResetDialog = _showResetDialog
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppUiState(
            state = appDataRepository.state.value,
            settings = appDataRepository.settings.value,
            vendorIdHook = vendorIdHook.value,
            accessibilityServiceAvailable = LibrePodsAccessibilityService.instance.value != null,
            showResetDialog = _showResetDialog.value
        )
    )

    fun updateSettings(
        transform: (AppSettingsEntity) -> AppSettingsEntity
    ) = appDataRepository.updateSettings(transform)

    fun updateState(
        transform: (AppStateEntity) -> AppStateEntity
    ) = appDataRepository.updateState(transform)

    fun setVendorIdHook(enabled: Boolean) {
        xposedRemotePref.putBoolean("vendor_id_hook", enabled)
        vendorIdHook.value = enabled
    }

    fun showResetDialog() {
        _showResetDialog.value = true
    }

    fun resetDialogDismissed() {
        _showResetDialog.value = false
    }

    fun resetAppSettings() {
        appDataRepository.resetAppSettings()
        _showResetDialog.value = false
    }
}
