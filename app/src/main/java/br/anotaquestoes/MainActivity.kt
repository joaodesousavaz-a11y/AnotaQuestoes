package br.anotaquestoes

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var draw: DrawingView
    private lateinit var toggleBtn: TextView
    private val toolButtons = LinkedHashMap<DrawingView.Tool, TextView>()
    private val colorDots = ArrayList<Pair<View, Int>>()
    private var lastUrl: String? = null
    private var autoClear = false
    private val density by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * density).toInt()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val root = FrameLayout(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (!isReload && url != lastUrl) {
                        if (autoClear && lastUrl != null) draw.clearAll()
                        lastUrl = url
                    }
                }
            }
        }
        draw = DrawingView(this).apply {
            scrollSource = web
            onTextRequest = { x, y -> askText(x, y) }
        }
        web.setOnScrollChangeListener { _, _, _, _, _ -> draw.invalidate() }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(web, FrameLayout.LayoutParams(match, match))
        root.addView(draw, FrameLayout.LayoutParams(match, match))
        root.addView(buildBar(root), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = px(12); topMargin = px(12) })
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        web.loadUrl("https://www.tecconcursos.com.br")
        selectTool(DrawingView.Tool.PEN)
    }

    // ------------------------------------------------------------------ barra
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(r).toFloat()
    }

    private fun label(t: String, size: Float = 20f, minW: Int = 46) = TextView(this).apply {
        text = t; textSize = size; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        minWidth = px(minW)
        setPadding(px(6), 0, px(6), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(46))
    }

    private fun mark(v: View, on: Boolean, color: String = "#3D5AFE") {
        v.background = if (on) rounded(Color.parseColor(color), 12) else null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBar(root: FrameLayout): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#EB212121"), 28)
            elevation = px(8).toFloat()
            setPadding(px(6), px(4), px(6), px(4))
        }

        // alça para arrastar a barra
        val handle = label("⠿", 24f, 34)
        var offX = 0f; var offY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { offX = bar.x - e.rawX; offY = bar.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    bar.x = (e.rawX + offX).coerceIn(0f, max(0f, (root.width - bar.width).toFloat()))
                    bar.y = (e.rawY + offY).coerceIn(0f, max(0f, (root.height - bar.height).toFloat()))
                }
            }
            true
        }

        // liga/desliga escrita
        toggleBtn = label("", 16f, 100)
        refreshToggle()
        toggleBtn.setOnClickListener { draw.drawingEnabled = !draw.drawingEnabled; refreshToggle() }

        // recolher / expandir
        val collapse = label("◀", 16f, 32)

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(row)

        // ferramentas
        val tools = listOf(
            Triple("✏️", DrawingView.Tool.PEN, "Lápis"),
            Triple("🖍️", DrawingView.Tool.HIGHLIGHTER, "Marcador"),
            Triple("╱", DrawingView.Tool.LINE, "Linha"),
            Triple("▢", DrawingView.Tool.RECT, "Retângulo"),
            Triple("◯", DrawingView.Tool.OVAL, "Círculo"),
            Triple("🗒️", DrawingView.Tool.TEXT, "Texto (post-it)"),
            Triple("🔴", DrawingView.Tool.LASER, "Laser"),
            Triple("🧽", DrawingView.Tool.ERASER, "Borracha: traço inteiro"),
            Triple("⌫", DrawingView.Tool.ERASER_PART, "Borracha: só onde passar")
        )
        for ((icon, tool, tip) in tools) {
            val b = label(icon)
            b.tooltipText = tip
            b.setOnClickListener { selectTool(tool) }
            toolButtons[tool] = b
            row.addView(b)
        }
        row.addView(sep())

        // desfazer / refazer / limpar
        row.addView(label("↩️").apply { tooltipText = "Desfazer"; setOnClickListener { draw.undo() } })
        row.addView(label("↪️").apply { tooltipText = "Refazer"; setOnClickListener { draw.redo() } })
        row.addView(label("🗑️").apply { tooltipText = "Limpar tudo"; setOnClickListener { draw.clearAll() } })
        row.addView(sep())

        // cores
        for (hex in listOf("#111111", "#E53935", "#1E88E5", "#43A047", "#FFEB3B", "#FF9800")) {
            val color = Color.parseColor(hex)
            val dot = View(this)
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(px(40), px(46))
                addView(dot, FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER))
                setOnClickListener {
                    if (draw.tool == DrawingView.Tool.HIGHLIGHTER) draw.hlColor = color else draw.penColor = color
                    refreshColors()
                }
            }
            colorDots.add(dot to color)
            row.addView(frame)
        }

        // espessura
        row.addView(TextView(this).apply { text = "━"; setTextColor(Color.WHITE); setPadding(px(8), 0, 0, 0) })
        row.addView(SeekBar(this).apply {
            max = 29; progress = 2
            layoutParams = LinearLayout.LayoutParams(px(140), px(46))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { draw.widthDp = (p + 1).toFloat() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        row.addView(TextView(this).apply { text = "▇"; setTextColor(Color.WHITE); setPadding(0, 0, px(8), 0) })
        row.addView(sep())

        // opções
        row.addView(optionToggle("📏", "Marcador em linha reta (régua)") { draw.ruler = it })
        row.addView(optionToggle("☝️", "Dedo também desenha") { draw.fingerDraws = it })
        row.addView(optionToggle("🔄", "Limpar ao trocar de página") { autoClear = it })

        bar.addView(handle)
        bar.addView(toggleBtn)
        bar.addView(collapse)
        bar.addView(scroll)

        collapse.setOnClickListener {
            val open = scroll.visibility != View.VISIBLE
            scroll.visibility = if (open) View.VISIBLE else View.GONE
            collapse.text = if (open) "◀" else "▶"
        }
        return bar
    }

    private fun sep() = View(this).apply {
        setBackgroundColor(Color.parseColor("#55FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(px(1), px(28)).apply { setMargins(px(6), 0, px(6), 0) }
    }

    private fun optionToggle(icon: String, tip: String, onChange: (Boolean) -> Unit): TextView {
        val b = label(icon)
        var on = false
        b.tooltipText = tip
        b.setOnClickListener { on = !on; mark(b, on, "#00897B"); onChange(on) }
        return b
    }

    private fun selectTool(t: DrawingView.Tool) {
        draw.selectTool(t)
        draw.drawingEnabled = true
        refreshToggle()
        toolButtons.forEach { (k, v) -> mark(v, k == t) }
        refreshColors()
    }

    private fun refreshToggle() {
        if (draw.drawingEnabled) {
            toggleBtn.text = "✍️ Escrever"
            toggleBtn.background = rounded(Color.parseColor("#2E7D32"), 14)
        } else {
            toggleBtn.text = "👆 Navegar"
            toggleBtn.background = rounded(Color.parseColor("#616161"), 14)
        }
    }

    private fun refreshColors() {
        val cur = if (draw.tool == DrawingView.Tool.HIGHLIGHTER) draw.hlColor else draw.penColor
        for ((v, c) in colorDots) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (c == cur) setStroke(px(3), Color.WHITE) else setStroke(px(1), Color.parseColor("#88FFFFFF"))
            }
        }
    }

    private fun askText(x: Float, y: Float) {
        val et = EditText(this).apply { hint = "Anotação"; minLines = 2; setPadding(px(20), px(12), px(20), px(12)) }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Post-it")
            .setView(et)
            .setPositiveButton("OK") { _, _ -> draw.addText(x, y, et.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        et.requestFocus()
    }
}
