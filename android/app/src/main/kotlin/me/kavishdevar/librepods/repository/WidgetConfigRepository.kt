package me.kavishdevar.librepods.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kavishdevar.librepods.database.widget.WidgetConfigDao
import me.kavishdevar.librepods.database.widget.WidgetConfigEntity

class WidgetConfigRepository(
    private val widgetConfigDao: WidgetConfigDao
) {
    private val _widgetConfigs = MutableStateFlow<List<WidgetConfigEntity>>(emptyList())
    val widgetConfigs: StateFlow<List<WidgetConfigEntity>> = _widgetConfigs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    init {
        scope.launch {
            lock.withLock { _widgetConfigs.value = widgetConfigDao.getAll() }
        }
    }

    suspend fun getWidgetConfig(appWidgetId: Int): WidgetConfigEntity? =
        lock.withLock { widgetConfigDao.getWidgetById(appWidgetId) }

    suspend fun setWidgetConfig(widgetConfig: WidgetConfigEntity) {
        lock.withLock {
            widgetConfigDao.upsert(widgetConfig)
            _widgetConfigs.value = widgetConfigDao.getAll()
        }
    }
}
