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
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.studio.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repo: StudioRepo
    private lateinit var updater: com.hermes.studio.update.AppUpdateManager

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
                        var label by remember { mutableStateOf("") }
                        LaunchedEffect(Unit) {
                            label = try { repo.baseUrl().substringAfter("://").substringBefore("/") }
                            catch (_: Exception) { "" }
                        }
                        HermesTopBar(online, label) {
                            scope.launch {
                                try { repo.api().capabilities(); online = true } catch (_: Exception) { online = false }
                                nav.navigate("settings")
                            }
                        }
                    },
                    bottomBar = {
                        val back by nav.currentBackStackEntryAsState()
                        val r = back?.destination?.route?.substringBefore("?")
                        NavigationBar(containerColor = Card) {
                            NavigationBarItem(
                                selected = r == "home", onClick = { nav.navigate("home") },
                                icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") },
                                colors = navColors()
                            )
                            NavigationBarItem(
                                selected = r == "create", onClick = { nav.navigate("create") },
                                icon = { Icon(Icons.Filled.AutoAwesome, null) }, label = { Text("Crea") },
                                colors = navColors()
                            )
                            NavigationBarItem(
                                selected = r == "library", onClick = { nav.navigate("library") },
                                icon = { Icon(Icons.Filled.Image, null) }, label = { Text("Libreria") },
                                colors = navColors()
                            )
                            NavigationBarItem(
                                selected = r == "settings", onClick = { nav.navigate("settings") },
                                icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Impostazioni") },
                                colors = navColors()
                            )
                        }
                    }
                ) { pad ->
                    NavHost(nav, "home", Modifier.padding(pad)) {
                        composable("home") { HomeScreen(repo) { nav.navigate(it) } }
                        composable(
                            "create?prompt={prompt}",
                            arguments = listOf(navArgument("prompt") {
                                type = NavType.StringType; nullable = true; defaultValue = null
                            })
                        ) { backStack ->
                            CreateImageScreen(repo, initialPrompt = backStack.arguments?.getString("prompt"))
                        }
                        composable("video") { CreateVideoScreen() }
                        composable("library") { LibraryScreen(repo) { nav.navigate(it) } }
                        composable("settings") { SettingsScreen(repo, updater) }
                    }
                }
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.White,
    selectedTextColor = Color.White,
    unselectedIconColor = Color(0xFF9AA0B4),
    unselectedTextColor = Color(0xFF9AA0B4),
    indicatorColor = Color(0xFF9B5CFF)
)
