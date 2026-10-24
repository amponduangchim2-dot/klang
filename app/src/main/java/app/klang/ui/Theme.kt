package app.klang.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.klang.R

/** Design tokens, matching the "Droidamp Rams" HTML concept. */
object K {
    val desk = Color(0xFFE2E0DA)
    val shell = Color(0xFFF3F2EE)
    val tile = Color(0xFFE5E3DD)
    val tile2 = Color(0xFFDCDAD3)
    val line = Color(0xFFCDCAC2)
    val ink = Color(0xFF232321)
    val ink2 = Color(0xFF5E5C57)
    val ink3 = Color(0xFF9F9D96)
    val hole = Color(0xFF2A2A27)
    val sage = Color(0xFFB3BAB4)
    val sageLight = Color(0xFFCDD3CD)
    val sageDark = Color(0xFF959C96)
    val lcd = Color(0xFFC8CBC4)
    val lcd2 = Color(0xFFBDC0B9)
    val seg = Color(0xFF252523)
    val ghost = Color(0xFFB2B5AE)
    val lcdDim = Color(0xFF555852)
    val glow = Color(0xFFF7E9D6)
    val glow2 = Color(0xFFEFD2AC)
    val scaleInk = Color(0xFF4A3B2C)
    val scaleInk2 = Color(0xFF94795A)
    val needle = Color(0xFFC9362A)
    val orange = Color(0xFFF05A1A)
    val orangeDark = Color(0xFFC64712)
    val orangeLight = Color(0xFFFF7038)
    val keyHi = Color(0xFFFCFBF8)
    val keyLo = Color(0xFFECEBE6)
    val keyLip = Color(0xFFC6C3BB)
    val wood = Color(0xFFC98A4F)
    val wood2 = Color(0xFFB37440)

    /** IBM Plex Sans Thai covers both Thai and Latin, so it is the UI face. */
    val sans = FontFamily(
        Font(R.font.plex_thai_regular, FontWeight.Normal),
        Font(R.font.plex_thai_medium, FontWeight.Medium),
        Font(R.font.plex_thai_semibold, FontWeight.SemiBold),
        Font(R.font.plex_thai_bold, FontWeight.Bold),
    )
    val mono = FontFamily.Monospace

    val label = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink2)
    val body = TextStyle(fontFamily = sans, fontSize = 14.sp, color = ink)
    val name = TextStyle(fontFamily = sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val value = TextStyle(fontFamily = mono, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = ink)
    val lcdText = TextStyle(fontFamily = mono, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = seg)
    val lcdSmall = TextStyle(fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = lcdDim)
    val wordmark = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink, letterSpacing = (-0.4).sp)

    private val thai = Regex("[฀-๿]")

    /** LCD text: monospace for Latin, IBM Plex Sans Thai when the text contains Thai. */
    fun lcd(text: String, base: TextStyle = lcdText): TextStyle =
        if (thai.containsMatchIn(text)) base.copy(fontFamily = sans, letterSpacing = 0.sp) else base
}
