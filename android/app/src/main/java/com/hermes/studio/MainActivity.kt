package com.hermes.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermes.studio.ui.*
import com.hermes.studio.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repo: StudioRepo
    private lateinit var updater: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StudioRepo(applicationContext)
        updater = (application as HermesStudioApp).updateManager
        setContent {
            HermesTheme {
                val nav = rememberNavController()
                var online by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    // Test autenticato reale: capabilities, non health (no-auth darebbe falso online).
                    try { repo.api().capabilities(); online = true } catch (_: Exception) { online = false }
                    launch(Dispatchers.IO) { runCatching { updater.checkForUpdates() } }
                    updater.ensureBackgroundChecksConfigured()
                }
                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(title = { Text("Hermes Studio") },
                            actions = {
                                var label by remember { mutableStateOf("") }
                                LaunchedEffect(Unit) {
                                    label = try { repo.baseUrl().substringAfter("://").substringBefore("/") }
                                    catch (_: Exception) { "" }
                                }
                                HeaderChip(online, label) {
                                    scope.launch {
                                        try { repo.api().capabilities(); online = true } catch (_: Exception) { online = false }
                                        nav.navigate("settings")
                                    }
                                }
                            })
                    },
                    bottomBar = {
                        val back by nav.currentBackStackEntryAsState()
                        val r = back?.destination?.route
                        NavigationBar {
                            NavigationBarItem(selected = r == "home", onClick = { nav.navigate("home") },
                                icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
                            NavigationBarItem(selected = r == "create", onClick = { nav.navigate("create") },
                                icon = { Icon(Icons.Filled.AutoAwesome, null) }, label = { Text("Crea") })
                            NavigationBarItem(selected = r == "library", onClick = { nav.navigate("library") },
                                icon = { Icon(Icons.Filled.PhotoLibrary, null) }, label = { Text("Libreria") })
                            NavigationBarItem(selected = r == "settings", onClick = { nav.navigate("settings") },
                                icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Impostazioni") })
                        }
                    }
                ) { pad ->
                    NavHost(nav, "home", Modifier.padding(pad)) {
                        composable("home") { HomeScreen(repo, updater) { nav.navigate(it) } }
                        composable("create") { CreateImageScreen(repo) }
                        composable("video") { CreateVideoScreen() }
                        composable("library") { LibraryScreen(repo) }
                        composable("settings") { SettingsScreen(repo, updater) }
                    }
                }
            }
        }
    }
}
