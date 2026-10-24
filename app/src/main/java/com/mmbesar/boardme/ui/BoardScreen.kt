@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.boardme.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.boardme.R
import com.mmbesar.boardme.data.ColumnEntity
import com.mmbesar.boardme.data.DueDates
import com.mmbesar.boardme.data.TaskEntity
import com.mmbesar.boardme.security.Mode
import kotlinx.coroutines.launch

/** Which pop-up is open on the board screen (at most one at a time). */
private sealed interface BoardDialog {
    data class NewTask(val columnId: Long) : BoardDialog
    data class EditTask(val task: TaskEntity) : BoardDialog
    data object NewProject : BoardDialog
    data object ConfirmArchive : BoardDialog
    data object ConfirmSwitch : BoardDialog
    data object ShowConnection : BoardDialog
    data class ConfirmImport(val uri: Uri) : BoardDialog
}

/** The main screen: project picker, one tab per column, swipe between columns. */
@Composable
fun BoardScreen(viewModel: BoardViewModel) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val loaded by viewModel.projectsLoaded.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val project by viewModel.currentProject.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val tasks by viewModel.openTasks.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it) } }

    var showClosed by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BoardDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var projectMenuOpen by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { columns.size })

    // Android's file pickers: the app never needs storage permission.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) dialog = BoardDialog.ConfirmImport(uri) }

    BackHandler(enabled = showClosed) { showClosed = false }

    if (showClosed) {
        ClosedTasksScreen(viewModel = viewModel, snackbar = snackbar, onBack = { showClosed = false })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier.clickable { projectMenuOpen = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = project?.name ?: stringResource(R.string.app_name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.menu_projects))
                        }
                        DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                            projects.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = {
                                        projectMenuOpen = false
                                        viewModel.selectProject(p.id)
                                    },
                                )
                            }
                            if (projects.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_new)) },
                                onClick = {
                                    projectMenuOpen = false
                                    dialog = BoardDialog.NewProject
                                },
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (project != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_closed_tasks)) },
                                    onClick = { menuOpen = false; showClosed = true },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (mode == Mode.LOCAL) R.string.menu_export_backup
                                            else R.string.menu_export_snapshot,
                                        ),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("boardme-backup.json")
                                },
                            )
                            if (mode == Mode.LOCAL) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_import_backup)) },
                                    onClick = {
                                        menuOpen = false
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                )
                            }
                            if (project != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_archive_project)) },
                                    onClick = { menuOpen = false; dialog = BoardDialog.ConfirmArchive },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_connection)) },
                                onClick = { menuOpen = false; dialog = BoardDialog.ShowConnection },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_switch_mode)) },
                                onClick = { menuOpen = false; dialog = BoardDialog.ConfirmSwitch },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (project != null && columns.isNotEmpty()) {
                FloatingActionButton(onClick = {
                    val column = columns.getOrNull(pagerState.currentPage) ?: columns.first()
                    dialog = BoardDialog.NewTask(column.id)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_new))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                !loaded -> Unit
                project == null -> EmptyProjects(onCreate = { dialog = BoardDialog.NewProject })
                columns.isEmpty() -> Text(
                    stringResource(R.string.board_no_columns),
                    modifier = Modifier.padding(24.dp),
                )
                else -> BoardPager(
                    columns = columns,
                    tasks = tasks,
                    pagerState = pagerState,
                    refreshing = refreshing,
                    onRefresh = { viewModel.refreshCurrent() },
                    onEdit = { dialog = BoardDialog.EditTask(it) },
                    onMove = { task, columnId, index -> viewModel.moveTask(task, columnId, index) },
                    onClose = { viewModel.closeTask(it) },
                )
            }
        }
    }

    // ---- Pop-ups ---------------------------------------------------------------------------------
    when (val d = dialog) {
        null -> Unit

        is BoardDialog.NewTask -> TaskDialog(
            initial = null,
            onDismiss = { dialog = null },
            onSave = { title, description, due ->
                viewModel.createTask(d.columnId, title, description, due)
                dialog = null
            },
            onClose = null,
        )

        is BoardDialog.EditTask -> TaskDialog(
            initial = d.task,
            onDismiss = { dialog = null },
            onSave = { title, description, due ->
                viewModel.updateTask(d.task, title, description, due)
                dialog = null
            },
            onClose = {
                viewModel.closeTask(d.task)
                dialog = null
            },
        )

        BoardDialog.NewProject -> TextInputDialog(
            title = stringResource(R.string.project_new),
            label = stringResource(R.string.field_project_name),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { dialog = null },
            onConfirm = { name ->
                viewModel.createProject(name)
                dialog = null
            },
        )

        BoardDialog.ConfirmArchive -> ConfirmDialog(
            title = stringResource(R.string.archive_title),
            message = stringResource(
                if (mode == Mode.LOCAL) R.string.archive_message_local else R.string.archive_message_server,
            ),
            confirmLabel = stringResource(R.string.menu_archive_project),
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.archiveCurrentProject()
                dialog = null
            },
        )

        BoardDialog.ConfirmSwitch -> ConfirmDialog(
            title = stringResource(R.string.switch_title),
            message = stringResource(
                if (mode == Mode.LOCAL) R.string.switch_message_local else R.string.switch_message_server,
            ),
            confirmLabel = stringResource(R.string.switch_confirm),
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.resetMode()
                dialog = null
            },
        )

        BoardDialog.ShowConnection -> ConnectionDialog(
            summary = viewModel.connectionSummary(),
            mode = mode,
            onDismiss = { dialog = null },
        )

        is BoardDialog.ConfirmImport -> ConfirmDialog(
            title = stringResource(R.string.import_title),
            message = stringResource(R.string.import_message),
            confirmLabel = stringResource(R.string.import_confirm),
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.importFrom(d.uri)
                dialog = null
            },
        )
    }
}

/** Shown when there is no project yet. */
@Composable
private fun EmptyProjects(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.board_no_projects), modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = onCreate) { Text(stringResource(R.string.project_new)) }
    }
}

/** Tabs (one per column) on top, swipeable pages with the tasks below. */
@Composable
private fun BoardPager(
    columns: List<ColumnEntity>,
    tasks: List<TaskEntity>,
    pagerState: PagerState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onMove: (task: TaskEntity, columnId: Long, index: Int) -> Unit,
    onClose: (TaskEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val byColumn = remember(tasks) { tasks.groupBy { it.columnId } }
    val selected = pagerState.currentPage.coerceIn(0, columns.lastIndex)

    ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
        columns.forEachIndexed { index, column ->
            Tab(
                selected = selected == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = {
                    Text(
                        text = "${column.title} (${byColumn[column.id].orEmpty().size})",
                        maxLines = 1,
                    )
                },
            )
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val column = columns.getOrNull(page) ?: return@HorizontalPager
        val inColumn = byColumn[column.id].orEmpty()

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (inColumn.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.board_empty_column),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                itemsIndexed(inColumn, key = { _, task -> task.id }) { index, task ->
                    TaskCard(
                        task = task,
                        canMoveLeft = page > 0,
                        canMoveRight = page < columns.lastIndex,
                        canMoveUp = index > 0,
                        canMoveDown = index < inColumn.lastIndex,
                        onEdit = { onEdit(task) },
                        onMoveLeft = {
                            onMove(task, columns[page - 1].id, index)
                            scope.launch { pagerState.animateScrollToPage(page - 1) }
                        },
                        onMoveRight = {
                            onMove(task, columns[page + 1].id, index)
                            scope.launch { pagerState.animateScrollToPage(page + 1) }
                        },
                        onMoveUp = { onMove(task, column.id, index - 1) },
                        onMoveDown = { onMove(task, column.id, index + 1) },
                        onClose = { onClose(task) },
                    )
                }
            }
        }
    }
}

/** One task. Tap to edit; the menu holds the move and close actions. */
@Composable
private fun TaskCard(
    task: TaskEntity,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                if (task.description.isNotBlank()) {
                    Text(
                        task.description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                task.dueDate?.let { due ->
                    Text(
                        text = stringResource(R.string.due_on, due),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (DueDates.isOverdue(due)) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    if (canMoveLeft) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_move_left)) },
                            onClick = { menuOpen = false; onMoveLeft() },
                        )
                    }
                    if (canMoveRight) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_move_right)) },
                            onClick = { menuOpen = false; onMoveRight() },
                        )
                    }
                    if (canMoveUp) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_move_up)) },
                            onClick = { menuOpen = false; onMoveUp() },
                        )
                    }
                    if (canMoveDown) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_move_down)) },
                            onClick = { menuOpen = false; onMoveDown() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_close)) },
                        onClick = { menuOpen = false; onClose() },
                    )
                }
            }
        }
    }
}
