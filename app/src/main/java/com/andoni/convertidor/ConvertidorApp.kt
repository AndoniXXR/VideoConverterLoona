package com.andoni.convertidor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.andoni.convertidor.service.ConversionService

class ConvertidorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ConversionService.CHANNEL_ID,
            "Conversión de video",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificaciones de progreso de conversión de video"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
