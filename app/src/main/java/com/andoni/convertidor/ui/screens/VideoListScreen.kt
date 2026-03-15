package com.andoni.convertidor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.size.Size
import com.andoni.convertidor.data.VideoItem
import com.andoni.convertidor.data.VideoRepository
import com.andoni.convertidor.data.extension
import com.andoni.convertidor.data.formattedDuration
import com.andoni.convertidor.data.formattedSize
import com.andoni.convertidor.util.AppUpdater
import com.andoni.convertidor.util.UpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SortOrder(val label: String) {
    DATE_DESC("Más nuevo primero"),
    DATE_ASC("Más antiguo primero"),
    NAME_ASC("Nombre A → Z"),
    NAME_DESC("Nombre Z → A")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(onVideoClick: (Long) -> Unit) {
    val context      = LocalContext.current
    val repository   = remember { VideoRepository(context) }
    var videos       by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var sortOrder    by remember { mutableStateOf(SortOrder.DATE_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var isRefreshing  by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf<UpdateInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope         = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    fun refreshVideos() {
        scope.launch {
            isRefreshing = true
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                snackbarHostState.showSnackbar(
                    message = "Actualizando\u2026",
                    duration = SnackbarDuration.Short
                )
            }
            try {
                videos    = repository.getAllVideos()
            } catch (_: Exception) { }
            isLoading = false
            isRefreshing = false
            delay(400)
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    // Recargar videos cada vez que la pantalla vuelve al frente.
    // Esto cubre: carga inicial, retorno tras aceptar permisos y retorno desde otras pantallas.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    isLoading = true
                    try {
                        videos = repository.getAllVideos()
                    } catch (_: Exception) { }
                    isLoading = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sortedVideos = remember(videos, sortOrder) {
        when (sortOrder) {
            SortOrder.DATE_DESC -> videos.sortedByDescending { it.dateAdded }
            SortOrder.DATE_ASC  -> videos.sortedBy { it.dateAdded }
            SortOrder.NAME_ASC  -> videos.sortedBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> videos.sortedByDescending { it.name.lowercase() }
        }
    }

    // ── Diálogo de actualización ──
    showUpdateDialog?.let { update ->
        AlertDialog(
            onDismissRequest = { showUpdateDialog = null },
            title = { Text("Nueva versión disponible") },
            text = {
                Column {
                    Text("Versión ${update.versionName}")
                    if (update.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            update.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ctx = context
                    // Verificar permiso de instalar paquetes
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                        !ctx.packageManager.canRequestPackageInstalls()) {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${ctx.packageName}")
                        )
                        ctx.startActivity(intent)
                    } else {
                        AppUpdater.downloadAndInstall(ctx, update)
                    }
                    showUpdateDialog = null
                }) { Text("Descargar e instalar") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = null }) { Text("Ahora no") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isCheckingUpdate) {
                                scope.launch {
                                    isCheckingUpdate = true
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    launch {
                                        snackbarHostState.showSnackbar(
                                            message = "Buscando actualizaciones…",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                    val update = AppUpdater.checkForUpdate(context)
                                    isCheckingUpdate = false
                                    delay(300)
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    if (update != null) {
                                        showUpdateDialog = update
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            message = "Ya tienes la última versión",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdateAlt,
                                contentDescription = "Buscar actualizaciones"
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text("VideoConvert", fontWeight = FontWeight.Bold)
                        Text(
                            "${videos.size} videos",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshVideos() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Ordenar")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (sortOrder == order) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Spacer(Modifier.size(18.dp))
                                            }
                                            Text(order.label)
                                        }
                                    },
                                    onClick = {
                                        sortOrder = order
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        val listState = rememberLazyListState()
        val showScrollToTop by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 3 }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pullToRefresh(
                    isRefreshing = isRefreshing,
                    state = pullState,
                    onRefresh = { refreshVideos() }
                )
        ) {
            when {
                isLoading && !isRefreshing -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                sortedVideos.isEmpty() && !isRefreshing -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(Modifier.weight(1f))
                    Text("No se encontraron videos", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Asegúrate de haber concedido el permiso de galería",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sortedVideos, key = { it.id }) { video ->
                        VideoListItem(video = video, onClick = { onVideoClick(video.id) })
                    }
                }
            }
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Botón scroll-to-top semitransparente
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp)
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Ir al inicio"
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoListItem(video: VideoItem, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier  = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Miniatura del video
            Box(
                modifier = Modifier
                    .size(88.dp, 66.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(video.uri)
                        .decoderFactory(VideoFrameDecoder.Factory())
                        .size(Size(176, 132))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text       = video.name,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    FormatBadge(video.extension.uppercase().ifBlank { "?" })
                    Text(
                        video.formattedDuration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        video.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatBadge(format: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text       = format,
            modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style      = MaterialTheme.typography.labelSmall,
            color      = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}
