package app.klang.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.klang.R

/** One colour scheme for the whole device. */
data class Skin(
    val id: String,
    val label: String,
    val note: String,
    val dark: Boolean,
    val desk: Color, val shell: Color, val tile: Color, val tile2: Color, val line: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val hole: Color,
    val sage: Color, val sageLight: Color, val sageDark: Color,
    val lcd: Color, val lcd2: Color, val seg: Color, val ghost: Color, val lcdDim: Color,
    val glow: Color, val glow2: Color, val scaleInk: Color, val scaleInk2: Color, val needle: Color,
    val orange: Color, val orangeDark: Color, val orangeLight: Color,
    val keyHi: Color, val keyLo: Color, val keyLip: Color,
    val keyOn: Color, val keyOnLo: Color, val keyOnLip: Color, val keyOnInk: Color,
    val tickMajor: Color,
    val wood: Color, val wood2: Color,
    /** Thin dark lines between the modules, like the gaps between OP-1 keys. */
    val seam: Color = Color(0xFF2E2D2A),
)

object Skins {
    /** The "Droidamp Rams" concept: warm white plastic, light LCD, orange key. */
    val studio = Skin(
        id = "studio", label = "studio", note = "white plastic, light LCD", dark = false,
        desk = Color(0xFFE2E0DA), shell = Color(0xFFF3F2EE), tile = Color(0xFFE5E3DD), tile2 = Color(0xFFDCDAD3), line = Color(0xFFCDCAC2),
        ink = Color(0xFF232321), ink2 = Color(0xFF5E5C57), ink3 = Color(0xFF9F9D96), hole = Color(0xFF2A2A27),
        sage = Color(0xFFB3BAB4), sageLight = Color(0xFFCDD3CD), sageDark = Color(0xFF959C96),
        lcd = Color(0xFFC8CBC4), lcd2 = Color(0xFFBDC0B9), seg = Color(0xFF252523), ghost = Color(0xFFB2B5AE), lcdDim = Color(0xFF555852),
        glow = Color(0xFFF7E9D6), glow2 = Color(0xFFEFD2AC), scaleInk = Color(0xFF4A3B2C), scaleInk2 = Color(0xFF94795A), needle = Color(0xFFC9362A),
        orange = Color(0xFFF05A1A), orangeDark = Color(0xFFC64712), orangeLight = Color(0xFFFF7038),
        keyHi = Color(0xFFFCFBF8), keyLo = Color(0xFFECEBE6), keyLip = Color(0xFFC6C3BB),
        keyOn = Color(0xFF232321), keyOnLo = Color(0xFF111110), keyOnLip = Color(0xFF000000), keyOnInk = Color.White,
        tickMajor = Color(0xFF3A3A37),
        wood = Color(0xFFC98A4F), wood2 = Color(0xFFB37440),
    )

    /** Black anodised version with an amber display. */
    val graphite = studio.copy(
        id = "graphite", label = "graphite", note = "black, amber display", dark = true, seam = Color(0xFF070707),
        desk = Color(0xFF121211), shell = Color(0xFF1C1C1B), tile = Color(0xFF272725), tile2 = Color(0xFF30302D), line = Color(0xFF3B3B38),
        ink = Color(0xFFECEAE4), ink2 = Color(0xFFADAAA2), ink3 = Color(0xFF6F6D67), hole = Color(0xFF0A0A09),
        sage = Color(0xFF6E756F), sageLight = Color(0xFF8C938D), sageDark = Color(0xFF525853),
        lcd = Color(0xFF2A2C26), lcd2 = Color(0xFF20221D), seg = Color(0xFFF4AE45), ghost = Color(0xFF393B33), lcdDim = Color(0xFFA48A58),
        keyHi = Color(0xFF3C3C39), keyLo = Color(0xFF2F2F2C), keyLip = Color(0xFF111110),
        keyOn = Color(0xFFECEAE4), keyOnLo = Color(0xFFC9C6BE), keyOnLip = Color(0xFF85827A), keyOnInk = Color(0xFF1C1C1B),
        tickMajor = Color(0xFFC9C6BE),
        wood = Color(0xFF7A4E2C), wood2 = Color(0xFF603B1F),
    )

    /** Grey-green housing, like the sage push buttons. */
    val sage = studio.copy(
        id = "sage", label = "sage", note = "grey-green housing", dark = false, seam = Color(0xFF29302A),
        desk = Color(0xFFC8CDC5), shell = Color(0xFFE3E7E1), tile = Color(0xFFD3D9D1), tile2 = Color(0xFFC8CFC6), line = Color(0xFFB5BDB2),
        ink = Color(0xFF1E231F), ink2 = Color(0xFF4F5750), ink3 = Color(0xFF89928A), hole = Color(0xFF262B27),
        sage = Color(0xFF9EA89F), sageLight = Color(0xFFBEC6BF), sageDark = Color(0xFF808A81),
        lcd = Color(0xFFC6CDBC), lcd2 = Color(0xFFB9C1AE), seg = Color(0xFF222720), ghost = Color(0xFFAAB2A0), lcdDim = Color(0xFF4C5549),
        keyHi = Color(0xFFF6F8F4), keyLo = Color(0xFFE3E8E0), keyLip = Color(0xFFB2BBAF),
        keyOn = Color(0xFF1E231F), keyOnLo = Color(0xFF0E110F),
        tickMajor = Color(0xFF353B36),
    )

    /** Cream with walnut cheeks and a warm backlit display. */
    val walnut = studio.copy(
        id = "walnut", label = "walnut", note = "cream, warm display", dark = false, seam = Color(0xFF3A3129),
        desk = Color(0xFFDAD0BE), shell = Color(0xFFF4EEE3), tile = Color(0xFFE8DFCE), tile2 = Color(0xFFDED3BF), line = Color(0xFFCBBDA3),
        ink = Color(0xFF29231C), ink2 = Color(0xFF615646), ink3 = Color(0xFFA19581), hole = Color(0xFF2E2720),
        lcd = Color(0xFFDCCFAD), lcd2 = Color(0xFFCFC09C), seg = Color(0xFF2A231B), ghost = Color(0xFFC4B690), lcdDim = Color(0xFF6B5D43),
        keyHi = Color(0xFFFFFDF8), keyLo = Color(0xFFF0E9DD), keyLip = Color(0xFFC6B89E),
        keyOn = Color(0xFF29231C), keyOnLo = Color(0xFF15110D),
        tickMajor = Color(0xFF3D342A),
        wood = Color(0xFF94603A), wood2 = Color(0xFF774726),
    )

    val all = listOf(studio, graphite, sage, walnut)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: studio
}

/** Design tokens. Colours follow the current [skin]; changing it recolours the whole app. */
object K {
    var skin: Skin by mutableStateOf(Skins.studio)

    val desk get() = skin.desk
    val shell get() = skin.shell
    val tile get() = skin.tile
    val tile2 get() = skin.tile2
    val line get() = skin.line
    val ink get() = skin.ink
    val ink2 get() = skin.ink2
    val ink3 get() = skin.ink3
    val hole get() = skin.hole
    val sage get() = skin.sage
    val sageLight get() = skin.sageLight
    val sageDark get() = skin.sageDark
    val lcd get() = skin.lcd
    val lcd2 get() = skin.lcd2
    val seg get() = skin.seg
    val ghost get() = skin.ghost
    val lcdDim get() = skin.lcdDim
    val glow get() = skin.glow
    val glow2 get() = skin.glow2
    val scaleInk get() = skin.scaleInk
    val scaleInk2 get() = skin.scaleInk2
    val needle get() = skin.needle
    val orange get() = skin.orange
    val orangeDark get() = skin.orangeDark
    val orangeLight get() = skin.orangeLight
    val keyHi get() = skin.keyHi
    val keyLo get() = skin.keyLo
    val keyLip get() = skin.keyLip
    val keyOn get() = skin.keyOn
    val keyOnLo get() = skin.keyOnLo
    val keyOnLip get() = skin.keyOnLip
    val keyOnInk get() = skin.keyOnInk
    val tickMajor get() = skin.tickMajor
    val wood get() = skin.wood
    val wood2 get() = skin.wood2
    val seam get() = skin.seam

    /** IBM Plex Sans Thai covers both Thai and Latin, so it is the UI face. */
    val sans = FontFamily(
        Font(R.font.plex_thai_regular, FontWeight.Normal),
        Font(R.font.plex_thai_medium, FontWeight.Medium),
        Font(R.font.plex_thai_semibold, FontWeight.SemiBold),
        Font(R.font.plex_thai_bold, FontWeight.Bold),
    )
    val mono = FontFamily.Monospace

    val label get() = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink2)
    val body get() = TextStyle(fontFamily = sans, fontSize = 14.sp, color = ink)
    val name get() = TextStyle(fontFamily = sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val value get() = TextStyle(fontFamily = mono, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = ink)
    val lcdText get() = TextStyle(fontFamily = mono, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = seg)
    val lcdSmall get() = TextStyle(fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = lcdDim)
    val wordmark get() = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink, letterSpacing = (-0.4).sp)

    private val thai = Regex("[฀-๿]")

    /** LCD text: monospace for Latin, IBM Plex Sans Thai when the text contains Thai. */
    fun lcd(text: String, base: TextStyle = lcdText): TextStyle =
        if (thai.containsMatchIn(text)) base.copy(fontFamily = sans, letterSpacing = 0.sp) else base
}
