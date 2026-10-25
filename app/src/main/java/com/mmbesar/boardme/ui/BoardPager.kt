@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.boardme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mmbesar.boardme.R
import com.mmbesar.boardme.data.ColumnEntity
import com.mmbesar.boardme.data.DueDates
import com.mmbesar.boardme.data.TaskEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything about a drag that is in progress (only one task can be dragged at a time). */
@Stable
class DragState {
    /** The task being dragged, or null when nothing is. */
    var task by mutableStateOf<TaskEntity?>(null)

    /** Where the finger is, in screen coordinates. */
    var pointer by mutableStateOf(Offset.Zero)

    /** How far below the card's top edge the finger touched it. */
    var grabY by mutableStateOf(0f)

    /** Height of the dragged card in pixels. */
    var cardHeight by mutableStateOf(0f)

    /** The column (page) the finger is over, and the task it would be dropped in front of (null = at the end). */
    var dropPage by mutableStateOf(-1)
    var dropBefore by mutableStateOf<Long?>(null)

    val active: Boolean get() = task != null

    fun reset() {
        task = null
        dropPage = -1
        dropBefore = null
    }
}

/**
 * Where would a task dropped at [pointerY] land? Returns the id of the task it goes in
 * front of, or null for "at the end". [dragged] itself is ignored.
 */
private fun computeDropTarget(
    pointerY: Float,
    dragged: Long,
    inColumn: List<TaskEntity>,
    listState: LazyListState,
    listTopOnScreen: Float,
): Long? {
    val visible = listState.layoutInfo.visibleItemsInfo.filter {
        val key = it.key
        key is Long && key != dragged
    }
    for (item in visible) {
        val middle = listTopOnScreen + item.offset + item.size / 2f
        if (pointerY < middle) return item.key as Long
    }
    // Below every visible card: go in front of the task that follows the last visible one.
    val lastVisible = visible.lastOrNull()?.key as? Long ?: return null
    val index = inColumn.indexOfFirst { it.id == lastVisible }
    return inColumn.drop(index + 1).firstOrNull { it.id != dragged }?.id
}

/** Tabs (one per column) on top, swipeable pages with the tasks below, drag and drop on top of both. */
@Composable
internal fun BoardPager(
    columns: List<ColumnEntity>,
    tasks: List<TaskEntity>,
    pendingIds: Set<Long>,
    dragEnabled: Boolean,
    showDescriptions: Boolean,
    pagerState: PagerState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onMove: (task: TaskEntity, columnId: Long, beforeTaskId: Long?) -> Unit,
    onClose: (TaskEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val byColumn = remember(tasks) { tasks.groupBy { it.columnId } }
    val selected = pagerState.currentPage.coerceIn(0, columns.lastIndex)

    // The long-running drag loop must always see the newest lists.
    val columnsNow by rememberUpdatedState(columns)
    val byColumnNow by rememberUpdatedState(byColumn)

    val drag = remember { DragState() }
    val listStates = remember { mutableMapOf<Int, LazyListState>() }
    var container by remember { mutableStateOf(Rect.Zero) }
    var pagerTop by remember { mutableStateOf(0f) }

    /** Works out which column and position the finger is over right now. */
    fun updateDrop() {
        val dragged = drag.task ?: return
        val page = pagerState.currentPage
        val listState = listStates[page] ?: return
        val column = columnsNow.getOrNull(page) ?: return
        drag.dropPage = page
        // The CARD's centre decides the spot (not the finger), so a short move is enough.
        val cardCentre = drag.pointer.y - drag.grabY + drag.cardHeight / 2f
        drag.dropBefore = computeDropTarget(
            cardCentre, dragged.id, byColumnNow[column.id].orEmpty(), listState, pagerTop,
        )
    }

    fun finishDrop() {
        val dragged = drag.task
        if (dragged != null) {
            updateDrop()
            val column = columnsNow.getOrNull(drag.dropPage)
            if (column != null) onMove(dragged, column.id, drag.dropBefore)
        }
        drag.reset()
    }

    // While dragging: scroll the list near its top/bottom edge, keep the drop indicator up to date,
    // and switch column after a SHORT sideways move (or when the finger reaches a screen edge).
    LaunchedEffect(drag.active) {
        if (!drag.active) return@LaunchedEffect
        val zone = with(density) { 72.dp.toPx() }
        val edge = with(density) { 40.dp.toPx() }
        val sideMove = with(density) { 72.dp.toPx() }
        // The sideways move is measured from where the drag started (or from the last switch).
        var anchor = drag.pointer
        var lastSwitch = 0L
        while (true) {
            val pointer = drag.pointer
            val page = pagerState.currentPage
            val listState = listStates[page]
            if (listState != null) {
                if (pointer.y < pagerTop + zone) {
                    listState.scrollBy(-20f)
                } else if (pointer.y > container.bottom - zone) {
                    listState.scrollBy(20f)
                }
            }
            val dx = pointer.x - anchor.x
            val dy = pointer.y - anchor.y
            val now = System.currentTimeMillis()
            var side = 0
            if (kotlin.math.abs(dx) > sideMove && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                side = if (dx > 0) 1 else -1
            } else if (pointer.x < container.left + edge && dx < 0) {
                side = -1
            } else if (pointer.x > container.right - edge && dx > 0) {
                side = 1
            }
            if (side != 0 && now - lastSwitch > 350) {
                val target = page + side
                if (target in columnsNow.indices) {
                    pagerState.animateScrollToPage(target)
                    anchor = drag.pointer
                    lastSwitch = System.currentTimeMillis()
                }
            }
            updateDrop()
            delay(16)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { container = it.boundsInRoot() },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { pagerTop = it.positionInRoot().y },
                // Swiping is switched off while a card is dragged; keeping every page alive lets
                // a drag continue after the finger moved to another column.
                userScrollEnabled = !drag.active,
                beyondViewportPageCount = if (drag.active) columns.size else 0,
            ) { page ->
                val column = columns.getOrNull(page) ?: return@HorizontalPager
                val inColumn = byColumn[column.id].orEmpty()
                val listState = rememberLazyListState()
                SideEffect { listStates[page] = listState }

                val inColumnNow by rememberUpdatedState(inColumn)

                // The long-press gesture lives on the LIST, not on each card: a card that scrolls
                // out of view while it is being dragged is removed by Compose, which would end
                // its own gesture. The list stays, so the drag survives long auto-scrolls.
                val dragGesture = if (dragEnabled) {
                    Modifier.pointerInput(page) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { touch ->
                                val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    it.key is Long && touch.y >= it.offset && touch.y <= it.offset + it.size
                                }
                                val task = inColumnNow.firstOrNull { it.id == hit?.key }
                                if (hit != null && task != null) {
                                    drag.task = task
                                    drag.pointer = Offset(container.left + touch.x, pagerTop + touch.y)
                                    drag.grabY = touch.y - hit.offset
                                    drag.cardHeight = hit.size.toFloat()
                                    drag.dropPage = page
                                    drag.dropBefore = task.id
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onDrag = { change, amount ->
                                if (drag.active) {
                                    change.consume()
                                    drag.pointer = drag.pointer + amount
                                }
                            },
                            onDragEnd = { finishDrop() },
                            onDragCancel = { drag.reset() },
                        )
                    }
                } else {
                    Modifier
                }

                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().then(dragGesture),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (inColumn.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    stringResource(R.string.board_empty_column),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        itemsIndexed(inColumn, key = { _, task -> task.id }) { index, task ->
                            Column {
                                if (drag.active && drag.dropPage == page && drag.dropBefore == task.id) DropLine()
                                TaskCard(
                                    task = task,
                                    pending = task.id in pendingIds,
                                    showDescription = showDescriptions,
                                    dragging = drag.task?.id == task.id,
                                    canMoveLeft = page > 0,
                                    canMoveRight = page < columns.lastIndex,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < inColumn.lastIndex,
                                    onEdit = { onEdit(task) },
                                    onMoveLeft = {
                                        val target = columns[page - 1]
                                        onMove(task, target.id, byColumn[target.id].orEmpty().getOrNull(index)?.id)
                                        scope.launch { pagerState.animateScrollToPage(page - 1) }
                                    },
                                    onMoveRight = {
                                        val target = columns[page + 1]
                                        onMove(task, target.id, byColumn[target.id].orEmpty().getOrNull(index)?.id)
                                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                                    },
                                    onMoveUp = { onMove(task, column.id, inColumn[index - 1].id) },
                                    onMoveDown = { onMove(task, column.id, inColumn.getOrNull(index + 2)?.id) },
                                    onClose = { onClose(task) },
                                )
                            }
                        }
                        if (drag.active && drag.dropPage == page && drag.dropBefore == null) {
                            item(key = "end-marker") { DropLine() }
                        }
                    }
                }
            }
        }

        // The card that follows the finger.
        val dragged = drag.task
        if (dragged != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .offset { IntOffset(0, (drag.pointer.y - drag.grabY - container.top).roundToInt()) }
                    .graphicsLayer {
                        scaleX = 1.03f
                        scaleY = 1.03f
                        shadowElevation = 24f
                    },
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Text(
                        dragged.title,
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

/** The line that shows where a dragged task will land. */
@Composable
private fun DropLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** One task. Tap to edit, press and hold to drag; the menu holds the move and close actions. */
@Composable
private fun TaskCard(
    task: TaskEntity,
    pending: Boolean,
    showDescription: Boolean,
    dragging: Boolean,
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
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dragging) 0.25f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Colour stripe on the left edge.
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(taskColor(task.colorId) ?: Color.Transparent),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showDescription && task.description.isNotBlank()) {
                        val theme = MdTheme(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
                        val preview = remember(task.description, theme) { markdownPreview(task.description, theme) }
                        Text(
                            preview,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val due = task.dueDate
                    if (due != null || task.priority > 0 || pending) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (due != null) {
                                Text(
                                    text = stringResource(R.string.due_on, due),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (DueDates.isOverdue(due)) MaterialTheme.colorScheme.error
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
                                    text = "▲ " + stringResource(label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (task.priority >= 3) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (pending) {
                                Text(
                                    text = stringResource(R.string.task_waiting),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
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
}
