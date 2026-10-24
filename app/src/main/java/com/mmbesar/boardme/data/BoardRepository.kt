package com.mmbesar.boardme.data

import com.mmbesar.boardme.network.ApiException
import com.mmbesar.boardme.network.BoardApi
import com.mmbesar.boardme.network.HttpClients
import com.mmbesar.boardme.security.Connection
import com.mmbesar.boardme.security.CredentialStore
import com.mmbesar.boardme.security.Mode
import kotlinx.coroutines.flow.Flow

/** One line in the home-screen widget: a column title or a task title. */
data class WidgetRow(val text: String, val isHeader: Boolean)

/** What the widget shows for one project. */
data class WidgetData(val projectName: String, val rows: List<WidgetRow>)

/**
 * The single place that knows about both worlds:
 *  - KANBOARD mode: the server is the boss. Every change is sent to the server
 *    first; only when it succeeds is the project re-downloaded into the local
 *    cache. The screens always read from the cache (so they work offline).
 *  - LOCAL mode: the on-device database is the real storage.
 */
class BoardRepository(
    private val dao: BoardDao,
    private val store: CredentialStore,
) {
    val mode: Mode get() = store.mode

    // ---- What the screens watch -----------------------------------------------

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()
    fun observeColumns(projectId: Long): Flow<List<ColumnEntity>> = dao.observeColumns(projectId)
    fun observeTasks(projectId: Long, open: Boolean): Flow<List<TaskEntity>> =
        dao.observeTasks(projectId, open)

    // ---- Server client (rebuilt only when the saved login changes) --------------

    private var cachedApi: Pair<Connection, BoardApi>? = null

    private fun buildApi(c: Connection) = BoardApi(
        client = HttpClients.create(pinnedSha256 = c.pinnedSha256, allowCleartext = c.isCleartext),
        endpoint = c.endpoint,
        username = c.username,
        token = c.token,
    )

    @Synchronized
    private fun api(): BoardApi {
        val connection = store.loadConnection()
            ?: throw ApiException(ApiException.Kind.NOT_CONFIGURED, "No server has been set up")
        cachedApi?.let { if (it.first == connection) return it.second }
        return buildApi(connection).also { cachedApi = connection to it }
    }

    // ---- Setting up and switching modes ---------------------------------------------

    /** Checks the login without saving anything. Throws [ApiException] on failure. */
    suspend fun testConnection(connection: Connection) {
        buildApi(connection).getMe()
    }

    /** Saves a tested login, wipes any previous workspace and downloads the project list. */
    suspend fun connect(connection: Connection) {
        dao.clearAll()
        store.saveConnection(connection)
        store.mode = Mode.KANBOARD
        store.selectedProjectId = 0L
        synchronized(this) { cachedApi = null }
        refreshProjects()
    }

    /** Starts local-only mode with one starter project. */
    suspend fun startLocal(starterProjectName: String) {
        dao.clearAll()
        store.clearConnection()
        store.mode = Mode.LOCAL
        store.selectedProjectId = 0L
        synchronized(this) { cachedApi = null }
        createLocalProject(starterProjectName)
    }

    /** Forgets everything on this phone and returns to the first-start screen. Server data is untouched. */
    suspend fun reset() {
        dao.clearAll()
        store.clearConnection()
        store.mode = Mode.UNSET
        store.selectedProjectId = 0L
        synchronized(this) { cachedApi = null }
    }

    /** The saved login, for showing connection details (the token is never displayed). */
    fun currentConnection(): Connection? = store.loadConnection()

    // ---- Refreshing from the server -----------------------------------------------------

    suspend fun refreshProjects() {
        if (mode != Mode.KANBOARD) return
        val projects = api().getMyProjects().map { it.toEntity() }
        dao.replaceProjectList(projects)
    }

    suspend fun refreshProject(projectId: Long) {
        if (mode != Mode.KANBOARD) return
        val api = api()
        val columns = api.getColumns(projectId).map { it.toEntity(projectId) }
        val open = api.getAllTasks(projectId, open = true)
        val closed = api.getAllTasks(projectId, open = false)
        val tasks = (open + closed).map { it.toEntity().copy(projectId = projectId) }
        dao.replaceProjectContent(projectId, columns, tasks)
    }

    /** Used by the widget's background refresh. */
    suspend fun refreshForWidgets(projectIds: Collection<Long>) {
        if (mode != Mode.KANBOARD) return
        refreshProjects()
        projectIds.forEach { refreshProject(it) }
    }

    // ---- Tasks ------------------------------------------------------------------------------

    suspend fun createTask(
        projectId: Long,
        columnId: Long,
        title: String,
        description: String,
        dueDate: String?,
    ) {
        if (mode == Mode.LOCAL) {
            val task = TaskEntity(
                id = dao.nextTaskId(),
                projectId = projectId,
                columnId = columnId,
                swimlaneId = 0L,
                title = title.trim(),
                description = description,
                position = dao.openTasksIn(columnId).size + 1,
                dueDate = dueDate,
                isActive = true,
            )
            dao.upsertTasks(listOf(task))
        } else {
            api().createTask(projectId, columnId, title.trim(), description, dueDate.orEmpty())
            refreshProject(projectId)
        }
    }

    suspend fun updateTask(task: TaskEntity, title: String, description: String, dueDate: String?) {
        if (mode == Mode.LOCAL) {
            dao.updateTaskText(task.id, title.trim(), description, dueDate)
        } else {
            api().updateTask(task.id, title.trim(), description, dueDate.orEmpty())
            refreshProject(task.projectId)
        }
    }

    /** Moves a task to [columnId] and puts it at [index] (0 = top; big numbers = bottom). */
    suspend fun moveTask(task: TaskEntity, columnId: Long, index: Int) {
        val others = dao.openTasksIn(columnId).map { it.id }.filter { it != task.id }
        if (mode == Mode.LOCAL) {
            dao.placeTasks(columnId, insertAt(others, task.id, index))
        } else {
            val position = index.coerceIn(0, others.size) + 1
            api().moveTaskPosition(task.projectId, task.id, columnId, position, task.swimlaneId)
            refreshProject(task.projectId)
        }
    }

    suspend fun setTaskClosed(task: TaskEntity, closed: Boolean) {
        if (mode == Mode.LOCAL) {
            dao.setTaskOpen(task.id, open = !closed)
        } else {
            if (closed) api().closeTask(task.id) else api().openTask(task.id)
            refreshProject(task.projectId)
        }
    }

    suspend fun deleteTask(task: TaskEntity) {
        if (mode == Mode.LOCAL) {
            dao.deleteTask(task.id)
        } else {
            api().removeTask(task.id)
            refreshProject(task.projectId)
        }
    }

    // ---- Projects ---------------------------------------------------------------------------------

    /** Returns the id of the new project. */
    suspend fun createProject(name: String): Long {
        return if (mode == Mode.LOCAL) {
            createLocalProject(name)
        } else {
            val id = api().createProject(name.trim())
            refreshProjects()
            refreshProject(id)
            id
        }
    }

    /** Hides a project. On a server this disables it; nothing is ever deleted. */
    suspend fun archiveProject(projectId: Long) {
        if (mode == Mode.LOCAL) {
            dao.setProjectActive(projectId, active = false)
        } else {
            api().disableProject(projectId)
            refreshProjects()
        }
    }

    private suspend fun createLocalProject(name: String): Long {
        val projectId = dao.nextProjectId()
        val firstColumnId = dao.nextColumnId()
        val columns = LOCAL_COLUMNS.mapIndexed { index, title ->
            ColumnEntity(firstColumnId + index, projectId, title, index + 1)
        }
        dao.insertProjectWithContent(
            ProjectEntity(projectId, name.trim(), isActive = true),
            columns,
            emptyList(),
        )
        return projectId
    }

    // ---- Backups ------------------------------------------------------------------------------------

    /** Local-only mode: everything stored on the phone. */
    suspend fun exportLocalBackup(): String {
        val projects = dao.allProjects().sortedBy { it.id }.map { project ->
            toBackupProject(
                name = project.name,
                archived = !project.isActive,
                columns = dao.columnsOf(project.id),
                tasks = dao.tasksOf(project.id),
            )
        }
        return BackupCodec.encode(BackupFile(projects = projects))
    }

    /**
     * Kanboard mode: downloads every active project completely. If ANY part
     * fails, the exception stops the export, so you never get a half-empty file.
     */
    suspend fun exportServerSnapshot(): String {
        val api = api()
        val projects = api.getMyProjects().filter { it.isActive == 1L }.map { dto ->
            val columns = api.getColumns(dto.id).map { it.toEntity(dto.id) }
            val open = api.getAllTasks(dto.id, open = true)
            val closed = api.getAllTasks(dto.id, open = false)
            val tasks = (open + closed).map { it.toEntity().copy(projectId = dto.id) }
            toBackupProject(dto.name, archived = false, columns = columns, tasks = tasks)
        }
        return BackupCodec.encode(BackupFile(projects = projects))
    }

    /** Local-only mode: replaces the whole workspace with the backup's content. */
    suspend fun importLocalBackup(text: String) {
        check(mode == Mode.LOCAL) { "Backups can only be restored in local-only mode" }
        val file = BackupCodec.decode(text)

        val projects = mutableListOf<ProjectEntity>()
        val columns = mutableListOf<ColumnEntity>()
        val tasks = mutableListOf<TaskEntity>()
        var nextProject = 1L
        var nextColumn = 1L
        var nextTask = 1L
        for (project in file.projects) {
            val projectId = nextProject++
            projects += ProjectEntity(projectId, project.name.trim(), isActive = !project.archived)
            project.columns.forEachIndexed { columnIndex, column ->
                val columnId = nextColumn++
                columns += ColumnEntity(columnId, projectId, column.title.trim(), columnIndex + 1)
                column.tasks.forEachIndexed { taskIndex, task ->
                    tasks += TaskEntity(
                        id = nextTask++,
                        projectId = projectId,
                        columnId = columnId,
                        swimlaneId = 0L,
                        title = task.title.trim(),
                        description = task.description,
                        position = taskIndex + 1,
                        dueDate = task.dueDate,
                        isActive = !task.closed,
                    )
                }
            }
        }
        dao.replaceEverything(projects, columns, tasks)
        store.selectedProjectId = 0L
    }

    private fun toBackupProject(
        name: String,
        archived: Boolean,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ): BackupProject {
        val byColumn = tasks.groupBy { it.columnId }
        return BackupProject(
            name = name,
            archived = archived,
            columns = columns.sortedBy { it.position }.map { column ->
                BackupColumn(
                    title = column.title,
                    tasks = (byColumn[column.id] ?: emptyList())
                        .sortedWith(compareBy({ it.position }, { it.id }))
                        .map { BackupTask(it.title, it.description, it.dueDate, closed = !it.isActive) },
                )
            },
        )
    }

    // ---- Widget -----------------------------------------------------------------------------------------

    /** Reads the cache for the home-screen widget. Returns null if the project is gone or archived. */
    suspend fun widgetSnapshot(projectId: Long): WidgetData? {
        val project = dao.project(projectId)?.takeIf { it.isActive } ?: return null
        val openTasks = dao.tasksOf(projectId).filter { it.isActive }.groupBy { it.columnId }
        val rows = mutableListOf<WidgetRow>()
        for (column in dao.columnsOf(projectId)) {
            val inColumn = openTasks[column.id].orEmpty()
            rows += WidgetRow("${column.title} (${inColumn.size})", isHeader = true)
            inColumn.forEach { rows += WidgetRow(it.title, isHeader = false) }
        }
        return WidgetData(project.name, rows)
    }

    private companion object {
        /** Every new local project gets these four columns. */
        val LOCAL_COLUMNS = listOf("Backlog", "Ready", "Work in progress", "Done")
    }
}
