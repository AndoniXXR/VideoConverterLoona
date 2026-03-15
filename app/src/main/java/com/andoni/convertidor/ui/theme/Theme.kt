package com.andoni.convertidor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary          = Color(0xFF6650A4),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    secondary        = Color(0xFF625B71),
    tertiary         = Color(0xFF7D5260),
    tertiaryContainer= Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D)
)

private val DarkColors = darkColorScheme(
    primary          = Color(0xFFD0BCFF),
    onPrimary        = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    secondary        = Color(0xFFCCC2DC),
    tertiary         = Color(0xFFEFB8C8)
)

@Composable
fun ConvertidorTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
