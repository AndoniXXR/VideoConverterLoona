package com.andoni.convertidor

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConvertidorTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

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
    ) { /* los resultados se gestionan a nivel de pantalla */ }

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
