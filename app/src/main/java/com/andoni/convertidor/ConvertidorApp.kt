package com.andoni.convertidor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.andoni.convertidor.service.ConversionService

class ConvertidorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Canal silencioso para el progreso (barra persistente sin sonido)
        nm.createNotificationChannel(
            NotificationChannel(
                ConversionService.CHANNEL_ID,
                "Progreso de conversión",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Barra de progreso mientras se convierte el video"
                setSound(null, null)
                enableVibration(false)
            }
        )

        // Canal con prioridad DEFAULT para que aparezca como heads-up
        // aunque la app esté en primer plano
        nm.createNotificationChannel(
            NotificationChannel(
                ConversionService.CHANNEL_ALERT_ID,
                "Alertas de conversión",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Aviso cuando el video termina de convertirse"
            }
        )
    }
}
