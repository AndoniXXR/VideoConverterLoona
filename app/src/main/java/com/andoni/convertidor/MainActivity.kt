package com.andoni.convertidor

import android.content.Intent
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.andoni.convertidor.ui.screens.VideoDetailScreen
import com.andoni.convertidor.ui.screens.VideoListScreen
import com.andoni.convertidor.ui.theme.ConvertidorTheme
import com.andoni.convertidor.service.ConversionService

class MainActivity : ComponentActivity() {
    private val _currentIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _currentIntent.value = intent
        enableEdgeToEdge()
        setContent {
            ConvertidorTheme {
                AppNavigation(_currentIntent.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _currentIntent.value = intent
    }
}

@Composable
fun AppNavigation(activityIntent: Intent? = null) {
    val navController = rememberNavController()
    var permissionsGranted by remember { mutableStateOf(false) }

    // Navegar al detalle si se abrió desde una notificación
    LaunchedEffect(activityIntent) {
        val videoId = activityIntent?.getLongExtra(ConversionService.EXTRA_VIDEO_ID, -1L) ?: -1L
        if (videoId > 0) {
            navController.navigate("video_detail/$videoId") {
                launchSingleTop = true
            }
        }
    }

    // Permisos necesarios según versión de Android
    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.any { it }
    }

    // Solicitar permisos al iniciar la app
    LaunchedEffect(Unit) { permissionLauncher.launch(permissions) }

    NavHost(navController = navController, startDestination = "video_list") {

        composable("video_list") {
            VideoListScreen(
                onVideoClick = { videoId ->
                    navController.navigate("video_detail/$videoId")
                }
            )
        }

        composable(
            route = "video_detail/{videoId}",
            arguments = listOf(navArgument("videoId") { type = NavType.LongType })
        ) { backStack ->
            val videoId = backStack.arguments?.getLong("videoId") ?: return@composable
            VideoDetailScreen(
                videoId = videoId,
                onBack  = { navController.popBackStack() }
            )
        }
    }
}
