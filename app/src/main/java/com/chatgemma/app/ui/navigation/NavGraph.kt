package com.chatgemma.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chatgemma.app.ui.screens.chat.ChatScreen
import com.chatgemma.app.ui.screens.models.ModelManagerScreen
import com.chatgemma.app.ui.screens.sessions.SessionListScreen
import com.chatgemma.app.ui.screens.settings.SettingsScreen
import com.chatgemma.app.ui.screens.topics.TopicManagerScreen

@Composable
fun ChatGemmaNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.SessionList.route
    ) {
        composable(Screen.SessionList.route) {
            SessionListScreen(
                onSessionClick = { sessionId, branchId ->
                    navController.navigate(Screen.Chat().createRoute(sessionId, branchId))
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onModelsClick = { navController.navigate(Screen.ModelManager.route) }
            )
        }

        composable(
            route = Screen.Chat().route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("branchId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val branchId = backStackEntry.arguments?.getString("branchId") ?: return@composable
            ChatScreen(
                sessionId = sessionId,
                branchId = branchId,
                onNavigateUp = { navController.popBackStack() },
                onOpenTopicManager = {
                    navController.navigate(Screen.TopicManager().createRoute(sessionId))
                },
                onBranchSwitch = { newBranchId ->
                    navController.navigate(Screen.Chat().createRoute(sessionId, newBranchId)) {
                        popUpTo(Screen.Chat().route) { inclusive = true }
                    }
                },
                onSessionsClick = { navController.popBackStack(Screen.SessionList.route, false) }
            )
        }

        composable(Screen.ModelManager.route) {
            ModelManagerScreen(
                onNavigateUp = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.TopicManager().route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            TopicManagerScreen(
                sessionId = sessionId,
                onNavigateUp = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateUp = { navController.popBackStack() }
            )
        }
    }
}
