package app.klang.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design tokens, matching the "Droidamp Rams" HTML concept. */
object K {
    val desk = Color(0xFFE7E5E0)
    val shell = Color(0xFFF3F2EE)
    val tile = Color(0xFFE9E7E2)
    val tile2 = Color(0xFFE1DFD9)
    val line = Color(0xFFD3D0C9)
    val ink = Color(0xFF232321)
    val ink2 = Color(0xFF7F7D77)
    val ink3 = Color(0xFFAEACA5)
    val hole = Color(0xFF2A2A27)
    val sage = Color(0xFFB3BAB4)
    val sageLight = Color(0xFFCDD3CD)
    val sageDark = Color(0xFF959C96)
    val lcd = Color(0xFFC8CBC4)
    val lcd2 = Color(0xFFBDC0B9)
    val seg = Color(0xFF252523)
    val ghost = Color(0xFFB2B5AE)
    val lcdDim = Color(0xFF6D706A)
    val glow = Color(0xFFF7E9D6)
    val glow2 = Color(0xFFEFD2AC)
    val scaleInk = Color(0xFF5A4A3A)
    val scaleInk2 = Color(0xFFA58F74)
    val needle = Color(0xFFC9362A)
    val orange = Color(0xFFF05A1A)
    val orangeDark = Color(0xFFC64712)
    val orangeLight = Color(0xFFFF7038)
    val keyHi = Color(0xFFFCFBF8)
    val keyLo = Color(0xFFECEBE6)
    val wood = Color(0xFFC98A4F)
    val wood2 = Color(0xFFB37440)

    val sans = FontFamily.SansSerif
    val mono = FontFamily.Monospace

    val label = TextStyle(fontFamily = sans, fontSize = 11.sp, color = ink2)
    val body = TextStyle(fontFamily = sans, fontSize = 13.sp, color = ink)
    val name = TextStyle(fontFamily = sans, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val value = TextStyle(fontFamily = mono, fontSize = 11.5.sp, color = ink)
    val lcdText = TextStyle(fontFamily = mono, fontSize = 12.sp, color = seg)
    val lcdSmall = TextStyle(fontFamily = mono, fontSize = 10.sp, color = lcdDim)
    val wordmark = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink, letterSpacing = (-0.4).sp)
}
