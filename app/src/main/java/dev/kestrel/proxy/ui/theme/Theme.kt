package dev.kestrel.proxy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.kestrel.proxy.data.model.ThemeMode

object KestrelColors {
    val DirectGreen = Color(0xFF16A34A)
    val DirectGreenContainerLight = Color(0xFFDCFCE7)
    val DirectGreenContainerDark = Color(0xFF113822)
    val DirectGreenOnDark = Color(0xFF6EE7B7)

    val BlockRed = Color(0xFFDC2626)
    val BlockRedContainerLight = Color(0xFFFEE2E2)
    val BlockRedContainerDark = Color(0xFF451214)
    val BlockRedOnDark = Color(0xFFFCA5A5)

    val WarningAmber = Color(0xFFD97706)
    val WarningAmberContainerLight = Color(0xFFFEF3C7)
    val WarningAmberContainerDark = Color(0xFF3F2907)
    val WarningAmberOnDark = Color(0xFFFCD34D)

    val ProxyTeal = Color(0xFF007A6C)
    val ProxyTealDark = Color(0xFF5CDAC5)
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006A5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8FF5E2),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E1),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF3E6375),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC2E8FD),
    onTertiaryContainer = Color(0xFF001E2B),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF6FAF8),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F3),
    surfaceContainer = Color(0xFFEAEFED),
    surfaceContainerHigh = Color(0xFFE4EAE7),
    outline = Color(0xFF6F7976),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// Deep AMOLED-friendly dark palette
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5CDAC5),
    onPrimary = Color(0xFF003830),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF8FF5E2),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCCE8E1),
    tertiary = Color(0xFFA6CCE0),
    onTertiary = Color(0xFF093545),
    tertiaryContainer = Color(0xFF254B5D),
    onTertiaryContainer = Color(0xFFC2E8FD),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE2E8E6),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE2E8E6),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0F11),
    surfaceContainer = Color(0xFF12181B),
    surfaceContainerHigh = Color(0xFF1A2226),
    outline = Color(0xFF899390),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val KestrelTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

@Composable
fun KestrelTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val base = if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (useDark && themeMode == ThemeMode.DARK) {
                base.copy(background = Color(0xFF000000), surface = Color(0xFF000000))
            } else {
                base
            }
        }
        useDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KestrelTypography,
        content = content,
    )
}
