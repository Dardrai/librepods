package me.kavishdevar.librepods.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.database.app.AppSettingsDao
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.database.app.AppStateDao
import me.kavishdevar.librepods.database.app.AppStateEntity
import org.junit.Assert.*
import org.junit.Test

class AppDataRepositoryTest {
    private class SettingsDao : AppSettingsDao {
        val saved = MutableStateFlow(AppSettingsEntity())
        var beforeWrite: suspend (AppSettingsEntity) -> Unit = {}
        override suspend fun get() = saved.value
        override suspend fun upsert(settings: AppSettingsEntity) {
            beforeWrite(settings)
            saved.value = settings
        }
    }

    private class StateDao : AppStateDao {
        val saved = MutableStateFlow(AppStateEntity())
        var beforeWrite: suspend (AppStateEntity) -> Unit = {}
        override suspend fun get() = saved.value
        override suspend fun upsert(state: AppStateEntity) {
            beforeWrite(state)
            saved.value = state
        }
    }

    @Test fun rapidChangesNeverWriteNewerRowsAheadOfAnOlderBlockedWrite() = runBlocking {
        withTimeout(10_000) {
            val settings = SettingsDao()
            val state = StateDao()
            val repository = AppDataRepository(settings, state)
            repository.awaitInitialized()
            val settingsEntered = CompletableDeferred<Unit>()
            val stateEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            settings.beforeWrite = { if (it.debugMode) { settingsEntered.complete(Unit); release.await() } }
            state.beforeWrite = { if (it.reviewPrompted) { stateEntered.complete(Unit); release.await() } }
            try {
                repository.updateSettings { it.copy(debugMode = true) }
                repository.updateState { it.copy(reviewPrompted = true) }
                settingsEntered.await()
                stateEntered.await()
                repository.updateSettings { it.copy(debugMode = false, useHighestRefreshRate = true) }
                repository.updateState { it.copy(reviewPrompted = false, hasConnectedToAACP = true) }
                delay(100)
                assertFalse("New settings must not pass a blocked older write", settings.saved.value.useHighestRefreshRate)
                assertFalse("New state must not pass a blocked older write", state.saved.value.hasConnectedToAACP)
            } finally { release.complete(Unit) }
            settings.saved.first { it == repository.settings.value }
            state.saved.first { it == repository.state.value }
            assertTrue(settings.saved.value.useHighestRefreshRate)
            assertTrue(state.saved.value.hasConnectedToAACP)
        }
    }

    @Test fun concurrentStateTransformsKeepEveryUpdateAndPersistTheFinalRow() = runBlocking {
        withTimeout(10_000) {
            val settings = SettingsDao()
            val state = StateDao()
            val repository = AppDataRepository(settings, state)
            repository.awaitInitialized()
            val start = CompletableDeferred<Unit>()
            val updates = (1..32).map {
                async(Dispatchers.Default) {
                    start.await()
                    repository.updateState {
                        Thread.sleep(1)
                        it.copy(timeUntilFOSSPremiumExpiry = it.timeUntilFOSSPremiumExpiry + 1)
                    }
                }
            }
            start.complete(Unit)
            updates.awaitAll()
            assertEquals(32L, repository.state.value.timeUntilFOSSPremiumExpiry)
            state.saved.first { it.timeUntilFOSSPremiumExpiry == 32L }
            Unit
        }
    }
}
