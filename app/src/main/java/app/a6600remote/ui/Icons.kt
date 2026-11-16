package app.a6600remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Простые линейные иконки, нарисованные кодом (без лишних библиотек). */
enum class Glyph { Gear, Close, Mirror, Timer, Album, Grid }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.085f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val c = center
        when (glyph) {
            Glyph.Gear -> {
                val r = w / 2f
                for (i in 0 until 8) {
                    rotate(i * 45f, c) {
                        drawRoundRect(
                            color,
                            topLeft = Offset(c.x - r * 0.15f, c.y - r * 0.96f),
                            size = Size(r * 0.30f, r * 0.42f),
                            cornerRadius = CornerRadius(r * 0.07f),
                        )
                    }
                }
                drawCircle(color, radius = r * 0.56f, center = c, style = Stroke(width = r * 0.30f))
            }

            Glyph.Close -> {
                val a = w * 0.22f
                drawLine(color, Offset(a, a), Offset(w - a, w - a), sw, StrokeCap.Round)
                drawLine(color, Offset(w - a, a), Offset(a, w - a), sw, StrokeCap.Round)
            }

            Glyph.Mirror -> {
                // ось симметрии пунктиром и два треугольника
                drawLine(
                    color, Offset(c.x, w * 0.08f), Offset(c.x, w * 0.92f), sw * 0.8f,
                    StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(w * 0.08f, w * 0.08f)),
                )
                val left = Path().apply {
                    moveTo(c.x - w * 0.12f, w * 0.25f)
                    lineTo(c.x - w * 0.12f, w * 0.75f)
                    lineTo(w * 0.06f, w * 0.75f)
                    close()
                }
                drawPath(left, color)
                val right = Path().apply {
                    moveTo(c.x + w * 0.12f, w * 0.25f)
                    lineTo(c.x + w * 0.12f, w * 0.75f)
                    lineTo(w * 0.94f, w * 0.75f)
                    close()
                }
                drawPath(right, color, style = stroke)
            }

            Glyph.Timer -> {
                val cc = Offset(c.x, c.y + w * 0.06f)
                val r = w * 0.38f
                drawCircle(color, radius = r, center = cc, style = stroke)
                drawLine(color, cc, Offset(cc.x, cc.y - r * 0.6f), sw, StrokeCap.Round)
                drawLine(color, cc, Offset(cc.x + r * 0.45f, cc.y + r * 0.2f), sw, StrokeCap.Round)
                drawLine(color, Offset(c.x - w * 0.12f, w * 0.06f), Offset(c.x + w * 0.12f, w * 0.06f), sw, StrokeCap.Round)
            }

            Glyph.Grid -> {
                val a = w * 0.12f
                val b = w - a
                drawRoundRect(
                    color, topLeft = Offset(a, a), size = Size(b - a, b - a),
                    cornerRadius = CornerRadius(w * 0.08f), style = stroke,
                )
                val t1 = a + (b - a) / 3f
                val t2 = a + 2f * (b - a) / 3f
                drawLine(color, Offset(t1, a), Offset(t1, b), sw * 0.7f)
                drawLine(color, Offset(t2, a), Offset(t2, b), sw * 0.7f)
                drawLine(color, Offset(a, t1), Offset(b, t1), sw * 0.7f)
                drawLine(color, Offset(a, t2), Offset(b, t2), sw * 0.7f)
            }

            Glyph.Album -> {
                // две «фотографии» стопкой и горы на передней
                drawRoundRect(
                    color, topLeft = Offset(w * 0.24f, w * 0.10f), size = Size(w * 0.66f, w * 0.56f),
                    cornerRadius = CornerRadius(w * 0.08f), style = stroke,
                )
                drawRoundRect(
                    color, topLeft = Offset(w * 0.10f, w * 0.30f), size = Size(w * 0.66f, w * 0.58f),
                    cornerRadius = CornerRadius(w * 0.08f), style = stroke,
                )
                val m = Path().apply {
                    moveTo(w * 0.18f, w * 0.80f)
                    lineTo(w * 0.36f, w * 0.56f)
                    lineTo(w * 0.48f, w * 0.70f)
                    lineTo(w * 0.56f, w * 0.62f)
                    lineTo(w * 0.70f, w * 0.80f)
                    close()
                }
                drawPath(m, color)
            }
        }
    }
}
