package es.peritaje.app.ui.tema

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// Paleta propia: azul marino de alto contraste para usar al sol, con acento azul vivo.
val Marino = Color(0xFF003C88)
val Azul = Color(0xFF2196F3)
val Verde = Color(0xFF1B7F3B)
val Ambar = Color(0xFFB26A00)
val Rojo = Color(0xFFC62828)
val Gris = Color(0xFF5F6B7A)

private val Claro = lightColorScheme(
    primary = Marino,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Azul,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E9FD),
    onSecondaryContainer = Color(0xFF00213A),
    error = Rojo,
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF0E1621),
    surface = Color.White,
    onSurface = Color(0xFF0E1621),
    surfaceVariant = Color(0xFFE3E8F0),
    onSurfaceVariant = Color(0xFF3B4757),
    outline = Color(0xFF6B7788),
)

private val Oscuro = darkColorScheme(
    primary = Color(0xFFABC7FF),
    onPrimary = Color(0xFF002F67),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF00325A),
    error = Color(0xFFFF8A80),
    background = Color(0xFF0E1621),
    onBackground = Color(0xFFE6EBF2),
    surface = Color(0xFF16202D),
    onSurface = Color(0xFFE6EBF2),
    surfaceVariant = Color(0xFF263241),
    onSurfaceVariant = Color(0xFFC1CAD6),
)

// Letra algo más grande de lo normal: se lee en la calle y con prisa.
private val base = Typography()
private val Letras = Typography(
    titleLarge = base.titleLarge.copy(fontSize = 24.sp),
    titleMedium = base.titleMedium.copy(fontSize = 19.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontSize = 17.sp),
)

@Composable
fun TemaPeritaje(oscuro: Boolean = isSystemInDarkTheme(), contenido: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (oscuro) Oscuro else Claro, typography = Letras, content = contenido)
}
