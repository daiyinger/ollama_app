package com.example.ollama

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ollama.ui.theme.OllamaTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            OllamaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "history"
                    ) {
                        composable("history") {
                            ConversationHistoryScreen(
                                viewModel = viewModel,
                                onNavigateToConversation = { conversationId ->
                                    navController.navigate("chat/$conversationId")
                                },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable(
                            "chat/{conversationId}",
                            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val conversationId = backStackEntry.arguments?.getString("conversationId")
                            ChatScreen(
                                viewModel = viewModel,
                                conversationId = conversationId,
                                onNavigateUp = { navController.popBackStack() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToLog = { navController.navigate("logViewer") },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        composable("logViewer") {
                            LogViewerScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppEnterBackground()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppEnterForeground()
    }
}
