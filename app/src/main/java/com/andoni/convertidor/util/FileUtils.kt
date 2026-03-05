package com.andoni.convertidor.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    /** Carpeta exclusiva de la app: /sdcard/Android/data/<pkg>/files/Movies/ */
    fun getOutputDirectory(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Devuelve una ruta única para el archivo de salida.
     * Si ya existe un archivo con ese nombre, añade un sufijo numérico.
     */
    fun buildOutputPath(context: Context, baseName: String, format: String): String {
        val dir  = getOutputDirectory(context)
        val name = baseName.trim().ifBlank { autoName() }
        var file = File(dir, "$name.$format")
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${name}_$counter.$format")
            counter++
        }
        return file.absolutePath
    }

    private fun autoName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "video_${sdf.format(Date())}"
    }
}
