package me.kavishdevar.librepods.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.database.app.AppSettingsDao
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.database.app.AppStateDao
import me.kavishdevar.librepods.database.app.AppStateEntity

class AppDataRepository(
    private val settingsDao: AppSettingsDao,
    private val stateDao: AppStateDao,
    private val rememberLocalTheme: () -> Boolean = { true },
    private val onSettingsSaveFailed: () -> Unit = {},
) {
    private val initialized = CompletableDeferred<Unit>()

    suspend fun awaitInitialized() {
        initialized.await()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(AppSettingsEntity())
    private val settingsWriter = Mutex()
    val settings: StateFlow<AppSettingsEntity> = _settings.asStateFlow()

    private val _state = MutableStateFlow(AppStateEntity())
    val state: StateFlow<AppStateEntity> = _state.asStateFlow()

    init {
        scope.launch {
            try {
                _settings.value = settingsDao.get() ?: AppSettingsEntity()
                _state.value = stateDao.get() ?: AppStateEntity()
                // One writer per row preserves order and coalesces rapid slider changes.
                scope.launch { _settings.collect { value -> settingsWriter.withLock {
                    if (_settings.value == value) settingsDao.upsert(value)
                } } }
                scope.launch { _state.collect { stateDao.upsert(it) } }
                initialized.complete(Unit)
            } catch (error: Exception) {
                initialized.completeExceptionally(error)
                throw error
            }
        }
    }

    fun updateSettings(
        transform: (AppSettingsEntity) -> AppSettingsEntity
    ) {
        _settings.update { old ->
            val next = transform(old)
            if (old.nightMode != next.nightMode && !rememberLocalTheme()) { onSettingsSaveFailed(); old } else next
        }
    }

    fun applySharedTheme(theme: me.kavishdevar.librepods.presentation.design.NightTheme, current: () -> Boolean): Boolean {
        var applied = false
        _settings.update { if (current()) { applied = true; it.copy(nightMode = theme) } else { applied = false; it } }
        return applied
    }

    suspend fun awaitSettingsSaved(value: AppSettingsEntity) {
        settingsWriter.withLock {
            check(_settings.value == value) { "App settings changed during synchronization" }
            settingsDao.upsert(value)
            check(_settings.value == value) { "App settings changed during synchronization" }
        }
    }

    fun updateState(
        transform: (AppStateEntity) -> AppStateEntity
    ) {
        _state.update(transform)
    }

    fun resetAppSettings() {
        updateSettings { AppSettingsEntity() }
    }
}
