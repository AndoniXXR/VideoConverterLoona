package com.andoni.convertidor.data

data class FpsOption(
    val fps: Int,
    val label: String,
    val socialHint: String
)

val FPS_PRESETS = listOf(
    FpsOption(24,  "24 fps",  "Cine · Facebook · estilo cinematográfico"),
    FpsOption(25,  "25 fps",  "Estándar PAL (Europa)"),
    FpsOption(30,  "30 fps",  "YouTube · Instagram · WhatsApp · estándar web"),
    FpsOption(48,  "48 fps",  "Interpolado 24→48 · alta fluidez"),
    FpsOption(60,  "60 fps",  "TikTok suave · YouTube 60fps · gaming"),
    FpsOption(120, "120 fps", "Cámara lenta · slow-motion visual")
)
