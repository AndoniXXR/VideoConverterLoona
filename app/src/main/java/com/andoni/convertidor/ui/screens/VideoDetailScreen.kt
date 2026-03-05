package com.andoni.convertidor.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.andoni.convertidor.data.VideoItem
import com.andoni.convertidor.data.VideoRepository
import com.andoni.convertidor.data.extension
import com.andoni.convertidor.data.formattedDuration
import com.andoni.convertidor.data.formattedSize
import com.andoni.convertidor.data.nameWithoutExtension
import com.andoni.convertidor.data.resolution
import com.andoni.convertidor.service.ConversionService
import com.andoni.convertidor.util.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(videoId: Long, onBack: () -> Unit) {
    val context    = LocalContext.current
    val repository = remember { VideoRepository(context) }
    var video      by remember { mutableStateOf<VideoItem?>(null) }
    val convState  by ConversionService.state.collectAsState()

    LaunchedEffect(videoId) { video = repository.getVideoById(videoId) }

    DisposableEffect(Unit) { onDispose { ConversionService.resetState() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detalle del video", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        val currentVideo = video
        if (currentVideo == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                VideoPlayerSection(currentVideo)

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    VideoInfoCard(currentVideo)
                    ConversionCard(
                        video      = currentVideo,
                        convState  = convState,
                        onExport   = { outputName, format, isRepair ->
                            val path = FileUtils.buildOutputPath(context, outputName, format)
                            val intent = Intent(context, ConversionService::class.java).apply {
                                putExtra(ConversionService.EXTRA_INPUT_PATH,  currentVideo.path)
                                putExtra(ConversionService.EXTRA_OUTPUT_PATH, path)
                                putExtra(ConversionService.EXTRA_FORMAT,      format)
                                putExtra(ConversionService.EXTRA_IS_REPAIR,   isRepair)
                                putExtra(ConversionService.EXTRA_DURATION_MS, currentVideo.duration)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                context.startForegroundService(intent)
                            else
                                context.startService(intent)
                        }
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ── Reproductor ──────────────────────────────────────────────────────────────

@Composable
private fun VideoPlayerSection(video: VideoItem) {
    val context   = LocalContext.current
    val exoPlayer = remember(video.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video.uri))
            prepare()
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply { player = exoPlayer }
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    )
}

// ── Tarjeta de información ───────────────────────────────────────────────────

@Composable
private fun VideoInfoCard(video: VideoItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Información del video",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider()
            InfoRow("Nombre",     video.name)
            InfoRow("Formato",    video.extension.uppercase().ifBlank { "Desconocido" })
            InfoRow("Duración",   video.formattedDuration)
            InfoRow("Tamaño",     video.formattedSize)
            InfoRow("Resolución", video.resolution)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium)
    }
}

// ── Tarjeta de conversión ────────────────────────────────────────────────────

@Composable
private fun ConversionCard(
    video:     VideoItem,
    convState: ConversionService.ConversionState,
    onExport:  (outputName: String, format: String, isRepair: Boolean) -> Unit
) {
    val allFormats = listOf("mp4", "webm", "3gp")
    var selectedFormat by remember {
        mutableStateOf(allFormats.firstOrNull { it != video.extension } ?: "mp4")
    }
    var isRepair   by remember { mutableStateOf(false) }
    var outputName by remember(video) { mutableStateOf(video.nameWithoutExtension) }
    val focusManager = LocalFocusManager.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Exportar / Reparar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider()

            // Toggle Reparar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("Reparar video",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        Text("Reconstruye el video en el mismo formato",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = isRepair, onCheckedChange = { isRepair = it })
            }

            // Selector de formato (sólo visible si no es reparación)
            if (!isRepair) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Formato de salida", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allFormats.forEach { fmt ->
                            FilterChip(
                                selected = selectedFormat == fmt,
                                onClick  = { selectedFormat = fmt },
                                label    = { Text(fmt.uppercase()) }
                            )
                        }
                    }
                }
            }

            // Nombre del archivo de salida
            OutlinedTextField(
                value        = outputName,
                onValueChange = { outputName = it },
                label        = { Text("Nombre del archivo exportado") },
                placeholder  = { Text("Dejar vacío para nombre automático") },
                singleLine   = true,
                modifier     = Modifier.fillMaxWidth(),
                keyboardOptions  = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions  = KeyboardActions(onDone = { focusManager.clearFocus() }),
                suffix = { Text(".${if (isRepair) video.extension else selectedFormat}") }
            )

            // Progreso de conversión
            if (convState.isConverting) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Convirtiendo…",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${convState.progress}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { convState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Mensaje de éxito
            if (convState.isCompleted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        "✓ Completado: ${convState.outputPath.substringAfterLast('/')}",
                        modifier = Modifier.padding(12.dp),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Mensaje de error
            convState.error?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "Error: $err",
                        modifier = Modifier.padding(12.dp),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 4
                    )
                }
            }

            // Botón de exportar / reparar
            Button(
                onClick = {
                    focusManager.clearFocus()
                    val fmt = if (isRepair) video.extension else selectedFormat
                    onExport(outputName.trim(), fmt, isRepair)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled  = !convState.isConverting
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRepair) "Reparar video"
                    else "Exportar a ${selectedFormat.uppercase()}"
                )
            }
        }
    }
}
