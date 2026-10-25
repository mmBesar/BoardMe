package com.mmbesar.boardme.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mmbesar.boardme.network.ColumnDto
import com.mmbesar.boardme.network.ProjectDto
import com.mmbesar.boardme.network.TaskDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---- Database tables (Room) ------------------------------------------------
// The same tables serve both modes: a local copy of Kanboard data, or the
// real storage in local-only mode.

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val isActive: Boolean,
)

@Entity(tableName = "board_columns", indices = [Index("projectId")])
data class ColumnEntity(
    @PrimaryKey val id: Long,
    val projectId: Long,
    val title: String,
    val position: Int,
)

@Entity(tableName = "tasks", indices = [Index("projectId"), Index("columnId")])
data class TaskEntity(
    /** Negative ids are tasks created offline that the server has not seen yet. */
    @PrimaryKey val id: Long,
    val projectId: Long,
    val columnId: Long,
    val swimlaneId: Long,
    val title: String,
    val description: String,
    val position: Int,
    /** "YYYY-MM-DD" or null when the task has no due date. */
    val dueDate: String?,
    /** true = open, false = closed. */
    val isActive: Boolean,
    /** Kanboard colour name such as "blue", or "" for the default. */
    val colorId: String,
    /** 0 (none) to 3 (high). */
    val priority: Int,
)

/** The kinds of change that wait in the queue until the server can be reached. */
enum class OpType { CREATE, UPDATE, MOVE, STATUS, DELETE }

/**
 * One change waiting to be sent to the server.
 *
 * The queue stores only WHICH task changed and HOW; the values to send are read
 * from the task's current row when the change is finally sent. That way two
 * edits to the same task in a row need only one request, and an old value can
 * never be sent by mistake.
 */
@Entity(tableName = "pending_ops")
data class PendingOp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [OpType] name. */
    val type: String,
    val taskId: Long,
    val projectId: Long,
    /** How often the server failed on this change (not counting "no connection"). */
    val attempts: Int = 0,
    /**
     * Only for CREATE: a unique tag stored in the task's Kanboard "reference" field.
     * If a connection drops after the server created the task but before BoardMe heard
     * back, the retry finds the task by this tag instead of creating a second one.
     */
    val ref: String = "",
)

// ---- Converting Kanboard answers into table rows ---------------------------

fun ProjectDto.toEntity() = ProjectEntity(id = id, name = name, isActive = isActive == 1L)

fun ColumnDto.toEntity(projectId: Long) = ColumnEntity(
    id = id,
    projectId = projectId,
    title = title,
    position = position.toInt(),
)

fun TaskDto.toEntity() = TaskEntity(
    id = id,
    projectId = projectId,
    columnId = columnId,
    swimlaneId = swimlaneId,
    title = title,
    description = description,
    position = position.toInt(),
    dueDate = DueDates.fromEpochSeconds(dateDue),
    isActive = isActive == 1L,
    colorId = colorId,
    priority = priority.toInt().coerceIn(0, 3),
)

// ---- Due dates --------------------------------------------------------------

/**
 * Due dates are handled as plain "YYYY-MM-DD" text in the app.
 * (java.time is avoided on purpose: it needs Android 8, BoardMe supports Android 6.)
 */
object DueDates {

    private const val PATTERN = "yyyy-MM-dd"
    private const val TWELVE_HOURS_SECONDS = 12 * 60 * 60L
    private val STRICT_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")

    private fun formatter(utc: Boolean): SimpleDateFormat =
        SimpleDateFormat(PATTERN, Locale.US).apply {
            isLenient = false
            if (utc) timeZone = TimeZone.getTimeZone("UTC")
        }

    /**
     * Kanboard stores a due date as the epoch second of midnight in ITS timezone.
     * Reading that back in UTC could show the previous day (e.g. a server at UTC+3
     * stores 21:00 UTC of the day before). Adding 12 hours first lands in the middle
     * of the intended day for every timezone from UTC-11 to UTC+11, so the date
     * you picked is the date you see. 0 means "no date".
     */
    fun fromEpochSeconds(seconds: Long): String? {
        if (seconds <= 0L) return null
        val shifted = (seconds + TWELVE_HOURS_SECONDS) * 1000L
        return formatter(utc = true).format(Date(shifted))
    }

    /** The date picker reports midnight UTC of the chosen day in milliseconds. */
    fun fromPickerMillis(millis: Long): String = formatter(utc = true).format(Date(millis))

    /** Inverse of [fromPickerMillis]; null if the text is not exactly a valid YYYY-MM-DD date. */
    fun toPickerMillis(date: String?): Long? {
        if (date.isNullOrBlank() || !STRICT_FORMAT.matches(date)) return null
        return try {
            formatter(utc = true).parse(date)?.time
        } catch (e: java.text.ParseException) {
            null
        }
    }

    /** Today's date on this phone, used to mark overdue tasks. */
    fun today(): String = formatter(utc = false).format(Date())

    /** True if [date] is before today. Text comparison works because the format is YYYY-MM-DD. */
    fun isOverdue(date: String?, today: String = today()): Boolean =
        !date.isNullOrBlank() && date < today
}
