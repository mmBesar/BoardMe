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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.boardme.R
import com.mmbesar.boardme.data.SyncState
import com.mmbesar.boardme.data.TaskEntity
import com.mmbesar.boardme.security.Mode

/** Which pop-up is open on the board screen (at most one at a time). */
private sealed interface BoardDialog {
    data class NewTask(val columnId: Long) : BoardDialog
    data class EditTask(val task: TaskEntity) : BoardDialog
    data object NewProject : BoardDialog
    data object ConfirmArchive : BoardDialog
    data object UpdateToken : BoardDialog
    data class ConfirmImport(val uri: Uri) : BoardDialog
}

/** The main screen: project picker, sync banner, one tab per column, swipe or drag between columns. */
@Composable
fun BoardScreen(viewModel: BoardViewModel, themePrefs: ThemePrefs) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val loaded by viewModel.projectsLoaded.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val project by viewModel.currentProject.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val allTasks by viewModel.openTasks.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingTaskIds.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val certReview by viewModel.certReview.collectAsStateWithLifecycle()
    val look by themePrefs.settings.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it) } }

    var showClosed by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
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

    // Back closes whatever is on top: settings, closed tasks, then search.
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showClosed && !showSettings) { showClosed = false }
    BackHandler(enabled = searching && !showClosed && !showSettings) {
        searching = false
        query = ""
    }

    if (showSettings) {
        SettingsScreen(themePrefs = themePrefs, viewModel = viewModel, onBack = { showSettings = false })
        return
    }
    if (showClosed) {
        ClosedTasksScreen(viewModel = viewModel, snackbar = snackbar, onBack = { showClosed = false })
        return
    }

    // Search narrows the open tasks by title or description.
    val needle = query.trim()
    val tasks = remember(allTasks, needle) {
        if (needle.isEmpty()) allTasks
        else allTasks.filter {
            it.title.contains(needle, ignoreCase = true) || it.description.contains(needle, ignoreCase = true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                                textDirection = androidx.compose.ui.text.style.TextDirection.Content,
                            ),
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
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
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
                        }
                    } else {
                        if (project != null) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_hint))
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (mode != Mode.LOCAL) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_sync_now)) },
                                        onClick = { menuOpen = false; viewModel.syncNow() },
                                    )
                                }
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
                                    text = { Text(stringResource(R.string.menu_settings)) },
                                    onClick = { menuOpen = false; showSettings = true },
                                )
                            }
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
            if (busy || syncState == SyncState.SYNCING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (mode != Mode.LOCAL) {
                SyncBanner(
                    state = syncState,
                    pending = pendingCount,
                    onUpdateToken = { dialog = BoardDialog.UpdateToken },
                    onReview = { viewModel.reviewCertificate() },
                )
            }
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
                    pendingIds = pendingIds,
                    // Dragging a card in a filtered list would drop it at a misleading place.
                    dragEnabled = needle.isEmpty(),
                    showDescriptions = look.showDescriptions,
                    pagerState = pagerState,
                    refreshing = refreshing,
                    onRefresh = { viewModel.refreshCurrent() },
                    onEdit = { dialog = BoardDialog.EditTask(it) },
                    onMove = { task, columnId, before -> viewModel.moveTask(task, columnId, before) },
                    onClose = { viewModel.closeTask(it) },
                )
            }
        }
    }

    // Certificate that changed since it was trusted (opened from the banner's "Review").
    certReview?.let { state ->
        CertificateDialog(
            state = state,
            onTrust = { viewModel.trustReviewedCertificate(state) },
            onDismiss = { viewModel.dismissCertReview() },
            changed = true,
        )
    }

    // ---- Pop-ups ---------------------------------------------------------------------------------
    when (val d = dialog) {
        null -> Unit

        is BoardDialog.NewTask -> TaskDialog(
            initial = null,
            onDismiss = { dialog = null },
            onSave = { title, description, due, colorId, priority ->
                viewModel.createTask(d.columnId, title, description, due, colorId, priority)
                dialog = null
            },
            onClose = null,
        )

        is BoardDialog.EditTask -> TaskDialog(
            initial = d.task,
            onDismiss = { dialog = null },
            onSave = { title, description, due, colorId, priority ->
                viewModel.updateTask(d.task, title, description, due, colorId, priority)
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

        BoardDialog.UpdateToken -> TokenDialog(
            onDismiss = { dialog = null },
            onConfirm = { token ->
                viewModel.updateToken(token)
                dialog = null
            },
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

/**
 * Thin strip under the title bar that tells the truth about syncing:
 * offline, waiting changes, login or certificate problems. Hidden when all is well.
 */
@Composable
private fun SyncBanner(
    state: SyncState,
    pending: Int,
    onUpdateToken: () -> Unit,
    onReview: () -> Unit,
) {
    // Text to show (null = nothing), and an optional button.
    val text: String? = when (state) {
        SyncState.LOGIN -> stringResource(R.string.banner_login)
        SyncState.CERTIFICATE -> stringResource(R.string.banner_certificate)
        SyncState.ERROR -> stringResource(R.string.banner_error)
        SyncState.OFFLINE ->
            if (pending > 0) stringResource(R.string.banner_offline_pending, pending)
            else stringResource(R.string.banner_offline)
        SyncState.SYNCING -> if (pending > 0) stringResource(R.string.banner_pending, pending) else null
        SyncState.IDLE -> if (pending > 0) stringResource(R.string.banner_pending, pending) else null
    }
    if (text == null) return

    val problem = state == SyncState.LOGIN || state == SyncState.CERTIFICATE || state == SyncState.ERROR
    Surface(
        color = if (problem) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (problem) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            when (state) {
                SyncState.LOGIN -> TextButton(onClick = onUpdateToken) {
                    Text(stringResource(R.string.action_update_token))
                }
                SyncState.CERTIFICATE -> TextButton(onClick = onReview) {
                    Text(stringResource(R.string.action_review))
                }
                else -> Unit
            }
        }
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
