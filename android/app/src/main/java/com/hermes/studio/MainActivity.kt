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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = StudioRepo(applicationContext)
        setContent {
            HermesTheme {
                val nav = rememberNavController()
                var online by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    try { repo.api().health(); online = true } catch (_: Exception) { online = false }
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
                                HeaderChip(online, label) { nav.navigate("settings") }
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
                        composable("home") { HomeScreen(repo) { nav.navigate(it) } }
                        composable("create") { CreateImageScreen(repo) }
                        composable("video") { CreateVideoScreen() }
                        composable("library") { LibraryScreen(repo) }
                        composable("settings") { SettingsScreen(repo) }
                    }
                }
            }
        }
    }
}
