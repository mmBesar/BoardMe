@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.boardme.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.boardme.R
import com.mmbesar.boardme.data.TaskEntity
import java.text.DateFormat
import java.util.Date

/**
 * One task in full: its description (Markdown shown properly), subtasks to tick off,
 * and comments. Everything here is stored on the server (in local-only mode: on this phone);
 * what was loaded once stays readable offline.
 */
@Composable
fun TaskDetailsScreen(
    viewModel: BoardViewModel,
    task: TaskEntity,
    snackbar: SnackbarHostState,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val subtasks by remember(task.id) { viewModel.subtasks(task.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val comments by remember(task.id) { viewModel.comments(task.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var newSubtask by rememberSaveable { mutableStateOf("") }
    var newComment by rememberSaveable { mutableStateOf("") }

    // Fresh copy from the server every time the screen opens (silent when offline).
    LaunchedEffect(task.id) { viewModel.refreshDetails(task) }

    // Each text field follows the direction of what is typed in it.
    val fieldStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.task_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.task_edit)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- Title, facts, description ----
            Text(
                task.title,
                style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            val due = task.dueDate
            if (due != null) {
                Text(
                    stringResource(R.string.due_on, due),
                    color = if (com.mmbesar.boardme.data.DueDates.isOverdue(due)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.priority > 0) {
                val label = when (task.priority) {
                    1 -> R.string.priority_low
                    2 -> R.string.priority_medium
                    else -> R.string.priority_high
                }
                Text(
                    stringResource(R.string.field_priority) + ": " + stringResource(label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.description.isNotBlank()) {
                MarkdownText(task.description, modifier = Modifier.fillMaxWidth())
            }

            // ---- Subtasks ----
            HorizontalDivider()
            val done = subtasks.count { it.status == 2 }
            Text(
                stringResource(R.string.details_subtasks) + if (subtasks.isNotEmpty()) "  ($done/${subtasks.size})" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtasks.isEmpty()) {
                Text(stringResource(R.string.details_no_subtasks), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            subtasks.forEach { sub ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleSubtask(task, sub) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (sub.status) {
                            2 -> "☑"
                            1 -> "◐"
                            else -> "☐"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        sub.title,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        textDecoration = if (sub.status == 2) TextDecoration.LineThrough else null,
                        color = if (sub.status == 2) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.deleteSubtask(task, sub) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.details_delete))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newSubtask,
                    onValueChange = { newSubtask = it },
                    label = { Text(stringResource(R.string.subtask_hint)) },
                    textStyle = fieldStyle,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.addSubtask(task, newSubtask.trim())
                        newSubtask = ""
                    },
                    enabled = newSubtask.isNotBlank() && !busy,
                ) { Text(stringResource(R.string.action_add)) }
            }

            // ---- Comments ----
            HorizontalDivider()
            Text(
                stringResource(R.string.details_comments) + if (comments.isNotEmpty()) "  (${comments.size})" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (comments.isEmpty()) {
                Text(stringResource(R.string.details_no_comments), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            comments.forEach { comment ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            comment.author.ifBlank { "?" } + "  ·  " + dates.format(Date(comment.created * 1000L)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.deleteComment(task, comment) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.details_delete))
                        }
                    }
                    MarkdownText(comment.content, modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(
                value = newComment,
                onValueChange = { newComment = it },
                label = { Text(stringResource(R.string.comment_hint)) },
                textStyle = fieldStyle,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    viewModel.addComment(task, newComment.trim())
                    newComment = ""
                },
                enabled = newComment.isNotBlank() && !busy,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.action_send)) }
        }
    }
}
