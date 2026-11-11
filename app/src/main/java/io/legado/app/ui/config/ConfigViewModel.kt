package io.legado.app.ui.config

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.syncv1.AppWebDavSyncV1SessionFactory
import io.legado.app.help.syncv1.SyncV1DeviceOutcome
import io.legado.app.help.syncv1.SyncV1ErrorCode
import io.legado.app.help.syncv1.SyncV1RegistrationRepository
import io.legado.app.help.syncv1.SyncV1SQLiteStore
import io.legado.app.help.syncv1.SyncV1UiPhase
import io.legado.app.help.syncv1.SyncV1UiState
import io.legado.app.utils.FileUtils
import io.legado.app.utils.restart
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import splitties.init.appCtx

class ConfigViewModel(application: Application) : BaseViewModel(application) {

    private var webDavUpdateJob: Job? = null

    private val syncV1Repository by lazy {
        SyncV1RegistrationRepository(
            store = SyncV1SQLiteStore(context),
            sessionProvider = AppWebDavSyncV1SessionFactory()::create
        )
    }
    private val _syncV1UiState = MutableStateFlow(SyncV1UiState())
    val syncV1UiState = _syncV1UiState.asStateFlow()
    private var syncV1Job: Job? = null

    fun upWebDavConfig() {
        webDavUpdateJob?.cancel()
        webDavUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            AppWebDav.upConfig()
        }
    }

    fun processSyncV1Registration() {
        if (syncV1Job?.isActive == true) return
        _syncV1UiState.value = SyncV1UiState(
            isRunning = true,
            currentPhase = SyncV1UiPhase.SCANNING
        )
        syncV1Job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = syncV1Repository.processRegistrations { outcome ->
                    _syncV1UiState.update { current ->
                        val updatedOutcomes = if (outcome.deviceId == null) {
                            current.outcomes
                        } else {
                            current.outcomes.filterNot { it.deviceId == outcome.deviceId } + outcome
                        }
                        current.copy(
                            currentPhase = outcome.phase,
                            outcomes = updatedOutcomes,
                            globalErrorCode = if (outcome.deviceId == null) outcome.errorCode
                            else current.globalErrorCode
                        )
                    }
                }
                _syncV1UiState.update { current ->
                    current.copy(
                        isRunning = false,
                        currentPhase = results.lastOrNull()?.phase ?: SyncV1UiPhase.IDLE,
                        outcomes = mergeOutcomes(current.outcomes, results),
                        globalErrorCode = results.lastOrNull()
                            ?.takeIf { it.deviceId == null }
                            ?.errorCode
                    )
                }
            } finally {
                _syncV1UiState.update { it.copy(isRunning = false) }
            }
        }
    }

    private fun mergeOutcomes(
        current: List<SyncV1DeviceOutcome>,
        results: List<SyncV1DeviceOutcome>
    ): List<SyncV1DeviceOutcome> {
        val merged = current.toMutableList()
        results.forEach { result ->
            if (result.deviceId == null) return@forEach
            val index = merged.indexOfFirst { it.deviceId == result.deviceId }
            if (index >= 0) merged[index] = result else merged += result
        }
        val global = results.lastOrNull { it.deviceId == null }
        if (global != null && merged.isEmpty()) return listOf(global)
        return merged
    }

    fun clearCache() {
        execute {
            BookHelp.clearCache()
            FileUtils.delete(context.cacheDir.absolutePath)
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

    fun clearWebViewData() {
        execute {
            FileUtils.delete(context.getDir("webview", Context.MODE_PRIVATE))
            FileUtils.delete(context.getDir("hws_webview", Context.MODE_PRIVATE), true)
            context.toastOnUi(R.string.clear_webview_data_success)
            delay(3000)
            appCtx.restart()
        }
    }

    fun shrinkDatabase() {
        execute {
            appDb.openHelper.writableDatabase.execSQL("VACUUM")
        }.onSuccess {
            context.toastOnUi(R.string.success)
        }
    }

}
