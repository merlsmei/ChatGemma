package com.chatgemma.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.White,
    primaryContainer = DarkPrimaryVariant,
    secondary = DarkSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    error = DarkError,
    outline = DarkDivider
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryVariant,
    secondary = LightSecondary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    error = LightError,
    outline = LightDivider
)

data class ChatGemmaColors(
    val userBubble: Color,
    val modelBubble: Color,
    val inputBackground: Color,
    val surfaceElevated: Color,
    val divider: Color
)

val LocalChatGemmaColors = staticCompositionLocalOf {
    ChatGemmaColors(
        userBubble = DarkUserBubble,
        modelBubble = DarkModelBubble,
        inputBackground = DarkInputBackground,
        surfaceElevated = DarkSurfaceElevated,
        divider = DarkDivider
    )
}

@Composable
fun ChatGemmaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val chatColors = if (darkTheme) {
        ChatGemmaColors(DarkUserBubble, DarkModelBubble, DarkInputBackground, DarkSurfaceElevated, DarkDivider)
    } else {
        ChatGemmaColors(LightUserBubble, LightModelBubble, LightInputBackground, LightSurfaceElevated, LightDivider)
    }

    CompositionLocalProvider(LocalChatGemmaColors provides chatColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ChatGemmaTypography,
            content = content
        )
    }
}
