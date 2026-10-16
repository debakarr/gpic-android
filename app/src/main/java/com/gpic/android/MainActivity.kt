package com.gpic.android

import com.gpic.android.ui.screens.albums.AlbumsScreen
import com.gpic.android.ui.screens.albums.AlbumsViewModel
import com.gpic.android.ui.screens.editor.EditorScreen
import com.gpic.android.ui.screens.editor.EditorViewModel
import com.gpic.android.ui.screens.home.Source
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
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
                // Manual factory – no Hilt for v1. Scoped to the Activity's ViewModelStore so it
                // survives recreation (theme/locale change) instead of spawning a second uploader.
                val homeVm = viewModel { HomeViewModel(app, app.credentialStore, app.djiScanner) }
                val albumsVm = viewModel { AlbumsViewModel(app, app.credentialStore) }
                val editorVm = viewModel { EditorViewModel(app, app.credentialStore) }
                val albumState by albumsVm.state.collectAsState()

                // Refresh auth when returning from Auth screen
                LaunchedEffect(nav.currentBackStackEntry) {
                    homeVm.refreshAuth()
                }

                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm = homeVm,
                            onNavigateAuth = { nav.navigate("auth") },
                            onNavigateSettings = { nav.navigate("settings") },
                            onNavigateAlbums = { nav.navigate("albums") },
                            onNavigateEditor = { nav.navigate("editor") },
                            albumSuggestions = albumState.suggestions.size,
                        )
                    }
                    composable("auth") {
                        AuthScreen(store = app.credentialStore, onDone = { nav.popBackStack() })
                    }
                    composable("albums") {
                        AlbumsScreen(vm = albumsVm, onBack = { nav.popBackStack() })
                    }
                    composable("editor") {
                        EditorScreen(
                            vm = editorVm,
                            onBack = { nav.popBackStack() },
                            // The edit goes to the Phone list like a picked file; Upload sends it.
                            onBackUp = { uri ->
                                homeVm.selectSource(Source.DEVICE)
                                homeVm.setDjiFileUris(listOf(uri), persist = false)
                                nav.popBackStack("home", inclusive = false)
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(store = app.credentialStore, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
