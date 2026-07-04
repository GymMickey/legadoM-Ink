package io.legado.app.ui.book.readRecord

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.book.readRecord.components.HeatmapCalendarBottomSheet
import io.legado.app.ui.book.readRecord.components.HeatmapMode
import io.legado.app.ui.book.readRecord.components.SummaryCard
import io.legado.app.ui.widget.components.swipe.SwipeActionContainer
import io.legado.app.ui.widget.components.swipe.rememberSwipeDeleteAction
import io.legado.app.utils.formatReadDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadRecordScreen(
    viewModel: ReadRecordViewModel = viewModel(),
    onBackClick: () -> Unit,
    onBookClick: (String, String) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val state by viewModel.uiState.collectAsState()
    val displayMode by viewModel.displayMode.collectAsState()
    val enableReadRecord by viewModel.enableReadRecord.collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var heatmapMode by remember { mutableStateOf(HeatmapMode.TIME) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val topBarColor = readRecordTopBarContainerColor()
    val searchFieldColor = readRecordCardContainerColor()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var skipDeleteConfirm by remember { mutableStateOf(false) }

    var showMergeDialog by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<ReadRecord?>(null) }

    if (showDeleteConfirm && pendingDeleteAction != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.rr_confirm_delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.rr_confirm_delete_msg))
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = skipDeleteConfirm,
                            onCheckedChange = { skipDeleteConfirm = it }
                        )
                        Text(stringResource(R.string.rr_dont_show_again), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteAction?.invoke()
                    showDeleteConfirm = false
                    pendingDeleteAction = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    pendingDeleteAction = null
                }) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    if (showMergeDialog && selectedRecord != null) {
        val candidates = produceState(initialValue = emptyList<ReadRecord>()) {
            value = viewModel.getMergeCandidates(selectedRecord!!)
        }.value

        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.rr_merge_same_name)) },
            text = {
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.rr_no_merge_candidates))
                } else {
                    Column {
                        Text(stringResource(R.string.rr_select_merge_target, selectedRecord!!.bookName))
                        candidates.forEach { record ->
                            Text(
                                text = "· ${record.bookName} - ${record.bookAuthor.ifBlank { stringResource(R.string.rr_unknown_author) }}",
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.mergeReadRecords(selectedRecord!!, candidates)
                    showMergeDialog = false
                }) {
                    Text(stringResource(R.string.rr_merge), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (state.isSelectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarColor,
                        scrolledContainerColor = topBarColor,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSecondary,
                        titleContentColor = MaterialTheme.colorScheme.onSecondary,
                        actionIconContentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    title = {
                        Text(
                            text = stringResource(R.string.rr_selected_items, state.selectedRecords.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rr_cancel_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllRecords(displayMode) }) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                        IconButton(onClick = {
                            if (state.selectedRecords.isNotEmpty()) {
                                pendingDeleteAction = { viewModel.deleteSelectedRecords() }
                                showDeleteConfirm = true
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rr_delete_selected))
                        }
                    }
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarColor,
                        scrolledContainerColor = topBarColor,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSecondary,
                        titleContentColor = MaterialTheme.colorScheme.onSecondary,
                        actionIconContentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.read_record),
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            )
                            Text(
                                text = when (displayMode) {
                                    DisplayMode.AGGREGATE -> stringResource(R.string.rr_aggregate_view)
                                    DisplayMode.TIMELINE -> stringResource(R.string.rr_timeline_view)
                                    DisplayMode.LATEST -> stringResource(R.string.rr_latest_read)
                                    DisplayMode.READ_TIME -> stringResource(R.string.rr_read_duration)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                        }
                        IconButton(onClick = { showCalendar = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.rr_calendar))
                        }
                        IconButton(onClick = {
                            viewModel.setDisplayMode(
                                when (displayMode) {
                                    DisplayMode.AGGREGATE -> DisplayMode.TIMELINE
                                    DisplayMode.TIMELINE -> DisplayMode.LATEST
                                    DisplayMode.LATEST -> DisplayMode.READ_TIME
                                    DisplayMode.READ_TIME -> DisplayMode.AGGREGATE
                                }
                            )
                        }) {
                            Icon(
                                imageVector = when (displayMode) {
                                    DisplayMode.AGGREGATE -> Icons.Default.Timeline
                                    DisplayMode.TIMELINE -> Icons.Default.List
                                    DisplayMode.LATEST -> Icons.Default.AutoAwesome
                                    DisplayMode.READ_TIME -> Icons.Default.Schedule
                                },
                                contentDescription = stringResource(R.string.rr_switch_view)
                            )
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_menu))
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (enableReadRecord) stringResource(R.string.rr_close_record) else stringResource(R.string.rr_open_record))
                                    },
                                    onClick = {
                                        viewModel.setEnableReadRecord(!enableReadRecord)
                                        showOverflowMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (enableReadRecord) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AnimatedVisibility(visible = showSearch) {
                OutlinedTextField(
                    value = state.searchKey ?: "",
                    onValueChange = { viewModel.setSearchKey(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.rr_search_books)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchKey?.isNotEmpty() == true) {
                            IconButton(onClick = { viewModel.setSearchKey("") }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = searchFieldColor,
                        unfocusedContainerColor = searchFieldColor,
                        disabledContainerColor = searchFieldColor,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    singleLine = true
                )
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (
                (displayMode == DisplayMode.AGGREGATE && state.groupedRecords.isEmpty()) ||
                (displayMode == DisplayMode.TIMELINE && state.timelineRecords.isEmpty()) ||
                (displayMode == DisplayMode.LATEST && state.latestRecords.isEmpty()) ||
                (displayMode == DisplayMode.READ_TIME && state.readTimeRecords.isEmpty())
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.rr_no_records),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item {
                        SummaryCard(
                            totalReadTime = state.totalReadTime,
                            bookCount = state.latestRecords.size,
                            latestRecords = state.latestRecords,
                            viewModel = viewModel
                        )
                    }

                    RecordListContent(
                        displayMode = displayMode,
                        state = state,
                        viewModel = viewModel,
                        onBookClick = onBookClick,
                        onConfirmDelete = { action ->
                            if (skipDeleteConfirm) {
                                action()
                            } else {
                                pendingDeleteAction = action
                                showDeleteConfirm = true
                            }
                        },
                        onMergeClick = { record ->
                            selectedRecord = record
                            showMergeDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showCalendar) {
        HeatmapCalendarBottomSheet(
            dailyReadCounts = state.dailyReadCounts,
            dailyReadTimes = state.dailyReadTimes,
            currentMode = heatmapMode,
            selectedDate = state.selectedDate,
            onDateSelected = { viewModel.setSelectedDate(it) },
            onModeChanged = { heatmapMode = it },
            onDismiss = { showCalendar = false }
        )
    }
}

@Composable
private fun BookCoverImage(
    bookName: String,
    bookAuthor: String,
    viewModel: ReadRecordViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(bookName, bookAuthor) {
        coverBitmap = withContext(Dispatchers.IO) {
            val coverPath = viewModel.getBookCover(bookName, bookAuthor)
            loadReadRecordCoverBitmap(context, coverPath)
                ?: loadReadRecordCoverBitmap(context, viewModel.getConfiguredDefaultCover())
        }
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.shadow(4.dp, RoundedCornerShape(6.dp))
    ) {
        if (coverBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = coverBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun loadReadRecordCoverBitmap(context: android.content.Context, coverPath: String?): Bitmap? {
    if (coverPath.isNullOrBlank()) return null
    return runCatching {
        ImageLoader.loadBitmap(context, coverPath)
            .submit()
            .get()
    }.getOrNull()
}

@Suppress("UNUSED_PARAMETER")
private fun LazyListScope.RecordListContent(
    displayMode: DisplayMode,
    state: ReadRecordUiState,
    viewModel: ReadRecordViewModel,
    onBookClick: (String, String) -> Unit,
    onConfirmDelete: (() -> Unit) -> Unit,
    onMergeClick: (ReadRecord) -> Unit
) {
    when (displayMode) {
        DisplayMode.AGGREGATE -> {
            state.groupedRecords.toSortedMap(compareByDescending { it }).forEach { (date, details) ->
                item(key = "header_$date") {
                    DateHeader(date = date, totalDuration = details.sumOf { it.readTime })
                }
                items(items = details.sortedByDescending { it.readTime }, key = { detailRecordKey(date, it) }) { detail ->
                    RecordDetailItem(
                        detail = detail,
                        viewModel = viewModel,
                        isSelectionMode = state.isSelectionMode,
                        isSelected = viewModel.isSelected(detail),
                        onClick = { 
                            if (state.isSelectionMode) {
                                viewModel.toggleRecordSelection(detail)
                            } else {
                                onBookClick(detail.bookName, detail.bookAuthor)
                            }
                        },
                        onLongClick = {
                            if (!state.isSelectionMode) {
                                viewModel.enterSelectionMode(detail)
                            }
                        },
                        onDelete = { onConfirmDelete { viewModel.deleteDetail(detail) } }
                    )
                }
            }
        }
        DisplayMode.TIMELINE -> {
            state.timelineRecords.forEach { (date, sessions) ->
                item(key = "timeline_header_$date") {
                    TimelineDateHeader(
                        date = date,
                        totalDuration = state.dailyReadTimes[
                            LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
                        ] ?: 0L
                    )
                }
                sessions.forEachIndexed { index, session ->
                    item(key = "timeline_item_${date}|${session.id}") {
                        TimelineSessionView(
                            session = session,
                            isLast = index == sessions.size - 1,
                            viewModel = viewModel,
                            isSelectionMode = state.isSelectionMode,
                            isSelected = viewModel.isSelected(session),
                            onClick = { 
                                if (state.isSelectionMode) {
                                    viewModel.toggleRecordSelection(session)
                                } else {
                                    onBookClick(session.bookName, session.bookAuthor)
                                }
                            },
                            onLongClick = {
                                if (!state.isSelectionMode) {
                                    viewModel.enterSelectionMode(session)
                                }
                            },
                            onDelete = { onConfirmDelete { viewModel.deleteSession(session) } }
                        )
                    }
                }
            }
        }
        DisplayMode.LATEST -> {
            items(items = state.latestRecords, key = { latestRecordKey(it) }) { record ->
                LatestRecordItem(
                    record = record,
                    viewModel = viewModel,
                    isSelectionMode = state.isSelectionMode,
                    isSelected = viewModel.isSelected(record),
                    onClick = { 
                        if (state.isSelectionMode) {
                            viewModel.toggleRecordSelection(record)
                        } else {
                            onBookClick(record.bookName, record.bookAuthor)
                        }
                    },
                    onLongClick = {
                        if (!state.isSelectionMode) {
                            viewModel.enterSelectionMode(record)
                        }
                    },
                    onDelete = { onConfirmDelete { viewModel.deleteReadRecord(record) } },
                    onMerge = { onMergeClick(record) }
                )
            }
        }
        DisplayMode.READ_TIME -> {
            items(items = state.readTimeRecords, key = { readTimeRecordKey(it) }) { record ->
                ReadTimeRecordItem(
                    record = record,
                    viewModel = viewModel,
                    isSelectionMode = state.isSelectionMode,
                    isSelected = viewModel.isSelected(record),
                    onClick = { 
                        if (state.isSelectionMode) {
                            viewModel.toggleRecordSelection(record)
                        } else {
                            onBookClick(record.bookName, record.bookAuthor)
                        }
                    },
                    onLongClick = {
                        if (!state.isSelectionMode) {
                            viewModel.enterSelectionMode(record)
                        }
                    },
                    onDelete = { onConfirmDelete { viewModel.deleteReadRecord(record) } }
                )
            }
        }
    }
}

private fun detailRecordKey(date: String, detail: ReadRecordDetail): String {
    return "agg_item_${date}|${detail.deviceId}|${detail.bookName}|${detail.bookAuthor}"
}

private fun latestRecordKey(record: ReadRecord): String {
    return "latest_${record.deviceId}|${record.bookName}|${record.bookAuthor}"
}

private fun readTimeRecordKey(record: ReadRecord): String {
    return "readtime_${record.deviceId}|${record.bookName}|${record.bookAuthor}"
}

@Composable
private fun DateHeader(date: String, totalDuration: Long) {
    val headerColor = readRecordHeaderContainerColor()
    val secondaryTextColor = readRecordSecondaryTextColor()
    val todayStr = stringResource(R.string.rr_today)
    val yesterdayStr = stringResource(R.string.rr_yesterday)
    val dayBeforeYesterdayStr = stringResource(R.string.rr_day_before_yesterday)
    val dateFormat = stringResource(R.string.rr_date_format_md_weekday)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = headerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatFriendlyDate(date, todayStr, yesterdayStr, dayBeforeYesterdayStr, dateFormat),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatReadDuration(totalDuration),
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor
            )
        }
    }
}

@Composable
private fun TimelineDateHeader(date: String, totalDuration: Long) {
    val headerColor = readRecordHeaderContainerColor()
    val secondaryTextColor = readRecordSecondaryTextColor()
    val todayStr = stringResource(R.string.rr_today)
    val yesterdayStr = stringResource(R.string.rr_yesterday)
    val dayBeforeYesterdayStr = stringResource(R.string.rr_day_before_yesterday)
    val dateFormat = stringResource(R.string.rr_date_format_md_weekday)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = headerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatFriendlyDate(date, todayStr, yesterdayStr, dayBeforeYesterdayStr, dateFormat),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatReadDuration(totalDuration),
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineSessionView(
    session: ReadRecordSession,
    isLast: Boolean,
    viewModel: ReadRecordViewModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val startTime = timeFormat.format(Date(session.startTime))
    val timelineAccentColor = readRecordTimelineAccentColor()
    val secondaryTextColor = readRecordSecondaryTextColor()
    
    val deleteAction = rememberSwipeDeleteAction(onDelete)
    var chapterTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session.bookName, session.bookAuthor) {
        chapterTitle = viewModel.getBookDurChapterTitle(session.bookName, session.bookAuthor)
    }
    
    if (isSelectionMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 0.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 8.dp)
            )
            Box(
                modifier = Modifier.width(20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(48.dp)
                            .shadow(2.dp, RoundedCornerShape(1.dp), clip = false)
                            .background(timelineAccentColor.copy(alpha = 0.7f))
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = timelineAccentColor,
                    modifier = Modifier
                        .size(12.dp)
                        .shadow(3.dp, CircleShape, clip = false)
                ) {}
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = startTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BookCoverImage(
                    bookName = session.bookName,
                    bookAuthor = session.bookAuthor,
                    viewModel = viewModel,
                    modifier = Modifier.width(40.dp).height(54.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.bookName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = session.bookAuthor.ifBlank { stringResource(R.string.rr_unknown_author) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    chapterTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    } else {
        SwipeActionContainer(
            modifier = Modifier.fillMaxWidth(),
            startActions = listOf(deleteAction)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.width(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(48.dp)
                                .shadow(2.dp, RoundedCornerShape(1.dp), clip = false)
                                .background(timelineAccentColor.copy(alpha = 0.7f))
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = timelineAccentColor,
                        modifier = Modifier
                            .size(12.dp)
                            .shadow(3.dp, CircleShape, clip = false)
                    ) {}
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Box(
                    modifier = Modifier.width(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = startTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookCoverImage(
                        bookName = session.bookName,
                        bookAuthor = session.bookAuthor,
                        viewModel = viewModel,
                        modifier = Modifier.width(40.dp).height(54.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.bookName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = session.bookAuthor.ifBlank { "未知作者" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        chapterTitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordDetailItem(
    detail: ReadRecordDetail,
    viewModel: ReadRecordViewModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val deleteAction = rememberSwipeDeleteAction(onDelete)
    var chapterTitle by remember { mutableStateOf<String?>(null) }
    val containerColor = readRecordCardContainerColor()
    val border = readRecordCardBorder()
    val secondaryTextColor = readRecordSecondaryTextColor()

    LaunchedEffect(detail.bookName, detail.bookAuthor) {
        chapterTitle = viewModel.getBookDurChapterTitle(detail.bookName, detail.bookAuthor)
    }
    
    if (isSelectionMode) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            color = containerColor,
            shape = RoundedCornerShape(12.dp),
            border = border
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
                BookCoverImage(
                    bookName = detail.bookName,
                    bookAuthor = detail.bookAuthor,
                    viewModel = viewModel,
                    modifier = Modifier.width(44.dp).height(60.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.bookName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = detail.bookAuthor.ifBlank { stringResource(R.string.rr_unknown_author) },
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                    chapterTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Text(
                    text = formatReadDuration(detail.readTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor
                )
            }
        }
    } else {
        SwipeActionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            startActions = listOf(deleteAction)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    ),
                color = containerColor,
                shape = RoundedCornerShape(12.dp),
                border = border
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookCoverImage(
                        bookName = detail.bookName,
                        bookAuthor = detail.bookAuthor,
                        viewModel = viewModel,
                        modifier = Modifier.width(44.dp).height(60.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = detail.bookName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = detail.bookAuthor.ifBlank { "未知作者" },
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryTextColor
                        )
                        chapterTitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    Text(
                        text = formatReadDuration(detail.readTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LatestRecordItem(
    record: ReadRecord,
    viewModel: ReadRecordViewModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onMerge: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var chapterTitle by remember { mutableStateOf<String?>(null) }
    val containerColor = readRecordCardContainerColor()
    val border = readRecordCardBorder()
    val secondaryTextColor = readRecordSecondaryTextColor()

    LaunchedEffect(record.bookName, record.bookAuthor) {
        chapterTitle = viewModel.getBookDurChapterTitle(record.bookName, record.bookAuthor)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        border = border
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            BookCoverImage(
                bookName = record.bookName,
                bookAuthor = record.bookAuthor,
                viewModel = viewModel,
                modifier = Modifier.width(44.dp).height(60.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.bookName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = record.bookAuthor.ifBlank { stringResource(R.string.rr_unknown_author) },
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor
                )
                chapterTitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatReadDuration(record.readTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                    Text(
                        text = formatDateTime(record.lastRead),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
            }
            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = readRecordMutedIconTint())
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rr_merge_same_name)) },
                            onClick = {
                                onMerge()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Merge, contentDescription = null, tint = readRecordMutedIconTint())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onDelete()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadTimeRecordItem(
    record: ReadRecord,
    viewModel: ReadRecordViewModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    var chapterTitle by remember { mutableStateOf<String?>(null) }
    val containerColor = readRecordCardContainerColor()
    val border = readRecordCardBorder()
    val secondaryTextColor = readRecordSecondaryTextColor()

    LaunchedEffect(record.bookName, record.bookAuthor) {
        chapterTitle = viewModel.getBookDurChapterTitle(record.bookName, record.bookAuthor)
    }

    val deleteAction = rememberSwipeDeleteAction(onDelete)

    if (isSelectionMode) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            color = containerColor,
            shape = RoundedCornerShape(12.dp),
            border = border
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
                BookCoverImage(
                    bookName = record.bookName,
                    bookAuthor = record.bookAuthor,
                    viewModel = viewModel,
                    modifier = Modifier.width(44.dp).height(60.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.bookName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = record.bookAuthor.ifBlank { "未知作者" },
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                    chapterTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = formatReadDuration(record.readTime),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    } else {
        SwipeActionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            startActions = listOf(deleteAction)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    ),
                color = containerColor,
                shape = RoundedCornerShape(12.dp),
                border = border
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookCoverImage(
                        bookName = record.bookName,
                        bookAuthor = record.bookAuthor,
                        viewModel = viewModel,
                        modifier = Modifier.width(44.dp).height(60.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.bookName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = record.bookAuthor.ifBlank { "未知作者" },
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryTextColor
                        )
                        chapterTitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Text(
                        text = formatReadDuration(record.readTime),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun formatFriendlyDate(
    dateStr: String,
    todayStr: String,
    yesterdayStr: String,
    dayBeforeYesterdayStr: String,
    dateFormat: String
): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val date = inputFormat.parse(dateStr) ?: return dateStr

        val today = LocalDate.now()
        val localDate = date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()

        when {
            localDate == today -> todayStr
            localDate == today.minusDays(1) -> yesterdayStr
            localDate == today.minusDays(2) -> dayBeforeYesterdayStr
            else -> {
                val outputFormat = SimpleDateFormat(dateFormat, Locale.getDefault())
                outputFormat.format(date)
            }
        }
    } catch (e: Exception) {
        dateStr
    }
}

private fun formatFriendlyDateTime(
    timestamp: Long,
    todayStr: String,
    yesterdayStr: String,
    dayBeforeYesterdayStr: String,
    dateFormat: String
): String {
    val date = Date(timestamp)
    val today = LocalDate.now()
    val localDate = date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = timeFormat.format(date)

    return when {
        localDate == today -> "$todayStr $timeStr"
        localDate == today.minusDays(1) -> "$yesterdayStr $timeStr"
        localDate == today.minusDays(2) -> "$dayBeforeYesterdayStr $timeStr"
        else -> {
            val df = SimpleDateFormat(dateFormat, Locale.getDefault())
            "${df.format(date)} $timeStr"
        }
    }
}

private fun formatDateTime(timestamp: Long): String {
    val date = Date(timestamp)
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return dateFormat.format(date)
}
