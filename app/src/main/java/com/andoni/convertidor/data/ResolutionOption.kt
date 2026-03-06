package com.andoni.convertidor.data

data class ResolutionOption(
    val label: String,
    val width: Int,
    val height: Int,
    val socialHint: String
)

fun resolutionPresetsFor(videoWidth: Int, videoHeight: Int): List<ResolutionOption> =
    if (videoHeight > videoWidth) VERTICAL_PRESETS else HORIZONTAL_PRESETS

val HORIZONTAL_PRESETS = listOf(
    ResolutionOption("240p",           426,  240,  ""),
    ResolutionOption("360p",           640,  360,  ""),
    ResolutionOption("480p SD",        854,  480,  "WhatsApp"),
    ResolutionOption("720p HD",       1280,  720,  "Twitter/X · WhatsApp · YouTube"),
    ResolutionOption("1080p Full HD", 1920, 1080,  "YouTube HD · Facebook · TikTok horizontal"),
    ResolutionOption("1440p 2K",      2560, 1440,  "YouTube 2K"),
    ResolutionOption("2160p 4K",      3840, 2160,  "YouTube 4K")
)

val VERTICAL_PRESETS = listOf(
    ResolutionOption("360p vertical",   360,  640,  ""),
    ResolutionOption("480p vertical",   480,  854,  "WhatsApp Status"),
    ResolutionOption("720p vertical",   720, 1280,  "TikTok · Twitter/X"),
    ResolutionOption("1080p vertical", 1080, 1920,  "TikTok · Instagram Reels/Stories · YouTube Shorts · Snapchat · Facebook Reels")
)
