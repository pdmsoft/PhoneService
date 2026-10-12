package com.phoneservice

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoneservice.call.CallArchiveService
import com.phoneservice.call.CallEntry
import com.phoneservice.call.CallLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class CallLogUiState(
    val permitted: Boolean = false,
    val needsSettings: Boolean = false,
    val calls: List<CallEntry> = emptyList(),
    val savedAtMillis: Long = 0,
    val ignoringBattery: Boolean = false,
    val syncing: Boolean = false,
)

data class Notice(
    val text: String,
    val id: Long = System.nanoTime(),
)

class CallLogViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(
        CallLogUiState(
            permitted = CallLogRepository.hasPermission(app),
            ignoringBattery = isIgnoringBattery(app),
        ),
    )
    val state: StateFlow<CallLogUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val syncDepth = AtomicInteger(0)
    private val onArchiveChanged: () -> Unit = {
        viewModelScope.launch(Dispatchers.IO) {
            publish(CallLogRepository.load(getApplication()))
        }
    }

    init {
        CallLogRepository.addOnChanged(onArchiveChanged)
        viewModelScope.launch(Dispatchers.IO) {
            publish(CallLogRepository.load(getApplication()))
        }
    }

    override fun onCleared() {
        CallLogRepository.removeOnChanged(onArchiveChanged)
        super.onCleared()
    }

    fun onHostResume(needsSettings: Boolean) {
        val app = getApplication<Application>()
        val permitted = CallLogRepository.hasPermission(app)
        _state.update {
            it.copy(
                permitted = permitted,
                needsSettings = needsSettings,
                ignoringBattery = isIgnoringBattery(app),
            )
        }
        if (permitted) {
            CallArchiveService.start(app, syncNow = false)
        } else {
            CallArchiveService.stop(app)
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!permitted) {
                publish(CallLogRepository.load(app))
                return@launch
            }
            syncArchive()
        }
    }

    fun onPermissionGranted() {
        val app = getApplication<Application>()
        _state.update { it.copy(permitted = true, needsSettings = false) }
        CallArchiveService.start(app, syncNow = true)
        viewModelScope.launch(Dispatchers.IO) {
            syncArchive()
        }
    }

    fun refresh() {
        val app = getApplication<Application>()
        if (!CallLogRepository.hasPermission(app)) return
        CallArchiveService.start(app, syncNow = false)
        viewModelScope.launch(Dispatchers.IO) {
            syncArchive()
        }
    }

    fun prepareEmail(onReady: (Uri) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val ready = runCatching { CallLogRepository.shareCsv(app) }
            withContext(Dispatchers.Main) {
                ready.fold(
                    onSuccess = onReady,
                    onFailure = {
                        _notice.value = Notice(app.getString(R.string.email_failed))
                    },
                )
            }
        }
    }

    fun exportTo(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { CallLogRepository.exportCsv(app, uri) }
            _notice.value = Notice(
                app.getString(if (saved.isSuccess) R.string.exported else R.string.export_failed),
            )
        }
    }

    fun showNotice(text: String) {
        _notice.value = Notice(text)
    }

    fun consumeNotice() {
        _notice.value = null
    }

    private fun syncArchive() {
        val app = getApplication<Application>()
        withSyncing {
            try {
                publish(CallLogRepository.sync(app))
            } catch (error: Exception) {
                Log.w(tag, "sync failed: ${error.javaClass.simpleName}")
                publish(CallLogRepository.load(app))
                _notice.value = Notice(app.getString(R.string.sync_failed))
            }
        }
    }

    private fun publish(archive: com.phoneservice.call.CallArchive) {
        _state.update {
            it.copy(
                calls = archive.calls.distinctBy { entry -> entry.archiveKey },
                savedAtMillis = archive.savedAtMillis,
            )
        }
    }

    private inline fun withSyncing(block: () -> Unit) {
        if (syncDepth.incrementAndGet() == 1) {
            _state.update { it.copy(syncing = true) }
        }
        try {
            block()
        } finally {
            if (syncDepth.decrementAndGet() == 0) {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    companion object {
        private const val tag = "CallArchive"

        private fun isIgnoringBattery(context: Context): Boolean {
            val power = context.getSystemService(PowerManager::class.java) ?: return false
            return power.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}
