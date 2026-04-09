package com.chatgemma.app.ui.navigation

sealed class Screen(val route: String) {
    data object SessionList : Screen("sessions")
    data class Chat(val sessionId: String = "{sessionId}", val branchId: String = "{branchId}") :
        Screen("chat/{sessionId}/{branchId}") {
        fun createRoute(sessionId: String, branchId: String) = "chat/$sessionId/$branchId"
    }
    data object ModelManager : Screen("models")
    data class TopicManager(val sessionId: String = "{sessionId}") :
        Screen("topics/{sessionId}") {
        fun createRoute(sessionId: String) = "topics/$sessionId"
    }
    data object Settings : Screen("settings")
}
