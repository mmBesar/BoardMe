package com.mmbesar.boardme.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmbesar.boardme.BoardMeApplication
import com.mmbesar.boardme.R
import com.mmbesar.boardme.data.ColumnEntity
import com.mmbesar.boardme.data.ProjectEntity
import com.mmbesar.boardme.data.TaskEntity
import com.mmbesar.boardme.network.ApiException
import com.mmbesar.boardme.network.CertFetcher
import com.mmbesar.boardme.network.CertInfo
import com.mmbesar.boardme.network.ServerAddress
import com.mmbesar.boardme.security.Connection
import com.mmbesar.boardme.security.Mode
import com.mmbesar.boardme.widget.BoardWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of "Test and connect" on the first-start screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Working : ConnectState
    data class Error(val message: String) : ConnectState

    /** The server uses a certificate Android does not know: ask the user whether to trust it. */
    data class ConfirmCertificate(val info: CertInfo, val draft: Connection) : ConnectState
}

/** How safe the current server connection is, for the "Connection" dialog. */
enum class Security { HTTPS, HTTPS_PINNED, HTTP }

/** Server details that are safe to show (never the token). */
data class ConnectionSummary(
    val address: String,
    val username: String,
    val security: Security,
    val pinnedSha256: String?,
)

/**
 * Holds what the screens show and performs their actions. Screens talk only to
 * this class, never to the network or database directly.
 */
class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app as BoardMeApplication
    private val repo = container.repository
    private val store = container.credentialStore

    // ---- State the screens watch -------------------------------------------------------

    private val _mode = MutableStateFlow(repo.mode)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _selectedProjectId = MutableStateFlow(store.selectedProjectId)

    private val _projectsLoaded = MutableStateFlow(false)

    /** false until the first answer from the database (avoids a flash of "no projects"). */
    val projectsLoaded: StateFlow<Boolean> = _projectsLoaded.asStateFlow()

    val projects: StateFlow<List<ProjectEntity>> = repo.observeProjects()
        .onEach { _projectsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The project on screen: the chosen one, or the first one if the choice is gone. */
    val currentProject: StateFlow<ProjectEntity?> =
        combine(projects, _selectedProjectId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val columns: StateFlow<List<ColumnEntity>> = currentProject.map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeColumns(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val openTasks: StateFlow<List<TaskEntity>> = currentProject.map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTasks(id, open = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val closedTasks: StateFlow<List<TaskEntity>> = currentProject.map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTasks(id, open = false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _busy = MutableStateFlow(false)

    /** true while a change is being sent (the UI shows a thin progress bar). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _refreshing = MutableStateFlow(false)

    /** true while a pull-to-refresh / automatic refresh runs. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _connectState = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connectState: StateFlow<ConnectState> = _connectState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** One-off messages to show in a snackbar. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        if (repo.mode == Mode.KANBOARD) {
            // Refresh the project list when the app opens.
            viewModelScope.launch { refreshQuietly { repo.refreshProjects() } }
        }
        // Whenever another project comes on screen, download its latest content.
        viewModelScope.launch {
            currentProject.map { it?.id }.distinctUntilChanged().collectLatest { id ->
                if (id != null && repo.mode == Mode.KANBOARD) {
                    refreshQuietly { repo.refreshProject(id) }
                }
            }
        }
    }

    // ---- First start: connect or go local -----------------------------------------------------

    /** Tests the login; asks about the certificate if Android does not trust it. */
    fun connect(rawAddress: String, username: String, token: String) {
        attemptConnect(rawAddress, username.trim(), token.trim(), pinnedSha256 = null)
    }

    /** The user looked at the fingerprint and said "trust it". */
    fun confirmCertificate(state: ConnectState.ConfirmCertificate) {
        val draft = state.draft
        attemptConnect(draft.endpoint, draft.username, draft.token, pinnedSha256 = state.info.sha256)
    }

    fun dismissConnectMessage() {
        _connectState.value = ConnectState.Idle
    }

    private fun attemptConnect(rawAddress: String, username: String, token: String, pinnedSha256: String?) {
        viewModelScope.launch {
            _connectState.value = ConnectState.Working

            val parsed = ServerAddress.parse(rawAddress)
            if (parsed == null) {
                _connectState.value = ConnectState.Error(str(R.string.error_bad_address))
                return@launch
            }
            if (username.isEmpty() || token.isEmpty()) {
                _connectState.value = ConnectState.Error(str(R.string.error_missing_login))
                return@launch
            }

            val draft = Connection(parsed.endpoint, username, token, pinnedSha256)
            try {
                repo.testConnection(draft)
                repo.connect(draft)
                _selectedProjectId.value = 0L
                _mode.value = Mode.KANBOARD
                _connectState.value = ConnectState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val untrusted = e.kind == ApiException.Kind.CERTIFICATE
                if (untrusted && pinnedSha256 == null && !parsed.isCleartext) {
                    // Offer to trust the certificate instead of just failing.
                    _connectState.value = try {
                        ConnectState.ConfirmCertificate(CertFetcher.fetch(parsed.host, parsed.port), draft)
                    } catch (fetchError: Exception) {
                        ConnectState.Error(describe(e))
                    }
                } else if (untrusted && pinnedSha256 != null) {
                    _connectState.value = ConnectState.Error(str(R.string.error_certificate_changed))
                } else {
                    _connectState.value = ConnectState.Error(describe(e))
                }
            } catch (e: Exception) {
                _connectState.value = ConnectState.Error(describe(e))
            }
        }
    }

    fun startLocal() = launchOp {
        repo.startLocal(str(R.string.starter_project_name))
        _selectedProjectId.value = 0L
        _mode.value = Mode.LOCAL
    }

    /** Forget this workspace on the phone and go back to the first-start screen. */
    fun resetMode() = launchOp {
        repo.reset()
        _selectedProjectId.value = 0L
        _connectState.value = ConnectState.Idle
        _mode.value = Mode.UNSET
    }

    // ---- Board actions ----------------------------------------------------------------------------------

    fun selectProject(projectId: Long) {
        _selectedProjectId.value = projectId
        store.selectedProjectId = projectId
    }

    /** Pull-to-refresh. */
    fun refreshCurrent() {
        if (repo.mode != Mode.KANBOARD) return
        viewModelScope.launch {
            refreshQuietly {
                repo.refreshProjects()
                currentProject.value?.let { repo.refreshProject(it.id) }
            }
            updateWidgets()
        }
    }

    fun createTask(columnId: Long, title: String, description: String, dueDate: String?) = launchOp {
        val project = currentProject.value ?: return@launchOp
        repo.createTask(project.id, columnId, title, description, dueDate)
    }

    fun updateTask(task: TaskEntity, title: String, description: String, dueDate: String?) = launchOp {
        repo.updateTask(task, title, description, dueDate)
    }

    fun moveTask(task: TaskEntity, columnId: Long, index: Int) = launchOp {
        repo.moveTask(task, columnId, index)
    }

    fun closeTask(task: TaskEntity) = launchOp { repo.setTaskClosed(task, closed = true) }

    fun reopenTask(task: TaskEntity) = launchOp { repo.setTaskClosed(task, closed = false) }

    fun deleteTask(task: TaskEntity) = launchOp { repo.deleteTask(task) }

    fun createProject(name: String) = launchOp {
        val id = repo.createProject(name)
        selectProject(id)
    }

    fun archiveCurrentProject() = launchOp {
        val project = currentProject.value ?: return@launchOp
        repo.archiveProject(project.id)
        selectProject(0L)
    }

    // ---- Backups ------------------------------------------------------------------------------------------

    /** Writes the backup (local mode) or server snapshot (Kanboard mode) to a file the user picked. */
    fun exportTo(uri: Uri) = launchOp {
        val text = if (repo.mode == Mode.LOCAL) repo.exportLocalBackup() else repo.exportServerSnapshot()
        withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalArgumentException(str(R.string.error_file_write))
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        _events.tryEmit(str(R.string.message_exported))
    }

    /** Restores a backup file (local mode only). Replaces everything after validation. */
    fun importFrom(uri: Uri) = launchOp {
        val text = withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(str(R.string.error_file_read))
            stream.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BACKUP_BYTES) {
                    throw IllegalArgumentException(str(R.string.error_file_too_big))
                }
                String(bytes, Charsets.UTF_8)
            }
        }
        repo.importLocalBackup(text)
        _selectedProjectId.value = 0L
        _events.tryEmit(str(R.string.message_imported))
    }

    // ---- Connection details for the dialog --------------------------------------------------------------------

    fun connectionSummary(): ConnectionSummary? {
        val c = repo.currentConnection() ?: return null
        val security = when {
            c.isCleartext -> Security.HTTP
            c.pinnedSha256 != null -> Security.HTTPS_PINNED
            else -> Security.HTTPS
        }
        return ConnectionSummary(c.endpoint, c.username, security, c.pinnedSha256)
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    /** Runs one change with a progress bar, reports failures in a snackbar and refreshes widgets. */
    private fun launchOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                updateWidgets()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Runs a refresh; on failure shows a message but keeps the saved (offline) data. */
    private suspend fun refreshQuietly(block: suspend () -> Unit) {
        _refreshing.value = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.tryEmit(describe(e))
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun updateWidgets() {
        try {
            BoardWidget().updateAll(getApplication<Application>())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A widget that cannot redraw must never break the app.
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun describe(e: Throwable): String = when (e) {
        is ApiException -> when (e.kind) {
            ApiException.Kind.NETWORK -> str(R.string.error_network)
            ApiException.Kind.CERTIFICATE -> str(R.string.error_certificate)
            ApiException.Kind.UNAUTHORIZED -> str(R.string.error_unauthorized)
            ApiException.Kind.FORBIDDEN -> str(R.string.error_forbidden)
            ApiException.Kind.REJECTED -> str(R.string.error_rejected)
            ApiException.Kind.SERVER -> str(R.string.error_server, e.message.orEmpty())
            ApiException.Kind.BAD_RESPONSE -> str(R.string.error_bad_response)
            ApiException.Kind.NOT_CONFIGURED -> str(R.string.error_not_configured)
        }
        // Our own validation messages (bad backup file, ...) are already readable.
        is IllegalArgumentException -> e.message ?: str(R.string.error_unexpected)
        else -> str(R.string.error_unexpected)
    }

    private companion object {
        /** Backups larger than this are refused. */
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    }
}
