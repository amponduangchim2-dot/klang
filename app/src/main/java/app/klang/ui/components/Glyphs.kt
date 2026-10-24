package app.klang.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph {
    Prev, Next, Play, Pause, Stop, Eject,
    Library, Search, Lyrics, Skins, Sleep, Cast, Share, Options, Back, Shuffle, Equalizer, List
}

/** Draws [glyph] on a 24x24 grid, scaled to the canvas. Filled glyphs for transport, line glyphs for tools. */
fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val u = minOf(size.width, size.height) / 24f
    val ox = (size.width - 24f * u) / 2f
    val oy = (size.height - 24f * u) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1f) =
        drawRoundRect(color, p(x, y), Size(w * u, h * u), CornerRadius(r * u, r * u))
    fun tri(vararg pts: Float) {
        val path = Path()
        path.moveTo(p(pts[0], pts[1]).x, p(pts[0], pts[1]).y)
        var i = 2
        while (i < pts.size) { val q = p(pts[i], pts[i + 1]); path.lineTo(q.x, q.y); i += 2 }
        path.close()
        drawPath(path, color)
    }
    val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(vararg pts: Float) {
        val path = Path()
        path.moveTo(p(pts[0], pts[1]).x, p(pts[0], pts[1]).y)
        var i = 2
        while (i < pts.size) { val q = p(pts[i], pts[i + 1]); path.lineTo(q.x, q.y); i += 2 }
        drawPath(path, color, style = stroke)
    }
    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(color, r * u, p(cx, cy), style = stroke)

    when (glyph) {
        Glyph.Prev -> { rect(4f, 5f, 2.4f, 14f); tri(20f, 5f, 20f, 19f, 8f, 12f) }
        Glyph.Next -> { rect(17.6f, 5f, 2.4f, 14f); tri(4f, 5f, 4f, 19f, 16f, 12f) }
        Glyph.Play -> tri(7f, 4.5f, 7f, 19.5f, 20f, 12f)
        Glyph.Pause -> { rect(6f, 5f, 4f, 14f); rect(14f, 5f, 4f, 14f) }
        Glyph.Stop -> rect(6f, 6f, 12f, 12f, 1.5f)
        Glyph.Eject -> { tri(12f, 4.5f, 19.5f, 13f, 4.5f, 13f); rect(4.5f, 15.5f, 15f, 3f) }
        Glyph.Library -> { line(5f, 5f, 8.5f, 5f, 8.5f, 19f, 5f, 19f, 5f, 5f); line(10.5f, 5f, 14f, 5f, 14f, 19f, 10.5f, 19f, 10.5f, 5f); line(16f, 6f, 19.3f, 6.8f, 16.3f, 19.4f, 13f, 18.6f) }
        Glyph.Search -> { ring(10.5f, 10.5f, 5.5f); line(15f, 15f, 19.5f, 19.5f) }
        Glyph.Lyrics -> { line(6f, 8f, 18f, 8f); line(6f, 12f, 15f, 12f); line(6f, 16f, 12f, 16f) }
        Glyph.Skins -> { line(5f, 7f, 19f, 7f, 19f, 17f, 5f, 17f, 5f, 7f); line(8f, 11f, 11f, 11f); line(8f, 13.5f, 11f, 13.5f); ring(15.5f, 12f, 1.6f) }
        Glyph.Sleep -> { line(17.5f, 14f, 15f, 15f, 12f, 15f, 9.5f, 13.5f, 8f, 11f, 8f, 8f, 9f, 6f, 10f, 6.5f); line(10f, 6.5f, 7f, 7.5f, 5.5f, 10f, 5.5f, 13.5f, 7f, 16.5f, 10f, 18.3f, 13.5f, 18.3f, 16f, 17f, 17.5f, 14f) }
        Glyph.Cast -> { ring(5f, 19f, 0.6f); line(5f, 14f, 7.5f, 14.7f, 9.3f, 16.5f, 10f, 19f); line(5f, 10.5f, 9f, 11.6f, 12f, 14.5f, 13.5f, 19f) }
        Glyph.Share -> { line(12f, 14f, 12f, 5f); line(8.5f, 8.5f, 12f, 5f, 15.5f, 8.5f); line(6.5f, 12f, 6.5f, 18f, 17.5f, 18f, 17.5f, 12f) }
        Glyph.Options -> { ring(12f, 12f, 3.2f); line(12f, 5f, 12f, 7f); line(12f, 17f, 12f, 19f); line(5f, 12f, 7f, 12f); line(17f, 12f, 19f, 12f) }
        Glyph.Back -> line(14f, 6f, 8f, 12f, 14f, 18f)
        Glyph.Shuffle -> { line(4f, 8f, 8f, 8f, 16f, 16f, 20f, 16f); line(4f, 16f, 8f, 16f, 16f, 8f, 20f, 8f) }
        Glyph.Equalizer -> { line(7f, 5f, 7f, 19f); line(12f, 5f, 12f, 19f); line(17f, 5f, 17f, 19f); rect(5f, 13f, 4f, 2f); rect(10f, 8f, 4f, 2f); rect(15f, 11f, 4f, 2f) }
        Glyph.List -> { line(8f, 7f, 19f, 7f); line(8f, 12f, 19f, 12f); line(8f, 17f, 19f, 17f); rect(4.5f, 6f, 2f, 2f); rect(4.5f, 11f, 2f, 2f); rect(4.5f, 16f, 2f, 2f) }
    }
}

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}
