package com.andoni.convertidor.data

data class GifOptions(
    val startMs: Long = 0,
    val durationMs: Long = 5000,
    val fps: Int = 10,
    val width: Int = 320,
    val loopCount: Int = 0  // 0 = infinito
)

val GIF_FPS_PRESETS = listOf(5, 10, 15, 20)

val GIF_WIDTH_PRESETS = listOf(
    240 to "240p · Ligero",
    320 to "320p · Estándar",
    480 to "480p · Alta calidad"
)

val GIF_LOOP_PRESETS = listOf(
    0 to "Infinito ∞",
    1 to "1 vez",
    3 to "3 veces",
    5 to "5 veces"
)
