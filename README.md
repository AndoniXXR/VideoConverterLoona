# VideoConverterLoona

An Android app to convert and repair video files, built with Kotlin and Jetpack Compose.

## Features

- Browse all videos stored on the device
- Play videos with a built-in player (ExoPlayer/Media3)
- Convert videos between formats: MP4, WebM, 3GP
- Repair corrupted or malformed video files
- Background conversion with progress notifications
- Automatic output file naming with timestamp fallback
- Exported videos are saved to the public Movies/VideoConvert folder
- Sort video list by date (newest/oldest) or name (A-Z / Z-A)

## Tech Stack

- Language: Kotlin
- UI: Jetpack Compose + Material3
- Video player: Media3 ExoPlayer 1.5.0
- Video conversion: LiTr (LinkedIn) 1.5.7 — native MediaCodec transcoding
- Thumbnails: Coil 2.7.0 with VideoFrameDecoder
- Navigation: Navigation Compose 2.8.5
- Background service: Android ForegroundService (dataSync)
- State: Kotlin StateFlow

## Requirements

- Android 8.0 (API 26) or higher
- Permissions requested at startup: Read Media Video, Post Notifications

## Build

```
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

```
app/src/main/java/com/andoni/convertidor/
    data/           VideoItem model, VideoRepository (MediaStore)
    service/        ConversionService (ForegroundService)
    ui/screens/     VideoListScreen, VideoDetailScreen
    ui/theme/       Material3 theme
    util/           FileUtils (output path builder)
    ConvertidorApp.kt
    MainActivity.kt
```

## Notes

- FFmpegKit was replaced with LiTr due to unavailability on Maven Central.
- LiTr uses Android's native MediaCodec, which supports H.264/AAC in MP4 containers natively.
- WebM and 3GP output depends on device codec support.
