package com.gpic.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gpic.android.ui.screens.auth.AuthScreen
import com.gpic.android.ui.screens.home.HomeViewModel
import com.gpic.android.ui.screens.home.HomeScreen
import com.gpic.android.ui.screens.settings.SettingsScreen
import com.gpic.android.ui.theme.GpicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GpicApp

        setContent {
            GpicTheme {
                val nav = rememberNavController()
                // ViewModel manual – no Hilt for v1
                val homeVm = remember { HomeViewModel(this, app.credentialStore, app.djiScanner) }

                // Refresh auth when returning from Auth screen
                LaunchedEffect(nav.currentBackStackEntry) {
                    homeVm.refreshAuth()
                }

                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm = homeVm,
                            onNavigateAuth = { nav.navigate("auth") },
                            onNavigateSettings = { nav.navigate("settings") }
                        )
                    }
                    composable("auth") {
                        AuthScreen(store = app.credentialStore, onDone = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(store = app.credentialStore, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
