package com.example.musicrhythmgame.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.example.musicrhythmgame.MainViewModel
import kotlin.math.min

data class PadSound(
    val padId: Int,
    var soundId: Int? = null,
    var soundName: String? = null,
    var color: Int = Color.parseColor("#2A2A2A")
)

data class RecordedNote(
    val padId: Int,
    val timestamp: Long
)

data class PadAnimation(
    var scale: Float = 1f,
    var rippleProgress: Float = 0f,
    var glowAlpha: Int = 0,
    var isAnimating: Boolean = false
)

class DrumPadsView @JvmOverloads constructor(
    val viewModel: MainViewModel,
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rows = 4
    private val cols = 3
    private val totalPads = rows * cols

    private val soundPool: SoundPool
    private val pads = mutableListOf<PadSound>()
    private val activePads = mutableSetOf<Int>()
    private val padAnimations = mutableMapOf<Int, PadAnimation>()

    // Recording
    private var isRecording = false
    private val recordedNotes = mutableListOf<RecordedNote>()
    private var recordStartTime = 0L

    // Paint objects
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 40f
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Bright colors for assigned pads
    private val brightColors = listOf(
        Color.parseColor("#FF6B6B"), // Đỏ sáng
        Color.parseColor("#4ECDC4"), // Xanh ngọc
        Color.parseColor("#FFE66D"), // Vàng
        Color.parseColor("#95E1D3"), // Xanh mint
        Color.parseColor("#F38181"), // Hồng
        Color.parseColor("#AA96DA"), // Tím
        Color.parseColor("#FCBAD3"), // Hồng pastel
        Color.parseColor("#A8E6CF"), // Xanh lá nhạt
        Color.parseColor("#FFD3B6"), // Cam nhạt
        Color.parseColor("#FFAAA5"), // Đào
        Color.parseColor("#FF8B94"), // Hồng đậm
        Color.parseColor("#A8D8EA")  // Xanh dương nhạt
    )

    private var padWidth = 0f
    private var padHeight = 0f
    private var padding = 16f

    var onPadClick: ((Int) -> Unit)? = null
    var onEditPad: ((Int) -> Unit)? = null

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(audioAttributes)
            .build()

        // Initialize pads
        for (i in 0 until totalPads) {
            pads.add(PadSound(padId = i))
            padAnimations[i] = PadAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculatePadSizes()
    }

    private fun calculatePadSizes() {
        val availableWidth = width - padding * (cols + 1)
        val availableHeight = height - padding * (rows + 1)

        padWidth = availableWidth / cols
        padHeight = availableHeight / rows
        if (padWidth < padHeight) {
            padHeight = padWidth
        } else padWidth = padHeight
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPad(canvas)
    }

    fun drawPad(canvas: Canvas) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val index = row * cols + col
                val pad = pads[index]
                val anim = padAnimations[index] ?: PadAnimation()

                val left = padding + col * (padWidth + padding)
                val top = padding + row * (padHeight + padding)
                val right = left + padWidth
                val bottom = top + padHeight

                val centerX = (left + right) / 2
                val centerY = (top + bottom) / 2

                // Animation scale với bounce effect
                val scale = anim.scale
                val scaledWidth = padWidth * scale
                val scaledHeight = padHeight * scale
                val scaledLeft = centerX - scaledWidth / 2
                val scaledTop = centerY - scaledHeight / 2
                val scaledRight = centerX + scaledWidth / 2
                val scaledBottom = centerY + scaledHeight / 2

                // Draw shadow khi active
                if (anim.glowAlpha > 0) {
                    shadowPaint.color = pad.color
                    shadowPaint.alpha = (anim.glowAlpha * 0.3f).toInt()
                    canvas.drawRoundRect(
                        scaledLeft - 8, scaledTop - 8,
                        scaledRight + 8, scaledBottom + 8,
                        24f, 24f, shadowPaint
                    )
                }

                // Draw pad background với gradient
                if (pad.soundId != null) {
                    val gradient = RadialGradient(
                        centerX, centerY,
                        padWidth / 2,
                        intArrayOf(
                            lightenColor(pad.color, 1.3f),
                            pad.color,
                            darkenColor(pad.color, 0.8f)
                        ),
                        floatArrayOf(0f, 0.6f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    padPaint.shader = gradient
                } else {
                    padPaint.shader = null
                    padPaint.color = pad.color
                }

                padPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    scaledLeft, scaledTop, scaledRight, scaledBottom,
                    20f, 20f, padPaint
                )

                padPaint.shader = null

                // Draw border
                padPaint.color = Color.WHITE
                padPaint.style = Paint.Style.STROKE
                padPaint.strokeWidth = if (anim.glowAlpha > 0) 4f else 3f
                padPaint.alpha = 255
                canvas.drawRoundRect(
                    scaledLeft, scaledTop, scaledRight, scaledBottom,
                    20f, 20f, padPaint
                )

                // Draw ripple effect
                if (anim.rippleProgress > 0) {
                    val rippleRadius = (padWidth / 2) * anim.rippleProgress
                    ripplePaint.color = Color.WHITE
                    ripplePaint.alpha = ((1 - anim.rippleProgress) * 200).toInt()
                    ripplePaint.strokeWidth = 6f * (1 - anim.rippleProgress)
                    canvas.drawCircle(centerX, centerY, rippleRadius, ripplePaint)
                }

                // Draw glow overlay
                if (anim.glowAlpha > 0) {
                    glowPaint.color = Color.WHITE
                    glowPaint.alpha = anim.glowAlpha
                    glowPaint.style = Paint.Style.FILL
                    canvas.drawRoundRect(
                        scaledLeft, scaledTop, scaledRight, scaledBottom,
                        20f, 20f, glowPaint
                    )
                }

                // Draw text với shadow
                textPaint.setShadowLayer(4f, 0f, 2f, Color.BLACK)
                val text = pad.soundName ?: (index + 1).toString()
                val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(text, centerX, textY, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                handleTouch(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val padIndex = getPadAtPosition(x, y)
                if (padIndex != -1) {
                    activePads.remove(padIndex)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                handleTouch(event)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouch(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        val padIndex = getPadAtPosition(x, y)
        if (padIndex != -1 && !activePads.contains(padIndex)) {
            playPad(padIndex)
        }
    }

    private fun getPadAtPosition(x: Float, y: Float): Int {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = padding + col * (padWidth + padding)
                val top = padding + row * (padHeight + padding)
                val right = left + padWidth
                val bottom = top + padHeight

                if (x in left..right && y in top..bottom) {
                    return row * cols + col
                }
            }
        }
        return -1
    }

    private fun playPad(padIndex: Int) {
        val pad = pads[padIndex]
        pad.soundId?.let { soundId ->
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
            activePads.add(padIndex)

            // Enhanced Animation
            animatePadEnhanced(padIndex)

            // Record
            if (isRecording) {
                recordedNotes.add(
                    RecordedNote(
                        padIndex,
                        System.currentTimeMillis() - recordStartTime
                    )
                )
            }

            onPadClick?.invoke(padIndex)
            invalidate()
        }
    }

    private fun animatePadEnhanced(padIndex: Int) {
        val anim = padAnimations[padIndex] ?: PadAnimation()
        anim.isAnimating = true

        // Scale animation với bounce effect
        ValueAnimator.ofFloat(1f, 0.85f, 1.1f, 1f).apply {
            duration = 400
            interpolator = OvershootInterpolator(2f)
            addUpdateListener { animator ->
                anim.scale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Ripple animation
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener { animator ->
                anim.rippleProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Glow animation
        ValueAnimator.ofInt(0, 120, 0).apply {
            duration = 300
            addUpdateListener { animator ->
                anim.glowAlpha = animator.animatedValue as Int
                invalidate()
            }
            start()
        }

        // Cleanup
        postDelayed({
            anim.isAnimating = false
            anim.rippleProgress = 0f
            anim.glowAlpha = 0
            activePads.remove(padIndex)
            invalidate()
        }, 500)
    }

    // Helper functions for color manipulation
    private fun lightenColor(color: Int, factor: Float): Int {
        val r = ((Color.red(color) * factor).toInt()).coerceAtMost(255)
        val g = ((Color.green(color) * factor).toInt()).coerceAtMost(255)
        val b = ((Color.blue(color) * factor).toInt()).coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r, g, b)
    }

    // Public API
    fun assignSound(padId: Int, rawResId: Int, soundName: String) {
        soundPool.setOnLoadCompleteListener { sp, id, status ->
            Log.e("LoadStatus", "soundID=$id status=$status")
        }

        if (padId in 0 until totalPads) {
            val soundId = soundPool.load(context, rawResId, 1)
            pads[padId].soundId = soundId
            pads[padId].soundName = soundName
            pads[padId].color = brightColors[padId % brightColors.size]
            invalidate()
        }
    }

    fun assignSoundFromPath(padId: Int, filePath: String, soundName: String) {
        if (padId in 0 until totalPads) {
            val soundId = soundPool.load(filePath, 1)
            pads[padId].soundId = soundId
            pads[padId].soundName = soundName
            pads[padId].color = brightColors[padId % brightColors.size]
            invalidate()
        }
    }

    fun removeSound(padId: Int) {
        if (padId in 0 until totalPads) {
            pads[padId].soundId?.let { soundPool.unload(it) }
            pads[padId].soundId = null
            pads[padId].soundName = null
            pads[padId].color = Color.parseColor("#2A2A2A")
            invalidate()
        }
    }

    fun startRecording() {
        isRecording = true
        recordedNotes.clear()
        recordStartTime = System.currentTimeMillis()
    }

    fun stopRecording(): List<RecordedNote> {
        isRecording = false
        return recordedNotes.toList()
    }

    fun playRecording(notes: List<RecordedNote>) {
        notes.forEach { note ->
            postDelayed({
                playPad(note.padId)
            }, note.timestamp)
        }
    }

    fun editPad(padId: Int) {
        onEditPad?.invoke(padId)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        soundPool.release()
    }
}

// MainActivity.kt - Example usage
/*
class MainActivity : AppCompatActivity() {
    private lateinit var drumPadsView: DrumPadsView
    private var recordedSequence: List<RecordedNote>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Drum pads view
        drumPadsView = DrumPadsView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            // Assign sounds (you need to add these sound files to res/raw/)
            assignSound(0, R.raw.kick, "Kick")
            assignSound(1, R.raw.snare, "Snare")
            assignSound(2, R.raw.hihat, "HiHat")
            assignSound(3, R.raw.clap, "Clap")
            // ... assign more sounds
        }

        // Control buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val recordButton = Button(this).apply {
            text = "Record"
            setOnClickListener {
                drumPadsView.startRecording()
                text = "Recording..."
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                recordedSequence = drumPadsView.stopRecording()
                recordButton.text = "Record"
            }
        }

        val playButton = Button(this).apply {
            text = "Play"
            setOnClickListener {
                recordedSequence?.let { drumPadsView.playRecording(it) }
            }
        }

        buttonLayout.addView(recordButton)
        buttonLayout.addView(stopButton)
        buttonLayout.addView(playButton)

        layout.addView(drumPadsView)
        layout.addView(buttonLayout)

        setContentView(layout)
    }
}
*/