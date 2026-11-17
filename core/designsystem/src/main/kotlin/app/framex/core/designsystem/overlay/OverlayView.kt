package app.framex.core.designsystem.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.framex.core.common.OverlayStyle
import app.framex.core.designsystem.R

/** What the overlay shows. Colors are only a visual hint; the numbers are whatever the caller measured or estimated. */
data class OverlayContent(
    val fps: String,
    val fpsUnit: String,
    val fpsColor: Int,
    val temp: String,
    val tempColor: Int,
)

object OverlayColors {
    const val GOOD = 0xFF7FF0CF.toInt()
    const val WARN = 0xFFFFC857.toInt()
    const val BAD = 0xFFFF6B6B.toInt()
    const val NEUTRAL = 0xFFB0BEC5.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()

    fun forFps(fps: Int?): Int = when {
        fps == null -> NEUTRAL
        fps >= 50 -> GOOD
        fps >= 25 -> WARN
        else -> BAD
    }

    /** Same thresholds as FrameX's temperature advice: 40 °C warm, 45 °C hot. */
    fun forTemp(c: Float?): Int = when {
        c == null -> NEUTRAL
        c >= 45f -> BAD
        c >= 40f -> WARN
        else -> TEXT
    }
}

/** Polygon given in 0..1 coordinates of its bounds, with a vertical gradient fill and an outline. */
private class PolyDrawable(
    private val pts: List<Pair<Float, Float>>, private val top: Int, private val bottom: Int,
    private val strokeColor: Int, private val strokePx: Float,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun onBoundsChange(b: Rect) {
        path.reset()
        pts.forEachIndexed { i, (x, y) ->
            val px = b.left + x * b.width(); val py = b.top + y * b.height()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        c.drawPath(path, fill)
        if (strokePx > 0f) { line.color = strokeColor; line.strokeWidth = strokePx; c.drawPath(path, line) }
    }

    override fun setAlpha(a: Int) { fill.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { fill.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Sword: dark handle with three pink diamonds, pink guard, silver blade with an angled tip. */
private class KatanaDrawable(private val d: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blade = Path()
    private val handleW = 34f * d
    private val guardW = 7f * d

    override fun draw(c: Canvas) {
        val b = bounds; val h = b.height().toFloat(); val w = b.width().toFloat()
        val l = b.left.toFloat(); val t = b.top.toFloat()
        // handle
        p.shader = null; p.style = Paint.Style.FILL; p.color = 0xFF3A3340.toInt()
        c.drawRoundRect(RectF(l, t + h * 0.30f, l + handleW, t + h * 0.70f), 3 * d, 3 * d, p)
        p.color = 0xFFFF9EC4.toInt()
        for (i in 0..2) {
            val cx = l + handleW * (0.2f + 0.3f * i); val cy = t + h * 0.5f; val r = h * 0.09f
            val dm = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
            c.drawPath(dm, p)
        }
        // guard
        p.color = 0xFFFFB3D1.toInt()
        c.drawRoundRect(RectF(l + handleW, t + h * 0.04f, l + handleW + guardW, t + h * 0.96f), guardW / 2, guardW / 2, p)
        // blade
        val x0 = l + handleW + guardW; val bw = w - handleW - guardW
        blade.reset()
        blade.moveTo(x0, t + h * 0.14f); blade.lineTo(x0 + bw * 0.90f, t + h * 0.14f)
        blade.lineTo(l + w, t + h * 0.38f); blade.lineTo(l + w - bw * 0.03f, t + h * 0.86f); blade.lineTo(x0, t + h * 0.86f); blade.close()
        p.shader = LinearGradient(0f, t, 0f, t + h, 0xFFFFFFFF.toInt(), 0xFFD5DAE6.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(blade, p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFFFB3D1.toInt()
        c.drawLine(x0, t + h * 0.86f, l + w - bw * 0.03f, t + h * 0.86f, p)
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class Spec(
    val background: (Float) -> Drawable,
    val padL: Int, val padT: Int, val padR: Int, val padB: Int,
    val textColor: Int, val unitColor: Int,
    /** null = color by FPS level; otherwise this accent is always used for the FPS number and icon. */
    val fixedFps: Int?,
    val statusDot: Boolean = false,
    val horizontal: Boolean = false,
    val textSize: Float = 16f,
)

private fun specFor(style: OverlayStyle): Spec = when (style) {
    OverlayStyle.Stealth -> Spec(
        { d -> GradientDrawable().apply { setColor(0xF0101418.toInt()); cornerRadius = 12 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0xFF2A3038.toInt()) } },
        12, 8, 14, 8, 0xFFFFFFFF.toInt(), 0xFF8A94A0.toInt(), null)
    OverlayStyle.Minimal -> Spec(
        { d -> GradientDrawable().apply { setColor(0xF012161B.toInt()); cornerRadius = 100 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0xFF262C34.toInt()) } },
        14, 7, 16, 7, 0xFFFFFFFF.toInt(), 0xFF8A94A0.toInt(), null, statusDot = true)
    OverlayStyle.Vengeance -> Spec(
        { d -> PolyDrawable(listOf(0f to 0.20f, 0.12f to 0f, 0.30f to 0.14f, 0.50f to 0.04f, 0.70f to 0.14f, 0.88f to 0f, 1f to 0.20f,
            0.94f to 0.62f, 0.78f to 0.80f, 0.50f to 1f, 0.22f to 0.80f, 0.06f to 0.62f), 0xFF2B2E34.toInt(), 0xFF141619.toInt(), 0xFF3A3F47.toInt(), 1 * d) },
        26, 14, 26, 16, 0xFFE6EAF0.toInt(), 0xFF7C8590.toInt(), 0xFF2EC8F0.toInt())
    OverlayStyle.Katana -> Spec(
        { d -> KatanaDrawable(d) },
        34 + 7 + 10, 9, 18, 9, 0xFF23232B.toInt(), 0xFF6B6B78.toInt(), 0xFF23232B.toInt())
    OverlayStyle.Raven -> Spec(
        { d -> PolyDrawable(listOf(0f to 0.35f, 0.10f to 0.10f, 0.22f to 0.28f, 0.36f to 0.06f, 0.50f to 0.22f, 0.64f to 0.06f, 0.78f to 0.28f, 0.90f to 0.10f, 1f to 0.35f,
            0.85f to 0.70f, 0.70f to 0.62f, 0.50f to 0.92f, 0.30f to 0.62f, 0.15f to 0.70f), 0xFF3A1D22.toInt(), 0xFF21100F.toInt(), 0xFF7A2A30.toInt(), 1 * d) },
        26, 14, 26, 18, 0xFFFFD9DA.toInt(), 0xFF9A7B7E.toInt(), 0xFFFF5A5F.toInt())
    OverlayStyle.Micro -> Spec(
        { d -> GradientDrawable().apply { setColor(0xE0101418.toInt()); cornerRadius = 100 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0xFF262C34.toInt()) } },
        10, 5, 12, 5, 0xFFFFFFFF.toInt(), 0xFF8A94A0.toInt(), null, statusDot = true, horizontal = true, textSize = 13f)
    OverlayStyle.Neon -> Spec(
        { d -> GradientDrawable().apply { setColor(0xF00A0A14.toInt()); cornerRadius = 12 * d; setStroke((2 * d).toInt().coerceAtLeast(2), 0xFF00E5FF.toInt()) } },
        12, 8, 14, 8, 0xFFFF4FD8.toInt(), 0xFF7A7A99.toInt(), 0xFF00E5FF.toInt())
    OverlayStyle.Retro -> Spec(
        { d -> GradientDrawable().apply { setColor(0xF00B1A0B.toInt()); cornerRadius = 2 * d; setStroke((2 * d).toInt().coerceAtLeast(2), 0xFF1F7F1F.toInt()) } },
        10, 6, 12, 6, 0xFF7CFF7C.toInt(), 0xFF3FA03F.toInt(), 0xFF7CFF7C.toInt())
    OverlayStyle.Glass -> Spec(
        { d -> GradientDrawable().apply { setColor(0x80FFFFFF.toInt()); cornerRadius = 16 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0x99FFFFFF.toInt()) } },
        12, 8, 14, 8, 0xFF101418.toInt(), 0xFF3A424C.toInt(), 0xFF101418.toInt())
    OverlayStyle.Sunset -> Spec(
        { d -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFF7A18.toInt(), 0xFFFF2E93.toInt())).apply { cornerRadius = 100 * d } },
        16, 8, 18, 8, 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFFFFFFF.toInt())
    OverlayStyle.Carbon -> Spec(
        { d -> PolyDrawable(listOf(0.06f to 0f, 1f to 0f, 0.94f to 1f, 0f to 1f), 0xFF2A2A2E.toInt(), 0xFF111113.toInt(), 0xFFE10600.toInt(), 2 * d) },
        20, 9, 20, 9, 0xFFF2F2F2.toInt(), 0xFF8A8A92.toInt(), 0xFFFF3B30.toInt())
    OverlayStyle.Frost -> Spec(
        { d -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF60A5FA.toInt(), 0xFF22D3EE.toInt())).apply { cornerRadius = 14 * d } },
        14, 8, 16, 8, 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFFFFFFF.toInt())
    OverlayStyle.Aurora -> Spec(
        { d -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7C3AED.toInt(), 0xFF2563EB.toInt())).apply { cornerRadius = 100 * d } },
        16, 8, 18, 8, 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFFFFFFF.toInt())
}

/**
 * The overlay box: FPS row and temperature row, in one of the [OverlayStyle] looks.
 * Pure display; it never measures anything. Used by the live overlay and by the Settings previews.
 */
class OverlayView(context: Context, style: OverlayStyle) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val spec = specFor(style)
    private val fpsIcon = ImageView(context).apply { setImageResource(R.drawable.ic_overlay_fps); layoutParams = LayoutParams((18 * d).toInt(), (18 * d).toInt()) }
    private val dot = ImageView(context).apply {
        setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OverlayColors.GOOD) })
        layoutParams = LayoutParams((9 * d).toInt(), (9 * d).toInt()).apply { marginEnd = (3 * d).toInt() }
    }
    private val fpsValue = value()
    private val fpsUnit = TextView(context).apply { textSize = 10f; setPadding((4 * d).toInt(), (4 * d).toInt(), 0, 0); setTextColor(spec.unitColor) }
    private val tempIcon = ImageView(context).apply { setImageResource(R.drawable.ic_overlay_temp); layoutParams = LayoutParams((18 * d).toInt(), (18 * d).toInt()) }
    private val tempValue = value()

    private fun value() = TextView(context).apply {
        setTextColor(spec.textColor); textSize = spec.textSize; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setPadding((6 * d).toInt(), 0, 0, 0)
    }

    init {
        orientation = if (spec.horizontal) HORIZONTAL else VERTICAL
        background = spec.background(d)
        setPadding((spec.padL * d).toInt(), (spec.padT * d).toInt(), (spec.padR * d).toInt(), (spec.padB * d).toInt())
        val fpsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(if (spec.statusDot) dot else fpsIcon); addView(fpsValue); addView(fpsUnit)
        }
        val tempRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(tempIcon); addView(tempValue) }
        addView(fpsRow, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(tempRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (spec.horizontal) marginStart = (10 * d).toInt() else topMargin = (3 * d).toInt()
        })
    }

    fun setContent(c: OverlayContent) {
        val fpsColor = spec.fixedFps ?: c.fpsColor
        fpsValue.text = c.fps; fpsValue.setTextColor(fpsColor)
        fpsIcon.setColorFilter(fpsColor)
        (dot.drawable as GradientDrawable).setColor(c.fpsColor) // the status dot always shows the level
        fpsUnit.text = c.fpsUnit
        // warm/hot always shows its warning color; otherwise the style's own text color
        val tempColor = if (c.tempColor == OverlayColors.TEXT || c.tempColor == OverlayColors.NEUTRAL) spec.textColor else c.tempColor
        tempValue.text = c.temp; tempValue.setTextColor(tempColor); tempIcon.setColorFilter(tempColor)
    }
}
