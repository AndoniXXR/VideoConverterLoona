package com.andoni.convertidor.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val GITHUB_API =
        "https://api.github.com/repos/AndoniXXR/VideoConverterLoona/releases/latest"

    /**
     * Consulta la última release de GitHub y compara con la versión instalada.
     * Devuelve [UpdateInfo] si hay nueva versión, null si está al día.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            Log.i(TAG, "Versión instalada: $currentVersion")

            val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API respondió ${conn.responseCode}")
                conn.disconnect()
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val obj = org.json.JSONObject(json)
            val tagName = obj.getString("tag_name").removePrefix("v").trim()
            val body = obj.optString("body", "").trim()
            Log.i(TAG, "Última release: $tagName")

            if (!isNewer(tagName, currentVersion)) {
                Log.i(TAG, "No hay actualización ($tagName <= $currentVersion)")
                return@withContext null
            }

            // Buscar asset .apk en la release
            val assets = obj.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl == null) {
                Log.w(TAG, "Release $tagName no tiene APK adjunto")
                return@withContext null
            }

            Log.i(TAG, "Actualización disponible: $tagName → $apkUrl")
            UpdateInfo(tagName, apkUrl, body)
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando actualizaciones", e)
            null
        }
    }

    /**
     * Descarga el APK usando DownloadManager y lo instala al completar.
     */
    fun downloadAndInstall(context: Context, update: UpdateInfo) {
        val fileName = "VideoConvert-${update.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("Actualizando VideoConvert")
            .setDescription("Descargando v${update.versionName}…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        Log.i(TAG, "Descarga iniciada id=$downloadId")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)
                Log.i(TAG, "Descarga completada, instalando…")

                val apkFile = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                installApk(context, apkFile)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK no encontrado: ${apkFile.absolutePath}")
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Compara versiones semánticas. Devuelve true si [remote] > [local].
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(r.size, l.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false // iguales
    }
}
