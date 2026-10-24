package app.klang.eq

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** One EQ curve: ten band gains and a preamp, all in dB (-12..+12). */
data class EqPreset(val name: String, val bands: List<Float>, val preamp: Float = 0f) {
    init {
        require(bands.size == 10)
    }
}

object BuiltInPresets {
    private fun p(name: String, vararg db: Int) = EqPreset(name, db.map { it.toFloat() })

    /** The sixteen presets that ship with Winamp-style players, in the same order. */
    val all: List<EqPreset> = listOf(
        p("Classical", 0, 0, 0, 0, 0, 0, -4, -4, -4, -6),
        p("Club", 0, 0, 4, 3, 3, 3, 2, 0, 0, 0),
        p("Dance", 6, 4, 1, 0, 0, -3, -4, -4, 0, 0),
        p("Flat", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        p("Full Bass", 6, 6, 6, 4, 1, -2, -5, -6, -6, -6),
        p("Full Bass & Treble", 4, 3, 0, -4, -3, 1, 5, 7, 7, 7),
        p("Full Treble", -6, -6, -6, -2, 2, 7, 10, 10, 10, 10),
        p("Live", -3, 0, 2, 3, 3, 3, 2, 1, 1, 1),
        p("Party", 4, 4, 0, 0, 0, 0, 0, 0, 4, 4),
        p("Pop", -1, 3, 4, 4, 3, 0, -1, -1, -1, -1),
        p("Reggae", 0, 0, 0, -3, 0, 3, 3, 0, 0, 0),
        p("Rock", 5, 3, -3, -5, -2, 2, 5, 7, 7, 7),
        p("Ska", -1, -3, -2, 0, 2, 3, 5, 5, 6, 5),
        p("Soft", 3, 1, 0, -1, 0, 2, 5, 6, 7, 7),
        p("Soft Rock", 2, 2, 1, 0, -2, -3, -2, 0, 1, 5),
        p("Techno", 5, 3, 0, -3, -3, 0, 5, 6, 6, 5),
    )

    /** Shown on the 1–4 quick keys. */
    val quick = listOf("Flat", "Rock", "Pop", "Full Bass")

    fun byName(name: String) = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

class EqfException(message: String) : Exception(message)

/**
 * Winamp EQ library file (.eqf), version 1.1:
 * 27-byte magic "Winamp EQ library file v1.1", 0x1A, "!--", then entries of
 * 257 bytes name (zero padded) + 10 band bytes + 1 preamp byte.
 * Byte 0 is +12 dB and byte 63 is -12 dB.
 */
object Eqf {
    private const val MAGIC = "Winamp EQ library file v1.1"
    private const val NAME_LEN = 257

    private fun toByte(db: Float): Int = (63f - (db.coerceIn(-12f, 12f) + 12f) / 24f * 63f).roundToInt().coerceIn(0, 63)
    private fun toDb(b: Int): Float {
        val v = b.coerceIn(0, 63)
        val db = (63 - v) / 63f * 24f - 12f
        return (db * 2f).roundToInt() / 2f
    }

    fun read(bytes: ByteArray): List<EqPreset> {
        val head = String(bytes, 0, minOf(bytes.size, 24), Charsets.ISO_8859_1)
        if (!head.startsWith("Winamp EQ library file")) throw EqfException("That is not a Winamp EQ preset file")
        // header = magic + 0x1A + "!--"
        var pos = bytes.indexOf(0x1A.toByte()).let { if (it < 0) throw EqfException("That EQ preset file is damaged") else it + 4 }
        val out = ArrayList<EqPreset>()
        while (pos + NAME_LEN + 11 <= bytes.size) {
            var end = pos
            while (end < pos + NAME_LEN && bytes[end] != 0.toByte()) end++
            val utf = String(bytes, pos, end - pos, Charsets.UTF_8)
            val text = if ('\uFFFD' in utf) String(bytes, pos, end - pos, Charsets.ISO_8859_1) else utf
            val name = text.trim().ifBlank { "Preset ${out.size + 1}" }
            pos += NAME_LEN
            val bands = (0 until 10).map { toDb(bytes[pos + it].toInt() and 0xFF) }
            val pre = toDb(bytes[pos + 10].toInt() and 0xFF)
            pos += 11
            out += EqPreset(name, bands, pre)
        }
        if (out.isEmpty()) throw EqfException("That EQ preset file is damaged")
        return out
    }

    fun write(presets: List<EqPreset>): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(MAGIC.toByteArray(Charsets.ISO_8859_1))
        b.write(0x1A)
        b.write("!--".toByteArray(Charsets.ISO_8859_1))
        for (p in presets) {
            val name = ByteArray(NAME_LEN)
            val raw = p.name.toByteArray(Charsets.UTF_8)
            System.arraycopy(raw, 0, name, 0, minOf(raw.size, NAME_LEN - 1))
            b.write(name)
            p.bands.forEach { b.write(toByte(it)) }
            b.write(toByte(p.preamp))
        }
        return b.toByteArray()
    }
}
