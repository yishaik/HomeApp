package com.yishaik.homeapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val EventColor = Color(0xFF3C6EED)
val TaskColor = Color(0xFFE78A1A)
val ListColor = Color(0xFF299764)
val NoteColor = Color(0xFFB38B00)
val Primary = Color(0xFF5A5BD7)

private val Light = lightColorScheme(
    primary = Primary,
    secondary = Color(0xFF706FA8),
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F1F6),
    outline = Color(0xFFE1E3EA),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC5C3FF),
    secondary = Color(0xFFC6C4E8),
    background = Color(0xFF111318),
    surface = Color(0xFF191B20),
    surfaceVariant = Color(0xFF23252C),
)

@Composable
fun HomeAppTheme(accentArgb: Long = 0xFF5A5BD7, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val accent = Color(accentArgb)
    val scheme = if (dark) Dark.copy(primary = accent) else Light.copy(primary = accent)
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }
}
