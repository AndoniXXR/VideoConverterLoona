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
import com.andoni.convertidor.data.ResolutionOption
import com.andoni.convertidor.data.VideoItem
import com.andoni.convertidor.data.VideoRepository
import com.andoni.convertidor.data.extension
import com.andoni.convertidor.data.formattedDuration
import com.andoni.convertidor.data.formattedSize
import com.andoni.convertidor.data.nameWithoutExtension
import com.andoni.convertidor.data.resolution
import com.andoni.convertidor.data.resolutionPresetsFor
import com.andoni.convertidor.service.ConversionService
import com.andoni.convertidor.util.FileUtils
import kotlin.math.roundToInt

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
                        onExport   = { outputName, format, isRepair, targetW, targetH ->
                            val path = FileUtils.buildOutputPath(context, outputName, format)
                            val intent = Intent(context, ConversionService::class.java).apply {
                                putExtra(ConversionService.EXTRA_INPUT_URI,     currentVideo.uri.toString())
                                putExtra(ConversionService.EXTRA_OUTPUT_PATH,   path)
                                putExtra(ConversionService.EXTRA_FORMAT,        format)
                                putExtra(ConversionService.EXTRA_IS_REPAIR,     isRepair)
                                putExtra(ConversionService.EXTRA_DURATION_MS,   currentVideo.duration)
                                if (targetW > 0) putExtra(ConversionService.EXTRA_TARGET_WIDTH,  targetW)
                                if (targetH > 0) putExtra(ConversionService.EXTRA_TARGET_HEIGHT, targetH)
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

// Calcula el tiempo estimado de procesamiento en segundos según la resolución de destino
private fun estimatedSeconds(durationMs: Long, srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
    val durationSec = (durationMs / 1000.0)
    // Factor base: 0.5x tiempo real para 720p. Escala cuadráticamente con los píxeles.
    val baseFactor = 0.5
    val srcPixels  = (srcW.toLong() * srcH).coerceAtLeast(1)
    val dstPixels  = dstW.toLong() * dstH
    val scaleFactor = (dstPixels.toDouble() / srcPixels).coerceIn(0.1, 8.0)
    return (durationSec * baseFactor * scaleFactor).roundToInt().coerceAtLeast(5)
}

private fun formatEstimate(secs: Int): String = when {
    secs < 60   -> "~${secs}s"
    secs < 3600 -> "~${secs / 60}min ${secs % 60}s"
    else        -> "~${secs / 3600}h ${(secs % 3600) / 60}min"
}

@Composable
private fun ConversionCard(
    video:     VideoItem,
    convState: ConversionService.ConversionState,
    onExport:  (outputName: String, format: String, isRepair: Boolean, targetW: Int, targetH: Int) -> Unit
) {
    val allFormats = listOf("mp4", "webm", "3gp")
    var selectedFormat by remember {
        mutableStateOf(allFormats.firstOrNull { it != video.extension } ?: "mp4")
    }
    var isRepair   by remember { mutableStateOf(false) }
    var outputName by remember(video) { mutableStateOf(video.nameWithoutExtension) }
    val focusManager = LocalFocusManager.current

    // ── Ajuste de resolución ──
    var changeResolution by remember { mutableStateOf(false) }
    val presets = remember(video.width, video.height) {
        resolutionPresetsFor(video.width, video.height)
    }
    var selectedPreset by remember(presets) { mutableStateOf<ResolutionOption?>(null) }

    // ── Diálogos ──
    var showSameFormatDialog  by remember { mutableStateOf(false) }
    var showResolutionConfirm by remember { mutableStateOf(false) }
    // Parámetros pendientes de confirmar
    var pendingOutputName by remember { mutableStateOf("") }
    var pendingFormat     by remember { mutableStateOf("") }
    var pendingIsRepair   by remember { mutableStateOf(false) }
    var pendingTargetW    by remember { mutableStateOf(-1) }
    var pendingTargetH    by remember { mutableStateOf(-1) }

    fun launchExport(name: String, fmt: String, repair: Boolean, tW: Int, tH: Int) {
        if (tW > 0 && tH > 0) {
            // Hay cambio de resolución → confirmar primero
            pendingOutputName = name
            pendingFormat     = fmt
            pendingIsRepair   = repair
            pendingTargetW    = tW
            pendingTargetH    = tH
            showResolutionConfirm = true
        } else {
            onExport(name, fmt, repair, -1, -1)
        }
    }

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

            // ── Toggle Reparar ──
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

            // ── Selector de formato ──
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

            HorizontalDivider()

            // ── Sección de ajuste de resolución ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cambiar resolución",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text("Resolución actual: ${video.resolution}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(
                    checked  = changeResolution,
                    onCheckedChange = {
                        changeResolution = it
                        if (!it) selectedPreset = null
                    }
                )
            }

            if (changeResolution) {
                Text("Selecciona la resolución de salida:",
                    style = MaterialTheme.typography.labelLarge)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { preset ->
                        val isSelected = selectedPreset == preset
                        val isCurrent  = preset.width == video.width && preset.height == video.height
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick  = { selectedPreset = preset }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "${preset.label}  (${preset.width}×${preset.height})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isCurrent) {
                                        Surface(
                                            shape    = MaterialTheme.shapes.small,
                                            color    = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text("actual",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                }
                                if (preset.socialHint.isNotEmpty()) {
                                    Text(
                                        "✓ Ideal para: ${preset.socialHint}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // Recomendaciones de redes sociales
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp),
                           verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Guía rápida de redes sociales:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold)
                        Text("• TikTok / Reels / Shorts / Snapchat → 1080×1920 (9:16)",
                            style = MaterialTheme.typography.labelSmall)
                        Text("• Instagram Feed → 1080×1080 (1:1) o 1080×1350 (4:5)",
                            style = MaterialTheme.typography.labelSmall)
                        Text("• YouTube HD → 1920×1080 (16:9)",
                            style = MaterialTheme.typography.labelSmall)
                        Text("• Twitter/X → 1280×720 (16:9)",
                            style = MaterialTheme.typography.labelSmall)
                        Text("• Facebook Feed → 1920×1080 o 1080×1920",
                            style = MaterialTheme.typography.labelSmall)
                        Text("• WhatsApp Status → 720×1280 mínimo",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ── Nombre del archivo de salida ──
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

            // ── Progreso de conversión ──
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

            // ── Mensaje de éxito ──
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

            // ── Mensaje de error ──
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

            // ── Botón de exportar ──
            Button(
                onClick = {
                    focusManager.clearFocus()
                    val tW = if (changeResolution) selectedPreset?.width  ?: -1 else -1
                    val tH = if (changeResolution) selectedPreset?.height ?: -1 else -1
                    if (!isRepair && selectedFormat == video.extension && tW < 0) {
                        showSameFormatDialog = true
                    } else {
                        val fmt = if (isRepair) video.extension else selectedFormat
                        launchExport(outputName.trim(), fmt, isRepair, tW, tH)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled  = !convState.isConverting
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isRepair         -> "Reparar video"
                        changeResolution && selectedPreset != null ->
                            "Cambiar a ${selectedPreset!!.label}"
                        else             -> "Exportar a ${selectedFormat.uppercase()}"
                    }
                )
            }

            // ── Diálogo: mismo formato sin cambio de resolución ──
            if (showSameFormatDialog) {
                AlertDialog(
                    onDismissRequest = { showSameFormatDialog = false },
                    title = { Text("Mismo formato detectado") },
                    text  = {
                        Text(
                            "El video ya está en formato ${video.extension.uppercase()}.\n\n" +
                            "¿Quieres reparar el video (reconstruirlo en el mismo formato) " +
                            "o prefieres elegir otro formato de salida?"
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showSameFormatDialog = false
                            isRepair = true
                            onExport(outputName.trim(), video.extension, true, -1, -1)
                        }) { Text("Reparar video") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            showSameFormatDialog = false
                            selectedFormat = allFormats.firstOrNull { it != video.extension } ?: allFormats.first()
                        }) { Text("Cambiar formato") }
                    }
                )
            }

            // ── Diálogo: confirmar cambio de resolución con tiempo estimado ──
            if (showResolutionConfirm) {
                val estSecs = estimatedSeconds(
                    video.duration,
                    video.width.takeIf { it > 0 } ?: pendingTargetW,
                    video.height.takeIf { it > 0 } ?: pendingTargetH,
                    pendingTargetW, pendingTargetH
                )
                AlertDialog(
                    onDismissRequest = { showResolutionConfirm = false },
                    title = { Text("Cambio de resolución") },
                    text  = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Se cambiará la resolución de ${video.resolution} " +
                                "a ${pendingTargetW}×${pendingTargetH}."
                            )
                            Text(
                                "⏱ Tiempo estimado de procesamiento: ${formatEstimate(estSecs)}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "El proceso se ejecutará en segundo plano. Puedes seguir " +
                                "usando el dispositivo y recibirás una notificación al terminar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showResolutionConfirm = false
                            onExport(pendingOutputName, pendingFormat, pendingIsRepair,
                                     pendingTargetW, pendingTargetH)
                        }) { Text("Sí, continuar") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showResolutionConfirm = false }) {
                            Text("No, cancelar")
                        }
                    }
                )
            }
        }
    }
}
