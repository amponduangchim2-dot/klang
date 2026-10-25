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
    /** Bright white plastic, black OLED display, red accent, black seams (after the OP-1). */
    val studio = Skin(
        id = "studio", label = "studio", note = "bright white, OLED display", dark = false, seam = Color.Black,
        desk = Color(0xFFF0EFEC), shell = Color(0xFFFCFCFA), tile = Color(0xFFF5F4F1), tile2 = Color(0xFFEBEAE6), line = Color(0xFFDAD8D2),
        ink = Color.Black, ink2 = Color(0xFF55534F), ink3 = Color(0xFF9A9893), hole = Color.Black,
        sage = Color(0xFFB3BAB4), sageLight = Color(0xFFCDD3CD), sageDark = Color(0xFF959C96),
        lcd = Color.Black, lcd2 = Color.Black, seg = Color.White, ghost = Color(0xFF171717), lcdDim = Color(0xFF8E8E8E),
        glow = Color(0xFFF7E9D6), glow2 = Color(0xFFEFD2AC), scaleInk = Color(0xFF4A3B2C), scaleInk2 = Color(0xFF94795A), needle = Color(0xFFF01E14),
        orange = Color(0xFFF01E14), orangeDark = Color(0xFFB0130B), orangeLight = Color(0xFFFF4B3E),
        keyHi = Color.White, keyLo = Color(0xFFF1F0ED), keyLip = Color(0xFFD0CEC7),
        keyOn = Color.Black, keyOnLo = Color.Black, keyOnLip = Color.Black, keyOnInk = Color.White,
        tickMajor = Color.Black,
        wood = Color(0xFFC98A4F), wood2 = Color(0xFFB37440),
    )

    /** Black anodised version. */
    val graphite = studio.copy(
        id = "graphite", label = "graphite", note = "black, OLED display", dark = true, seam = Color.Black,
        desk = Color(0xFF121211), shell = Color(0xFF1C1C1B), tile = Color(0xFF272725), tile2 = Color(0xFF30302D), line = Color(0xFF3B3B38),
        ink = Color(0xFFECEAE4), ink2 = Color(0xFFADAAA2), ink3 = Color(0xFF6F6D67), hole = Color(0xFF0A0A09),
        sage = Color(0xFF6E756F), sageLight = Color(0xFF8C938D), sageDark = Color(0xFF525853),
        lcd = Color.Black, lcd2 = Color.Black, seg = Color.White, ghost = Color(0xFF171717), lcdDim = Color(0xFF8E8E8E),
        keyHi = Color(0xFF3C3C39), keyLo = Color(0xFF2F2F2C), keyLip = Color(0xFF111110),
        keyOn = Color(0xFFECEAE4), keyOnLo = Color(0xFFC9C6BE), keyOnLip = Color(0xFF85827A), keyOnInk = Color(0xFF1C1C1B),
        tickMajor = Color(0xFFC9C6BE),
        wood = Color(0xFF7A4E2C), wood2 = Color(0xFF603B1F),
    )

    /** Grey-green housing, like the sage push buttons. */
    val sage = studio.copy(
        id = "sage", label = "sage", note = "grey-green housing", dark = false, seam = Color.Black,
        desk = Color(0xFFC8CDC5), shell = Color(0xFFE3E7E1), tile = Color(0xFFD3D9D1), tile2 = Color(0xFFC8CFC6), line = Color(0xFFB5BDB2),
        ink = Color(0xFF1E231F), ink2 = Color(0xFF4F5750), ink3 = Color(0xFF89928A), hole = Color(0xFF262B27),
        sage = Color(0xFF9EA89F), sageLight = Color(0xFFBEC6BF), sageDark = Color(0xFF808A81),
        lcd = Color.Black, lcd2 = Color.Black, seg = Color.White, ghost = Color(0xFF171717), lcdDim = Color(0xFF8E8E8E),
        keyHi = Color(0xFFF6F8F4), keyLo = Color(0xFFE3E8E0), keyLip = Color(0xFFB2BBAF),
        keyOn = Color.Black, keyOnLo = Color.Black,
        tickMajor = Color(0xFF353B36),
    )

    /** Warm cream housing. */
    val walnut = studio.copy(
        id = "walnut", label = "walnut", note = "cream, OLED display", dark = false, seam = Color.Black,
        desk = Color(0xFFDAD0BE), shell = Color(0xFFF4EEE3), tile = Color(0xFFE8DFCE), tile2 = Color(0xFFDED3BF), line = Color(0xFFCBBDA3),
        ink = Color(0xFF29231C), ink2 = Color(0xFF615646), ink3 = Color(0xFFA19581), hole = Color(0xFF2E2720),
        lcd = Color.Black, lcd2 = Color.Black, seg = Color.White, ghost = Color(0xFF171717), lcdDim = Color(0xFF8E8E8E),
        keyHi = Color(0xFFFFFDF8), keyLo = Color(0xFFF0E9DD), keyLip = Color(0xFFC6B89E),
        keyOn = Color.Black, keyOnLo = Color.Black,
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

    /** Klang Sans: Outfit for Latin (capitals drawn lowercase) + IBM Plex Sans Thai for Thai, merged into one font. */
    val sans = FontFamily(
        Font(R.font.klang_regular, FontWeight.Normal),
        Font(R.font.klang_medium, FontWeight.Medium),
        Font(R.font.klang_semibold, FontWeight.SemiBold),
        Font(R.font.klang_bold, FontWeight.Bold),
    )
    /** Numbers line up in columns (tabular figures). */
    val mono = sans
    private const val TNUM = "tnum"

    val label get() = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink2)
    val body get() = TextStyle(fontFamily = sans, fontSize = 14.sp, color = ink)
    val name get() = TextStyle(fontFamily = sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val value get() = TextStyle(fontFamily = mono, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = ink, fontFeatureSettings = TNUM)
    val lcdText get() = TextStyle(fontFamily = mono, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = seg, fontFeatureSettings = TNUM)
    val lcdSmall get() = TextStyle(fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = lcdDim, fontFeatureSettings = TNUM)
    val wordmark get() = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink, letterSpacing = (-0.4).sp)

    /** LCD text: monospace for Latin, IBM Plex Sans Thai when the text contains Thai. */
    fun lcd(@Suppress("UNUSED_PARAMETER") text: String, base: TextStyle = lcdText): TextStyle = base
}
