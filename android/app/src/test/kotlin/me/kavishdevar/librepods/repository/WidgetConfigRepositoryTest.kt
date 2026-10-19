package me.kavishdevar.librepods.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.database.widget.WidgetConfigDao
import me.kavishdevar.librepods.database.widget.WidgetConfigEntity
import org.junit.Assert.*
import org.junit.Test

class WidgetConfigRepositoryTest {
    private val first = WidgetConfigEntity(11, MacAddress("00:11:22:33:44:55"))
    private val second = first.copy(macAddress = MacAddress("00:11:22:33:44:66"))

    @Test fun startupLoadCannotEraseASaveAndAcknowledgementWaitsForStorage() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dao = Rows(listOf(first))
            dao.beforeRead = { entered.complete(Unit); release.await() }
            val repository = WidgetConfigRepository(dao)
            entered.await()
            val save = async(start = CoroutineStart.UNDISPATCHED) { repository.setWidgetConfig(second) }
            assertFalse("A configuration cannot acknowledge a queued write", save.isCompleted)
            assertEquals(first, dao.rows.single())
            dao.beforeRead = {}
            release.complete(Unit)
            save.await()
            assertEquals(second, dao.rows.single())
            assertEquals(listOf(second), repository.widgetConfigs.value)
            assertEquals(second, repository.getWidgetConfig(11))
        }
    }

    @Test fun rapidWritesCannotPersistTheOlderSelectionAfterTheNewerSelection() = runBlocking {
        withTimeout(10_000) {
            val dao = Rows()
            val repository = WidgetConfigRepository(dao)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            dao.beforeWrite = { if (it == first) { entered.complete(Unit); release.await() } }
            val older = async(start = CoroutineStart.UNDISPATCHED) { repository.setWidgetConfig(first) }
            entered.await()
            val newer = async(start = CoroutineStart.UNDISPATCHED) { repository.setWidgetConfig(second) }
            assertFalse(newer.isCompleted)
            assertTrue(dao.rows.isEmpty())
            release.complete(Unit)
            older.await()
            newer.await()
            assertEquals(listOf(second), dao.rows)
            assertEquals(listOf(second), repository.widgetConfigs.value)
            assertEquals(second, repository.getWidgetConfig(11))
        }
    }

    private class Rows(var rows: List<WidgetConfigEntity> = emptyList()) : WidgetConfigDao {
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend (WidgetConfigEntity) -> Unit = {}
        override suspend fun getAll(): List<WidgetConfigEntity> {
            val snapshot = rows.toList()
            beforeRead()
            return snapshot
        }
        override suspend fun getWidgetById(appWidgetId: Int) = rows.singleOrNull { it.appWidgetId == appWidgetId }
        override suspend fun upsert(widgetConfig: WidgetConfigEntity) {
            beforeWrite(widgetConfig)
            rows = rows.filterNot { it.appWidgetId == widgetConfig.appWidgetId } + widgetConfig
        }
    }
}
