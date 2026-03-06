package com.andoni.convertidor.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.andoni.convertidor.data.FPS_PRESETS
import com.andoni.convertidor.data.FpsOption
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(scrollState)
            ) {
                VideoPlayerSection(currentVideo)

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    VideoInfoCard(currentVideo)
                    ConversionCard(
                        video       = currentVideo,
                        convState   = convState,
                        scrollState = scrollState,
                        onExport    = { outputName, format, isRepair, targetW, targetH, targetFps ->
                            val path = FileUtils.buildOutputPath(context, outputName, format)
                            val intent = Intent(context, ConversionService::class.java).apply {
                                putExtra(ConversionService.EXTRA_INPUT_URI,     currentVideo.uri.toString())
                                putExtra(ConversionService.EXTRA_OUTPUT_PATH,   path)
                                putExtra(ConversionService.EXTRA_FORMAT,        format)
                                putExtra(ConversionService.EXTRA_IS_REPAIR,     isRepair)
                                putExtra(ConversionService.EXTRA_DURATION_MS,   currentVideo.duration)
                                if (targetW   > 0) putExtra(ConversionService.EXTRA_TARGET_WIDTH,  targetW)
                                if (targetH   > 0) putExtra(ConversionService.EXTRA_TARGET_HEIGHT, targetH)
                                if (targetFps > 0) putExtra(ConversionService.EXTRA_TARGET_FPS,    targetFps)
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
    video:       VideoItem,
    convState:   ConversionService.ConversionState,
    scrollState: ScrollState,
    onExport:    (outputName: String, format: String, isRepair: Boolean, targetW: Int, targetH: Int, targetFps: Int) -> Unit
) {
    val allFormats = listOf("mp4", "webm", "3gp")
    var selectedFormat by remember {
        mutableStateOf(allFormats.firstOrNull { it != video.extension } ?: "mp4")
    }
    var isRepair   by remember { mutableStateOf(false) }
    var outputName by remember(video) { mutableStateOf(video.nameWithoutExtension) }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // ── Ajuste de resolución ──
    var changeResolution by remember { mutableStateOf(false) }
    val presets = remember(video.width, video.height) {
        resolutionPresetsFor(video.width, video.height)
    }
    var selectedPreset by remember(presets) { mutableStateOf<ResolutionOption?>(null) }

    // ── Ajuste de FPS ──
    var changeFps by remember { mutableStateOf(false) }
    var selectedFps by remember { mutableStateOf<FpsOption?>(null) }
    val srcFps = remember(video) {
        // Si VideoItem no expone frameRate, 30 es el default
        30
    }

    // ── Diálogos ──
    var showSameFormatDialog  by remember { mutableStateOf(false) }
    var showConfirmDialog     by remember { mutableStateOf(false) }
    // Parámetros pendientes de confirmar
    var pendingOutputName by remember { mutableStateOf("") }
    var pendingFormat     by remember { mutableStateOf("") }
    var pendingIsRepair   by remember { mutableStateOf(false) }
    var pendingTargetW    by remember { mutableStateOf(-1) }
    var pendingTargetH    by remember { mutableStateOf(-1) }
    var pendingTargetFps  by remember { mutableStateOf(-1) }

    fun launchExport(name: String, fmt: String, repair: Boolean, tW: Int, tH: Int, tFps: Int) {
        val hasAnyChange = (tW > 0 && tH > 0) || tFps > 0
        if (hasAnyChange) {
            pendingOutputName = name
            pendingFormat     = fmt
            pendingIsRepair   = repair
            pendingTargetW    = tW
            pendingTargetH    = tH
            pendingTargetFps  = tFps
            showConfirmDialog = true
        } else {
            onExport(name, fmt, repair, -1, -1, -1)
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

            // ── Sección de ajuste de FPS ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cambiar FPS (fotogramas por segundo)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text("FPS actual: ~$srcFps fps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(
                    checked  = changeFps,
                    onCheckedChange = {
                        changeFps = it
                        if (!it) selectedFps = null
                    }
                )
            }

            if (changeFps) {
                Text("Selecciona los FPS de salida:",
                    style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FPS_PRESETS.forEach { opt ->
                        val isSelected = selectedFps == opt
                        val isCurrent  = opt.fps == srcFps
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick  = { selectedFps = opt }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        opt.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isCurrent) {
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text("actual",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                    if (opt.fps > srcFps) {
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text("interpolado",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                }
                                if (opt.socialHint.isNotEmpty()) {
                                    Text(
                                        "✓ ${opt.socialHint}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
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
                modifier     = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (it.isFocused) coroutineScope.launch {
                            delay(300)
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    },
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
            val tW = if (changeResolution) selectedPreset?.width  ?: -1 else -1
            val tH = if (changeResolution) selectedPreset?.height ?: -1 else -1
            val tFps = if (changeFps) selectedFps?.fps ?: -1 else -1
            val hasRes = tW > 0 && tH > 0
            val hasFps = tFps > 0
            val buttonLabel = when {
                isRepair                       -> "Reparar video"
                hasRes && hasFps               -> "Exportar · ${selectedPreset!!.label} · ${tFps}fps"
                hasRes                         -> "Exportar a ${selectedPreset!!.label}"
                hasFps                         -> "Exportar a ${tFps}fps"
                else                           -> "Exportar a ${selectedFormat.uppercase()}"
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    // Mostrar diálogo de mismo formato solo si NO hay cambios de resolución ni FPS
                    if (!isRepair && selectedFormat == video.extension && !hasRes && !hasFps) {
                        showSameFormatDialog = true
                    } else {
                        val fmt = if (isRepair) video.extension else selectedFormat
                        launchExport(outputName.trim(), fmt, isRepair, tW, tH, tFps)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled  = !convState.isConverting &&
                           (!changeResolution || selectedPreset != null) &&
                           (!changeFps || selectedFps != null)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel)
            }

            // ── Diálogo: mismo formato sin ningún cambio ──
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
                            onExport(outputName.trim(), video.extension, true, -1, -1, -1)
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

            // ── Diálogo: confirmar cambios (resolución y/o FPS) con tiempo estimado ──
            if (showConfirmDialog) {
                val dstW = if (pendingTargetW > 0) pendingTargetW else (video.width.takeIf { it > 0 } ?: 1280)
                val dstH = if (pendingTargetH > 0) pendingTargetH else (video.height.takeIf { it > 0 } ?: 720)
                val estSecs = estimatedSeconds(
                    video.duration,
                    video.width.takeIf { it > 0 } ?: dstW,
                    video.height.takeIf { it > 0 } ?: dstH,
                    dstW, dstH
                )
                // Multiplicar estimación si hay interpolación de FPS (proceso extra)
                val fpsMultiplier = if (pendingTargetFps > srcFps) 3 else 1
                val finalEstSecs  = estSecs * fpsMultiplier

                AlertDialog(
                    onDismissRequest = { showConfirmDialog = false },
                    title = { Text("Confirmar exportación") },
                    text  = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Resumen de cambios activos
                            if (pendingTargetW > 0 && pendingTargetH > 0) {
                                Text("📐 Resolución: ${video.resolution} → ${pendingTargetW}×${pendingTargetH}")
                            }
                            if (pendingTargetFps > 0) {
                                val fpsLabel = if (pendingTargetFps > srcFps) "interpolado" else "reducido"
                                Text("🎞 FPS: ~${srcFps}fps → ${pendingTargetFps}fps ($fpsLabel)")
                            }
                            if (pendingFormat != video.extension) {
                                Text("📦 Formato: ${video.extension.uppercase()} → ${pendingFormat.uppercase()}")
                            }
                            HorizontalDivider()
                            Text(
                                "⏱ Tiempo estimado: ${formatEstimate(finalEstSecs)}",
                                fontWeight = FontWeight.Bold
                            )
                            if (pendingTargetFps > srcFps) {
                                Text(
                                    "La interpolación de FPS analiza frame a frame con óptica de movimiento — puede demorar más en videos largos.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "El proceso se ejecutará en segundo plano. Recibirás una notificación al terminar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showConfirmDialog = false
                            onExport(pendingOutputName, pendingFormat, pendingIsRepair,
                                     pendingTargetW, pendingTargetH, pendingTargetFps)
                        }) { Text("Sí, continuar") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showConfirmDialog = false }) {
                            Text("No, cancelar")
                        }
                    }
                )
            }
        }
    }
}
