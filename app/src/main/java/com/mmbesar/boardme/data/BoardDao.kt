package com.mmbesar.boardme.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * All database questions in one place.
 * It is an abstract class (not an interface) so it can contain @Transaction
 * helpers: several changes that succeed or fail together.
 */
@Dao
abstract class BoardDao {

    // ---- Watching data (the screens update automatically) ----------------

    @Query("SELECT * FROM projects WHERE isActive = 1 ORDER BY name COLLATE NOCASE")
    abstract fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM board_columns WHERE projectId = :projectId ORDER BY position, id")
    abstract fun observeColumns(projectId: Long): Flow<List<ColumnEntity>>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId AND isActive = :open ORDER BY position, id")
    abstract fun observeTasks(projectId: Long, open: Boolean): Flow<List<TaskEntity>>

    // ---- One-off reads -------------------------------------------------------

    @Query("SELECT * FROM projects")
    abstract suspend fun allProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    abstract suspend fun project(id: Long): ProjectEntity?

    @Query("SELECT * FROM board_columns WHERE projectId = :projectId ORDER BY position, id")
    abstract suspend fun columnsOf(projectId: Long): List<ColumnEntity>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY position, id")
    abstract suspend fun tasksOf(projectId: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract suspend fun task(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE columnId = :columnId AND isActive = 1 ORDER BY position, id")
    abstract suspend fun openTasksIn(columnId: Long): List<TaskEntity>

    // ---- New ids for local-only mode (no server hands them out) -------------

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM projects")
    abstract suspend fun nextProjectId(): Long

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM board_columns")
    abstract suspend fun nextColumnId(): Long

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM tasks")
    abstract suspend fun nextTaskId(): Long

    // ---- Simple writes -----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertProjects(projects: List<ProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertColumns(columns: List<ColumnEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET title = :title, description = :description, dueDate = :dueDate WHERE id = :id")
    abstract suspend fun updateTaskText(id: Long, title: String, description: String, dueDate: String?)

    @Query("UPDATE tasks SET isActive = :open WHERE id = :id")
    abstract suspend fun setTaskOpen(id: Long, open: Boolean)

    @Query("UPDATE tasks SET columnId = :columnId, position = :position WHERE id = :id")
    abstract suspend fun setTaskPlacement(id: Long, columnId: Long, position: Int)

    @Query("DELETE FROM tasks WHERE id = :id")
    abstract suspend fun deleteTask(id: Long)

    @Query("UPDATE projects SET isActive = :active WHERE id = :id")
    abstract suspend fun setProjectActive(id: Long, active: Boolean)

    @Query("DELETE FROM tasks WHERE projectId = :projectId")
    abstract suspend fun deleteTasksOf(projectId: Long)

    @Query("DELETE FROM board_columns WHERE projectId = :projectId")
    abstract suspend fun deleteColumnsOf(projectId: Long)

    @Query("DELETE FROM projects")
    abstract suspend fun deleteAllProjects()

    @Query("DELETE FROM board_columns")
    abstract suspend fun deleteAllColumns()

    @Query("DELETE FROM tasks")
    abstract suspend fun deleteAllTasks()

    @Query("DELETE FROM projects WHERE id NOT IN (:keep)")
    abstract suspend fun deleteProjectsNotIn(keep: List<Long>)

    @Query("DELETE FROM board_columns WHERE projectId NOT IN (:keep)")
    abstract suspend fun deleteColumnsOfProjectsNotIn(keep: List<Long>)

    @Query("DELETE FROM tasks WHERE projectId NOT IN (:keep)")
    abstract suspend fun deleteTasksOfProjectsNotIn(keep: List<Long>)

    // ---- Several changes at once (all or nothing) --------------------------

    /** Wipes everything. Used when switching between Kanboard and local-only mode. */
    @Transaction
    open suspend fun clearAll() {
        deleteAllTasks()
        deleteAllColumns()
        deleteAllProjects()
    }

    /** Replaces the cached columns and tasks of one project with fresh server data. */
    @Transaction
    open suspend fun replaceProjectContent(
        projectId: Long,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ) {
        deleteTasksOf(projectId)
        deleteColumnsOf(projectId)
        upsertColumns(columns)
        upsertTasks(tasks)
    }

    /** Stores the project list from the server and forgets projects that are gone. */
    @Transaction
    open suspend fun replaceProjectList(projects: List<ProjectEntity>) {
        val keep = projects.map { it.id }
        deleteTasksOfProjectsNotIn(keep)
        deleteColumnsOfProjectsNotIn(keep)
        deleteProjectsNotIn(keep)
        upsertProjects(projects)
    }

    /** Puts tasks into a column in exactly the given order (positions 1, 2, 3 ...). */
    @Transaction
    open suspend fun placeTasks(columnId: Long, orderedTaskIds: List<Long>) {
        orderedTaskIds.forEachIndexed { index, taskId ->
            setTaskPlacement(taskId, columnId, index + 1)
        }
    }

    /** Replaces the ENTIRE workspace in one step (a failed import leaves your data untouched). */
    @Transaction
    open suspend fun replaceEverything(
        projects: List<ProjectEntity>,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ) {
        clearAll()
        upsertProjects(projects)
        upsertColumns(columns)
        upsertTasks(tasks)
    }

    /** Creates a whole project (columns and tasks) in one go. Used by local-only mode and import. */
    @Transaction
    open suspend fun insertProjectWithContent(
        project: ProjectEntity,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ) {
        upsertProjects(listOf(project))
        upsertColumns(columns)
        upsertTasks(tasks)
    }
}

/** The database itself. Bump [version] and add a migration whenever a table changes. */
@Database(
    entities = [ProjectEntity::class, ColumnEntity::class, TaskEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BoardDatabase : RoomDatabase() {
    abstract fun dao(): BoardDao

    companion object {
        fun create(context: Context): BoardDatabase =
            Room.databaseBuilder(context, BoardDatabase::class.java, "boardme.db").build()
    }
}
