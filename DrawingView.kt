package br.anotaquestoes

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Camada transparente por cima do WebView. Só a caneta (ou o dedo, se ativado) desenha. */
class DrawingView(ctx: Context) : View(ctx) {

    enum class Tool { PEN, HIGHLIGHTER, LINE, RECT, OVAL, TEXT, LASER, ERASER, ERASER_PART }
    private enum class Kind { PEN, HL, LINE, RECT, OVAL, TEXT, ERASE }

    private class Item(
        val kind: Kind, val color: Int, val width: Float,
        val pts: MutableList<PointF>, val text: String? = null
    ) {
        var path: Path? = null
        var layout: StaticLayout? = null
    }

    private class LaserPt(val x: Float, val y: Float, val t: Long)

    // ---- configuração (mexida pela barra) ----
    var scrollSource: View? = null          // o WebView: strokes acompanham a rolagem da página
    var drawingEnabled = true               // ✍️ ligado / 👆 desligado (toques passam direto)
    var fingerDraws = false                 // se true, o dedo também desenha
    var ruler = false                       // marcador em linha reta
    var penColor = Color.parseColor("#E53935")
    var hlColor = Color.parseColor("#FFEB3B")
    var widthDp = 3f
    var onTextRequest: ((Float, Float) -> Unit)? = null
    var tool = Tool.PEN
        private set

    fun selectTool(t: Tool) {
        tool = t
        if (t == Tool.ERASER || t == Tool.ERASER_PART) eraserTool = t
    }

    // ---- estado ----
    private val dp = ctx.resources.displayMetrics.density
    private var eraserTool = Tool.ERASER
    private var items = ArrayList<Item>()
    private val undoStack = ArrayList<List<Item>>()
    private val redoStack = ArrayList<List<Item>>()
    private var current: Item? = null
    private var activeTool = Tool.PEN
    private var rulerActive = false
    private var changed = false
    private var downPx = 0f
    private var downPy = 0f
    private var downSx = 0f
    private var downSy = 0f
    private val lasers = ArrayList<MutableList<LaserPt>>()
    private var laserCur: MutableList<LaserPt>? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 18f * dp
    }

    private val sx get() = (scrollSource?.scrollX ?: 0).toFloat()
    private val sy get() = (scrollSource?.scrollY ?: 0).toFloat()
    private val eraserR get() = (8f + widthDp * 1.5f) * dp

    // ---------------- histórico ----------------
    private fun pushUndo() {
        undoStack.add(ArrayList(items))
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun commit(i: Item) { pushUndo(); items.add(i); invalidate() }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(ArrayList(items))
        items = ArrayList(undoStack.removeAt(undoStack.lastIndex))
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(ArrayList(items))
        items = ArrayList(redoStack.removeAt(redoStack.lastIndex))
        invalidate()
    }

    fun clearAll() {
        if (items.isEmpty()) return
        pushUndo(); items = ArrayList(); invalidate()
    }

    fun addText(px: Float, py: Float, text: String) {
        if (text.isBlank()) return
        commit(Item(Kind.TEXT, Color.BLACK, 0f, mutableListOf(PointF(px, py)), text))
    }

    // ---------------- toque ----------------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!drawingEnabled) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val tt = e.getToolType(0)
            val stylus = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
            if (!stylus && !fingerDraws) return false      // dedo passa para a página (rolar/clicar)
            val eraserPen = tt == MotionEvent.TOOL_TYPE_ERASER ||
                (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            activeTool = if (eraserPen) eraserTool else tool
            rulerActive = ruler
            changed = false
            downSx = e.x; downSy = e.y
            downPx = e.x + sx; downPy = e.y + sy
            begin(downPx, downPy, e.x, e.y)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize)
                    move(e.getHistoricalX(h) + sx, e.getHistoricalY(h) + sy, e.getHistoricalX(h), e.getHistoricalY(h))
                move(e.x + sx, e.y + sy, e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                move(e.x + sx, e.y + sy, e.x, e.y)
                end(false, e.x, e.y)
            }
            MotionEvent.ACTION_CANCEL -> end(true, e.x, e.y)
        }
        return true
    }

    private fun begin(px: Float, py: Float, scrX: Float, scrY: Float) {
        when (activeTool) {
            Tool.PEN -> current = Item(Kind.PEN, penColor, widthDp * dp, mutableListOf(PointF(px, py)))
            Tool.HIGHLIGHTER -> current = Item(Kind.HL, hlColor, max(widthDp * 4f, 14f) * dp, mutableListOf(PointF(px, py)))
            Tool.LINE -> current = Item(Kind.LINE, penColor, widthDp * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.RECT -> current = Item(Kind.RECT, penColor, widthDp * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.OVAL -> current = Item(Kind.OVAL, penColor, widthDp * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.TEXT -> {}
            Tool.LASER -> {
                val s = mutableListOf(LaserPt(scrX, scrY, SystemClock.uptimeMillis()))
                lasers.add(s); laserCur = s
            }
            Tool.ERASER -> eraseAt(px, py)
            Tool.ERASER_PART -> current = Item(Kind.ERASE, 0, eraserR * 2f, mutableListOf(PointF(px, py)))
        }
        invalidate()
    }

    private fun move(px: Float, py: Float, scrX: Float, scrY: Float) {
        val c = current
        when (activeTool) {
            Tool.PEN, Tool.ERASER_PART -> if (c != null) {
                val l = c.pts.last()
                if (hypot(px - l.x, py - l.y) > 0.6f * dp) { c.pts.add(PointF(px, py)); c.path = null }
            }
            Tool.HIGHLIGHTER -> if (c != null) {
                if (rulerActive) {
                    if (c.pts.size < 2) c.pts.add(PointF(px, py)) else c.pts[1].set(px, py)
                    c.path = null
                } else {
                    val l = c.pts.last()
                    if (hypot(px - l.x, py - l.y) > 0.6f * dp) { c.pts.add(PointF(px, py)); c.path = null }
                }
            }
            Tool.LINE, Tool.RECT, Tool.OVAL -> if (c != null) { c.pts[1].set(px, py); c.path = null }
            Tool.ERASER -> eraseAt(px, py)
            Tool.LASER -> laserCur?.let { s ->
                if (!lasers.contains(s)) lasers.add(s)
                s.add(LaserPt(scrX, scrY, SystemClock.uptimeMillis()))
            }
            Tool.TEXT -> {}
        }
        invalidate()
    }

    private fun end(cancelled: Boolean, scrX: Float, scrY: Float) {
        val c = current
        if (c != null && !cancelled) {
            val keep = when (c.kind) {
                Kind.LINE, Kind.RECT, Kind.OVAL -> hypot(c.pts[1].x - c.pts[0].x, c.pts[1].y - c.pts[0].y) > 3f * dp
                else -> true
            }
            if (keep) commit(c)
        }
        current = null
        laserCur = null
        if (activeTool == Tool.TEXT && !cancelled && hypot(scrX - downSx, scrY - downSy) < 12f * dp)
            onTextRequest?.invoke(downPx, downPy)
        invalidate()
    }

    private fun eraseAt(px: Float, py: Float) {
        val r = eraserR
        val del = items.filter { hit(it, px, py, r) }
        if (del.isNotEmpty()) {
            if (!changed) { pushUndo(); changed = true }
            items.removeAll(del.toSet())
            invalidate()
        }
    }

    // ---------------- geometria ----------------
    private fun segDist(x: Float, y: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    private fun polyHit(p: List<PointF>, x: Float, y: Float, tol: Float): Boolean {
        if (p.size == 1) return hypot(x - p[0].x, y - p[0].y) <= tol
        for (k in 1 until p.size) if (segDist(x, y, p[k - 1], p[k]) <= tol) return true
        return false
    }

    private fun hit(i: Item, x: Float, y: Float, r: Float): Boolean {
        val tol = r + i.width / 2f
        return when (i.kind) {
            Kind.ERASE -> false
            Kind.TEXT -> RectF(boundsOf(i)).apply { inset(-r, -r) }.contains(x, y)
            Kind.PEN, Kind.HL -> polyHit(i.pts, x, y, tol)
            Kind.LINE -> polyHit(i.pts, x, y, tol)
            Kind.RECT -> {
                val a = i.pts[0]; val b = i.pts[1]
                polyHit(listOf(a, PointF(b.x, a.y), b, PointF(a.x, b.y), a), x, y, tol)
            }
            Kind.OVAL -> {
                val a = i.pts[0]; val b = i.pts[1]
                val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2
                val rx = abs(b.x - a.x) / 2; val ry = abs(b.y - a.y) / 2
                val poly = (0..48).map { k ->
                    val t = (k / 48.0 * 2 * PI).toFloat()
                    PointF(cx + rx * cos(t), cy + ry * sin(t))
                }
                polyHit(poly, x, y, tol)
            }
        }
    }

    private fun layoutOf(i: Item): StaticLayout {
        i.layout?.let { return it }
        val t = i.text ?: ""
        val w = min(ceil(Layout.getDesiredWidth(t, textPaint)).toInt() + 2, (240 * dp).toInt()).coerceAtLeast(10)
        val l = StaticLayout.Builder.obtain(t, 0, t.length, textPaint, w).build()
        i.layout = l
        return l
    }

    private fun boundsOf(i: Item): RectF {
        val l = layoutOf(i); val pad = 8 * dp
        val p = i.pts[0]
        return RectF(p.x, p.y, p.x + l.width + 2 * pad, p.y + l.height + 2 * pad)
    }

    private fun pathOf(i: Item): Path {
        i.path?.let { return it }
        val p = Path()
        when (i.kind) {
            Kind.PEN, Kind.HL, Kind.ERASE -> {
                val pts = i.pts
                p.moveTo(pts[0].x, pts[0].y)
                if (pts.size == 1) p.lineTo(pts[0].x + 0.01f, pts[0].y)
                else {
                    for (k in 1 until pts.size) {
                        val a = pts[k - 1]; val b = pts[k]
                        p.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
                    }
                    p.lineTo(pts.last().x, pts.last().y)
                }
            }
            Kind.LINE -> { p.moveTo(i.pts[0].x, i.pts[0].y); p.lineTo(i.pts[1].x, i.pts[1].y) }
            Kind.RECT -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.addRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), Path.Direction.CW)
            }
            Kind.OVAL -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.addOval(RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)), Path.Direction.CW)
            }
            Kind.TEXT -> {}
        }
        i.path = p
        return p
    }

    // ---------------- desenho ----------------
    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.translate(-sx, -sy)
        for (i in items) drawItem(canvas, i)
        current?.let { drawItem(canvas, it) }
        canvas.restoreToCount(layer)
        drawLasers(canvas)
    }

    private fun drawItem(c: Canvas, i: Item) {
        if (i.kind == Kind.TEXT) { drawNote(c, i); return }
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = i.width
        when (i.kind) {
            Kind.HL -> { paint.color = i.color; paint.alpha = 100; paint.strokeCap = Paint.Cap.SQUARE }
            Kind.ERASE -> paint.xfermode = clearMode
            else -> paint.color = i.color
        }
        c.drawPath(pathOf(i), paint)
    }

    private fun drawNote(c: Canvas, i: Item) {
        val l = layoutOf(i); val pad = 8 * dp
        val b = boundsOf(i)
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FFF59D")
        c.drawRoundRect(b, 8 * dp, 8 * dp, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1 * dp
        paint.color = Color.parseColor("#FBC02D")
        c.drawRoundRect(b, 8 * dp, 8 * dp, paint)
        c.save(); c.translate(b.left + pad, b.top + pad); l.draw(c); c.restore()
    }

    private fun drawLasers(c: Canvas) {
        if (lasers.isEmpty()) return
        val life = 1500L
        val now = SystemClock.uptimeMillis()
        lasers.forEach { s -> s.removeAll { p -> now - p.t > life } }
        lasers.removeAll { it.isEmpty() && it !== laserCur }
        for (s in lasers) {
            for (pass in 0..1) {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (if (pass == 0) 14f else 5f) * dp
                if (s.size == 1) {
                    val a = alphaFor(now - s[0].t, life, pass)
                    paint.color = Color.argb(a, 255, 23, 68)
                    c.drawPoint(s[0].x, s[0].y, paint)
                }
                for (k in 1 until s.size) {
                    val a = alphaFor(now - s[k].t, life, pass)
                    paint.color = Color.argb(a, 255, 23, 68)
                    c.drawLine(s[k - 1].x, s[k - 1].y, s[k].x, s[k].y, paint)
                }
            }
        }
        if (lasers.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun alphaFor(age: Long, life: Long, pass: Int): Int {
        val f = (1f - age.toFloat() / life).coerceIn(0f, 1f)
        return (f * (if (pass == 0) 70 else 255)).toInt()
    }
}
