package com.example.musicrhythmgame.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.Keep
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import com.example.musicrhythmgame.AudioSyncClock
import com.example.musicrhythmgame.NoteSpawner
import com.example.musicrhythmgame.NoteType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.core.graphics.withRotation

class RhythmGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var noteSpawner: NoteSpawner? = null
    private var audioSyncClock: AudioSyncClock? = null

    // Màu sắc cho 9 pads
    private val padColors = listOf(
        "#FF6B9D".toColorInt(), // Pink
        "#FFA06B".toColorInt(), // Orange
        "#FFD66B".toColorInt(), // Yellow
        "#6BCF7F".toColorInt(), // Green
        "#6BB5FF".toColorInt(), // Blue
        "#9D6BFF".toColorInt(), // Purple
        "#FF6B6B".toColorInt(), // Red
        "#6BFFD6".toColorInt(), // Cyan
        "#D66BFF".toColorInt()  // Magenta
    )

    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val padPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bubblePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val ripplePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 48f
        isAntiAlias = true
    }

    private val pads = mutableMapOf<Int, RectF>()
    private val padStates = mutableMapOf<Int, PadState>()
    private val hitEffects = mutableListOf<HitEffect>()
    private val missEffects = mutableListOf<MissEffect>()
    private val holdEffects = mutableMapOf<Int, HoldEffect>()

    private var gridSize = 0f
    private var padSize = 0f
    private var padSpacing = 16f

    data class PadState(
        var isPressed: Boolean = false,
        var hasActiveNote: Boolean = false,
        var isHoldNote: Boolean = false,
        var bubbleScale: Float = 0f,
        var bubblePulse: Float = 0f,
        var glowIntensity: Float = 0f
    )

    data class HitEffect(
        val padId: Int,
        val centerX: Float,
        val centerY: Float,
        var progress: Float = 0f,
        val startTime: Long = System.currentTimeMillis()
    )

    data class MissEffect(
        val padId: Int,
        val centerX: Float,
        val centerY: Float,
        var progress: Float = 0f,
        val startTime: Long = System.currentTimeMillis()
    )

    data class HoldEffect(
        var progress: Float = 0f,
        var intensity: Float = 1f,
        val particles: MutableList<Particle> = mutableListOf(),
        var isCompleted: Boolean = false,
        var isMissed: Boolean = false,
        var completionTime: Long = 0L,
        var fadeOutProgress: Float = 0f,
        var rotationAngle: Float = 0f
    )

    data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var alpha: Float = 1f,
        var size: Float,
        var angle: Float = 0f,
        var rotation: Float = 0f,
        var color: Int = Color.WHITE
    )

    private var backgroundGradientProgress: Float = 0f
    private var targetGradientColors: IntArray? = null
    private var currentGradientColors: IntArray = intArrayOf(
        "#0a0a0a".toColorInt(),
        "#0a0a0a".toColorInt(),
        "#0a0a0a".toColorInt()
    )
    private val backgroundRipples = mutableListOf<BackgroundRipple>()

    @Keep
    private data class BackgroundRipple(
        val x: Float,
        val y: Float,
        var progress: Float = 0f,
        val color: Int,
        val startTime: Long = System.currentTimeMillis()
    )

    init {
        for (i in 1..9) {
            padStates[i] = PadState()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculatePadPositions()
    }

    private fun calculatePadPositions() {
        val availableSize = min(width, height) * 0.8f
        gridSize = availableSize
        padSize = (gridSize - padSpacing * 4) / 3

        val startX = (width - gridSize) / 2
        val startY = (height - gridSize) / 2

        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val padId = row * 3 + col + 1
                val left = startX + col * (padSize + padSpacing) + padSpacing
                val top = startY + row * (padSize + padSpacing) + padSpacing
                val right = left + padSize
                val bottom = top + padSize

                pads[padId] = RectF(left, top, right, bottom)
            }
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawDynamicBackground(canvas)

        val currentTime = audioSyncClock?.getCurrentTimeSeconds() ?: 0f

        // Vẽ pads
        for (i in 1..9) {
            val rect = pads[i] ?: continue
            val state = padStates[i] ?: continue
            val baseColor = padColors[i - 1]

            // Vẽ background với màu riêng cho từng pad
            padPaint.color = baseColor
            padPaint.alpha = if (state.isPressed) 255 else 180
            canvas.drawRoundRect(rect, 20f, 20f, padPaint)

            // Vẽ glow effect khi có note
            if (state.glowIntensity > 0) {
                val glowPaint = Paint(padPaint).apply {
                    alpha = (state.glowIntensity * 100).toInt()
                    maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(rect, 20f, 20f, glowPaint)
            }

            // Vẽ border sáng hơn
            gridPaint.color = lightenColor(baseColor, 0.3f)
            canvas.drawRoundRect(rect, 20f, 20f, gridPaint)

            // Vẽ bubble note ở giữa
            if (state.bubbleScale > 0) {
                Log.d("check draw", "onDraw: $i $state")
                if (padSize == 0f) {
                    Log.e("BubbleDebug", "padSize is 0! Recalculating...")
                }

                if (state.bubbleScale > 0) {
                    Log.d("BubbleDebug", "Drawing bubble for pad $i - scale: ${state.bubbleScale}")
                } else {
                    Log.d(
                        "BubbleDebug",
                        "NOT drawing bubble for pad $i - scale: ${state.bubbleScale}"
                    )
                }
                drawBubbleNote(canvas, rect, state, baseColor)
            }
        }

        // Vẽ hold effects
        drawHoldEffects(canvas)

        // Vẽ hit effects
        drawHitEffects(canvas)

        // Vẽ miss effects
        drawMissEffects(canvas)

        // Update timing indicators
        updateTimingIndicators()

        invalidate()
    }

    private fun drawBubbleNote(canvas: Canvas, rect: RectF, state: PadState, baseColor: Int) {
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        canvas.withSave {

            if (state.isHoldNote) {
                val maxHeight = padSize * 0.6f
                val barWidth = padSize * 0.15f
                val barHeight = maxHeight * state.bubbleScale

                // Hiệu ứng pulse cho width
                val pulseScale = 1f + sin(state.bubblePulse * Math.PI * 2).toFloat() * 0.15f
                val currentWidth = barWidth * pulseScale

                // Vẽ outer glow cho bar
                bubblePaint.shader = LinearGradient(
                    centerX, centerY + maxHeight / 2, centerX, centerY - maxHeight / 2,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(
                            100,
                            Color.red(baseColor),
                            Color.green(baseColor),
                            Color.blue(baseColor)
                        ),
                        Color.argb(150, 255, 255, 255),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.3f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
                val glowRect = RectF(
                    centerX - currentWidth * 1.5f,
                    centerY + maxHeight / 2 - barHeight,
                    centerX + currentWidth * 1.5f,
                    centerY + maxHeight / 2
                )
                drawRoundRect(glowRect, currentWidth, currentWidth, bubblePaint)

                // Vẽ bar chính với gradient
                bubblePaint.shader = LinearGradient(
                    centerX, centerY + maxHeight / 2, centerX, centerY - maxHeight / 2,
                    intArrayOf(
                        baseColor,
                        lightenColor(baseColor, 0.4f),
                        Color.WHITE,
                        lightenColor(baseColor, 0.4f)
                    ),
                    floatArrayOf(0f, 0.4f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                val barRect = RectF(
                    centerX - currentWidth,
                    centerY + maxHeight / 2 - barHeight,
                    centerX + currentWidth,
                    centerY + maxHeight / 2
                )
                drawRoundRect(barRect, currentWidth / 2, currentWidth / 2, bubblePaint)

                // Vẽ highlights trên bar
                bubblePaint.shader = LinearGradient(
                    centerX - currentWidth * 0.5f, centerY,
                    centerX + currentWidth * 0.5f, centerY,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(120, 255, 255, 255),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                val highlightRect = RectF(
                    centerX - currentWidth * 0.6f,
                    centerY + maxHeight / 2 - barHeight,
                    centerX + currentWidth * 0.6f,
                    centerY + maxHeight / 2 - barHeight * 0.7f
                )
                drawRoundRect(highlightRect, currentWidth / 3, currentWidth / 3, bubblePaint)

                // Vẽ particles chạy lên trên bar
                val particleCount = 3
                for (i in 0 until particleCount) {
                    val particleProgress = (state.bubblePulse + i * 0.33f) % 1f
                    val py = centerY + maxHeight / 2 - barHeight * particleProgress
                    val particleAlpha = (255 * (1f - particleProgress)).toInt()

                    bubblePaint.shader = RadialGradient(
                        centerX, py, currentWidth * 0.8f,
                        intArrayOf(
                            Color.argb(particleAlpha, 255, 255, 255),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    drawCircle(centerX, py, currentWidth * 0.8f, bubblePaint)
                }

                // Vẽ arrow chỉ lên
                if (state.bubbleScale > 0.5f) {
                    val arrowPaint = Paint().apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                        alpha = (255 * (state.bubbleScale - 0.5f) * 2f).toInt()
                    }

                    val arrowSize = currentWidth * 1.5f
                    val arrowY = centerY - maxHeight / 2 + barHeight - arrowSize * 1.5f

                    val arrowPath = Path().apply {
                        moveTo(centerX - arrowSize, arrowY + arrowSize)
                        lineTo(centerX, arrowY)
                        lineTo(centerX + arrowSize, arrowY + arrowSize)
                    }
                    drawPath(arrowPath, arrowPaint)
                }

            } else {
                // TAP NOTE - Vẽ bubble
                val maxRadius = padSize * 0.25f
                val pulseScale = 1f + sin(state.bubblePulse * Math.PI * 2).toFloat() * 0.1f
                val radius = maxRadius * (state.bubbleScale) * pulseScale

                // Vẽ outer glow
                bubblePaint.shader = RadialGradient(
                    centerX, centerY, radius * 1.5f,
                    intArrayOf(
                        Color.argb(
                            100,
                            Color.red(baseColor),
                            Color.green(baseColor),
                            Color.blue(baseColor)
                        ),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                drawCircle(centerX, centerY, radius * 1.5f, bubblePaint)

                // Vẽ bubble chính
                bubblePaint.shader = RadialGradient(
                    centerX, centerY - radius * 0.2f, radius,
                    intArrayOf(
                        Color.WHITE,
                        lightenColor(baseColor, 0.5f),
                        baseColor
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                drawCircle(centerX, centerY, radius, bubblePaint)

                // Vẽ highlight
                bubblePaint.shader = RadialGradient(
                    centerX - radius * 0.3f, centerY - radius * 0.3f, radius * 0.5f,
                    intArrayOf(
                        Color.argb(180, 255, 255, 255),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                drawCircle(
                    centerX - radius * 0.3f,
                    centerY - radius * 0.3f,
                    radius * 0.5f,
                    bubblePaint
                )
            }
            bubblePaint.shader = null
        }
    }

    private fun drawHitEffects(canvas: Canvas) {
        val iterator = hitEffects.iterator()
        while (iterator.hasNext()) {
            val effect = iterator.next()
            val elapsed = System.currentTimeMillis() - effect.startTime
            effect.progress = (elapsed / 1000f).coerceIn(0f, 1f)

            if (effect.progress >= 1f) {
                iterator.remove()
                continue
            }

            val rect = pads[effect.padId] ?: continue
            val baseColor = padColors[effect.padId - 1]
            val alpha = (255 * (1f - effect.progress)).toInt()

            // Hiệu ứng bùng nổ từ tâm
            val maxRadius = padSize * 0.6f
            val currentRadius = maxRadius * effect.progress

            // Vẽ nhiều vòng tròn lan tỏa
            for (i in 0..2) {
                val offset = i * 0.15f
                val ringProgress = (effect.progress - offset).coerceIn(0f, 1f)
                val ringRadius = maxRadius * ringProgress
                val ringAlpha = (alpha * (1f - ringProgress)).toInt()

                ripplePaint.color = Color.argb(ringAlpha, 255, 255, 255)
                ripplePaint.strokeWidth = 6f - i * 2f
                canvas.drawCircle(effect.centerX, effect.centerY, ringRadius, ripplePaint)
            }
            val particlesPaint = Paint().apply {
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            // Vẽ particles bắn ra
            val particleCount = 12
            for (i in 0 until particleCount) {
                val angle = (Math.PI * 2 * i / particleCount).toFloat()
                val distance = currentRadius * 1.5f
                val px = effect.centerX + cos(angle) * distance
                val py = effect.centerY + sin(angle) * distance
                val particleSize = 8f * (1f - effect.progress)

                particlesPaint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawCircle(px, py, particleSize, particlesPaint)
            }

            // Vẽ fill toàn bộ pad
            val fillAlpha = (alpha * 0.7f * (1f - effect.progress)).toInt()
            padPaint.color = Color.argb(fillAlpha, 255, 255, 255)
            canvas.drawRoundRect(rect, 20f, 20f, padPaint)
        }
    }

    private fun drawMissEffects(canvas: Canvas) {
        canvas.withSave {

            val iterator = missEffects.iterator()
            while (iterator.hasNext()) {
                val effect = iterator.next()
                val elapsed = System.currentTimeMillis() - effect.startTime
                effect.progress = (elapsed / 1000f).coerceIn(0f, 1f)

                if (effect.progress >= 1f) {
                    iterator.remove()
                    continue
                }

                val rect = pads[effect.padId] ?: continue
                val baseColor = padColors[effect.padId - 1]

                val bubbleY = effect.centerY + (padSize * 0.5f * effect.progress)
                val scaleY = 1f - effect.progress * 0.8f  // Xẹp dần
                val scaleX = 1f + effect.progress * 0.2f  // Dãn ngang

                val alpha = (255 * (1f - effect.progress)).toInt()
                val radius = padSize * 0.25f

                bubblePaint.shader = RadialGradient(
                    effect.centerX, bubbleY,
                    radius,
                    intArrayOf(
                        Color.argb(alpha / 2, 150, 150, 150),
                        Color.argb(alpha, baseColor.red, baseColor.green, baseColor.blue),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )

                val path = Path().apply {
                    addOval(
                        effect.centerX - radius * scaleX,
                        bubbleY - radius * scaleY,
                        effect.centerX + radius * scaleX,
                        bubbleY + radius * scaleY,
                        Path.Direction.CW
                    )
                }
                canvas.drawPath(path, bubblePaint)
                val fragmentPaint = Paint().apply {
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                val fragmentCount = 6
                for (i in 0 until fragmentCount) {
                    val angle = (Math.PI * 2 * i / fragmentCount).toFloat()
                    val distance = effect.progress * padSize * 0.6f
                    val fx = effect.centerX + cos(angle) * distance
                    val fy =
                        effect.centerY + sin(angle) * distance + effect.progress * padSize * 0.3f // Rơi xuống

                    val fragmentAlpha =
                        (alpha * (1f - effect.progress * 1.2f).coerceIn(0f, 1f)).toInt()
                    val fragmentSize = 6f * (1f - effect.progress)

                    canvas.withRotation(effect.progress * 360f * (i % 2 * 2 - 1), fx, fy) {
                        fragmentPaint.shader = null
                        fragmentPaint.color = Color.argb(fragmentAlpha, 150, 150, 150)
                        drawRect(
                            fx - fragmentSize,
                            fy - fragmentSize,
                            fx + fragmentSize,
                            fy + fragmentSize,
                            fragmentPaint
                        )
                    }
                }
                if (effect.progress < 0.5f) {
                    val shockProgress = effect.progress * 2f
                    val shockAlpha = (150 * (1f - shockProgress)).toInt()

                    ripplePaint.color = Color.argb(shockAlpha, 200, 200, 200)
                    ripplePaint.strokeWidth = 3f

                    val shockRadius = padSize * 0.5f * shockProgress
                    canvas.drawArc(
                        effect.centerX - shockRadius,
                        effect.centerY - shockRadius * 0.3f,
                        effect.centerX + shockRadius,
                        effect.centerY + shockRadius * 0.3f,
                        180f, 180f, false, ripplePaint
                    )
                }
                val darkAlpha = (100 * (1f - effect.progress)).toInt()
                padPaint.color = Color.argb(darkAlpha, 0, 0, 0)
                drawRoundRect(rect, 20f, 20f, padPaint)

            }
        }
    }

    private val TIME_HOLD_EFFECTT = 800f

    private fun drawHoldEffects(canvas: Canvas) {
        val iterator = holdEffects.iterator()
        val bbPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val padId = entry.key
            val effect = entry.value
            val rect = pads[padId] ?: continue
            val baseColor = padColors[padId - 1]
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val radius = padSize / 2 - 20f

            if (effect.isMissed) {
                val missRadius = radius
                val alpha = 255
                bbPaint.shader = RadialGradient(
                    centerX, centerY, missRadius,
                    intArrayOf(Color.RED, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(centerX, centerY, missRadius, bbPaint)

                effect.particles.forEach { particle ->
                    particle.x += particle.vx * 4f
                    particle.y += particle.vy * 4f
                    particle.alpha *= 0.85f
                    bbPaint.shader = RadialGradient(
                        particle.x, particle.y, particle.size * 2f,
                        intArrayOf(
                            Color.argb((particle.alpha * 200).toInt(), 255, 0, 0),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(particle.x, particle.y, particle.size * 2f, bbPaint)
                }

                iterator.remove()
                continue
            }

            if (effect.isCompleted) {
                val elapsed = System.currentTimeMillis() - effect.completionTime
                effect.fadeOutProgress = (elapsed / TIME_HOLD_EFFECTT).coerceIn(0f, 1f)

                if (effect.fadeOutProgress >= 1f) {
                    iterator.remove()
                    continue
                }

                // Hiệu ứng burst khi hoàn thành
                drawCompletionBurst(
                    canvas,
                    centerX,
                    centerY,
                    radius,
                    baseColor,
                    effect.fadeOutProgress
                )

                // Animate particles bay ra
                effect.particles.forEach { particle ->
                    particle.x += particle.vx * 3f
                    particle.y += particle.vy * 3f
                    particle.alpha *= 0.92f
                    particle.rotation += 15f
                    particle.size *= 1.02f

                    if (particle.alpha > 0.05f) {
                        canvas.save()
                        canvas.rotate(particle.rotation, particle.x, particle.y)

                        // Vẽ glow
                        bbPaint.shader = RadialGradient(
                            particle.x, particle.y, particle.size * 2f,
                            intArrayOf(
                                Color.argb((particle.alpha * 200).toInt(), 255, 255, 255),
                                Color.argb(
                                    (particle.alpha * 100).toInt(),
                                    Color.red(baseColor),
                                    Color.green(baseColor),
                                    Color.blue(baseColor)
                                ),
                                Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 0.5f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawCircle(particle.x, particle.y, particle.size * 2f, bbPaint)

                        // Vẽ star shape
                        bbPaint.shader = null
                        bbPaint.color =
                            Color.argb((particle.alpha * 255).toInt(), 255, 255, 255)
                        drawStar(canvas, particle.x, particle.y, particle.size, bbPaint)

                        canvas.restore()
                    }
                }

                effect.particles.removeAll { it.alpha <= 0.05f }
                continue
            }

            // Hiệu ứng đang hold
            effect.rotationAngle += 2f

            // Vẽ outer glow ring
            val glowPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 12f
                isAntiAlias = true
                maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
            }
            glowPaint.color = Color.argb(150, 255, 255, 255)
            canvas.drawCircle(centerX, centerY, radius, glowPaint)

            // Vẽ progress ring với gradient xoay
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 8f
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
                shader = SweepGradient(
                    centerX, centerY,
                    intArrayOf(
                        Color.RED,
                        Color.YELLOW,
                        Color.GREEN,
                        Color.CYAN,
                        Color.MAGENTA
                    ),
                    floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                )
            }

            canvas.save()
            canvas.rotate(effect.rotationAngle, centerX, centerY)

            val sweepAngle = 360f * effect.progress
            canvas.drawArc(
                centerX - radius, centerY - radius,
                centerX + radius, centerY + radius,
                -90f, sweepAngle, false, paint
            )
            canvas.restore()

            // Vẽ energy trails - particles chạy theo đường tròn
            val trailCount = 8
            for (i in 0 until trailCount) {
                val angle =
                    (effect.rotationAngle + i * 360f / trailCount + effect.progress * 360f) * Math.PI / 180f
                val trailRadius =
                    radius + sin(System.currentTimeMillis() / 200.0 + i).toFloat() * 10f
                val tx = centerX + cos(angle).toFloat() * trailRadius
                val ty = centerY + sin(angle).toFloat() * trailRadius
                val trailAlpha = (200 * (1f - i.toFloat() / trailCount)).toInt()

                bbPaint.shader = RadialGradient(
                    tx, ty, 8f,
                    intArrayOf(
                        Color.argb(trailAlpha, 255, 255, 255),
                        Color.argb(
                            trailAlpha / 2,
                            Color.red(baseColor),
                            Color.green(baseColor),
                            Color.blue(baseColor)
                        ),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(tx, ty, 8f, bbPaint)
            }

            // Vẽ spiral particles từ trong ra ngoài
            effect.particles.forEach { particle ->
                // Di chuyển theo spiral
                particle.angle += 0.1f
                val spiralRadius =
                    radius * 0.5f + (particle.angle % (Math.PI * 2).toFloat()) / (Math.PI * 2).toFloat() * radius * 0.5f
                particle.x = centerX + cos(particle.angle.toDouble()).toFloat() * spiralRadius
                particle.y = centerY + sin(particle.angle.toDouble()).toFloat() * spiralRadius
                particle.alpha *= 0.97f
                particle.rotation += 8f

                if (particle.alpha > 0.1f) {
                    canvas.save()
                    canvas.rotate(particle.rotation, particle.x, particle.y)

                    // Vẽ glow
                    bbPaint.shader = RadialGradient(
                        particle.x, particle.y, particle.size * 3f,
                        intArrayOf(
                            Color.argb((particle.alpha * 180).toInt(), 255, 255, 255),
                            Color.argb(
                                (particle.alpha * 100).toInt(),
                                Color.red(particle.color),
                                Color.green(particle.color),
                                Color.blue(particle.color)
                            ),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(particle.x, particle.y, particle.size * 3f, bbPaint)

                    // Vẽ core
                    bbPaint.shader = null
                    bbPaint.color = Color.argb((particle.alpha * 255).toInt(), 255, 255, 255)
                    canvas.drawCircle(particle.x, particle.y, particle.size, bbPaint)

                    canvas.restore()
                }
            }

            if (effect.particles.size < 30 && Math.random() < 0.5) {
                val startAngle = Math.random() * Math.PI * 2
                effect.particles.add(
                    Particle(
                        x = centerX,
                        y = centerY,
                        vx = 0f,
                        vy = 0f,
                        size = 3f + Math.random().toFloat() * 3f,
                        angle = startAngle.toFloat(),
                        rotation = 0f,
                        color = if (Math.random() < 0.5) Color.WHITE else baseColor
                    )
                )
            }

            effect.particles.removeAll { it.alpha <= 0.1f }
        }
    }

    private fun drawCompletionBurst(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        baseColor: Int,
        progress: Float
    ) {
        val burstRadius = radius * (1f + progress * 2f)
        val alpha = ((1f - progress) * 255).toInt()
        val bbPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        // Vẽ shockwave rings
        for (i in 0..2) {
            val offset = i * 0.15f
            val ringProgress = (progress - offset).coerceIn(0f, 1f)
            val ringRadius = burstRadius * ringProgress
            val ringAlpha = (alpha * (1f - ringProgress) * 0.8f).toInt()
            val safeRadius = ringRadius.coerceAtLeast(1f)
            ripplePaint.shader = RadialGradient(
                centerX, centerY, safeRadius,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(ringAlpha, 255, 255, 255),
                    Color.argb(
                        ringAlpha / 2,
                        Color.red(baseColor),
                        Color.green(baseColor),
                        Color.blue(baseColor)
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            ripplePaint.strokeWidth = 8f - i * 2f
            canvas.drawCircle(centerX, centerY, ringRadius, ripplePaint)
        }

        // Vẽ starburst - tia sáng
        val rayCount = 12
        for (i in 0 until rayCount) {
            val angle = (Math.PI * 2 * i / rayCount).toFloat()
            val rayLength = burstRadius * (1f - progress * 0.5f)
            val rayWidth = 8f * (1f - progress)

            val gradient = LinearGradient(
                centerX, centerY,
                centerX + cos(angle) * rayLength,
                centerY + sin(angle) * rayLength,
                intArrayOf(
                    Color.argb(alpha, 255, 255, 255),
                    Color.argb(
                        alpha / 2,
                        Color.red(baseColor),
                        Color.green(baseColor),
                        Color.blue(baseColor)
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )

            val rayPaint = Paint().apply {
                shader = gradient
                strokeWidth = rayWidth
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }

            canvas.drawLine(
                centerX,
                centerY,
                centerX + cos(angle) * rayLength,
                centerY + sin(angle) * rayLength,
                rayPaint
            )
        }

        // Vẽ center flash
        bbPaint.shader = RadialGradient(
            centerX, centerY, radius * 0.8f,
            intArrayOf(
                Color.argb((alpha * 1.2f).toInt().coerceAtMost(255), 255, 255, 255),
                Color.argb(
                    alpha,
                    Color.red(baseColor),
                    Color.green(baseColor),
                    Color.blue(baseColor)
                ),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, radius * 0.8f * (1f - progress * 0.5f), bbPaint)

        ripplePaint.shader = null
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val path = Path()
        val points = 5
        val outerRadius = size
        val innerRadius = size * 0.4f

        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = (Math.PI * i / points - Math.PI / 2).toFloat()
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun updateTimingIndicators() {
        val currentTime = audioSyncClock?.getCurrentTimeSeconds() ?: return
        val activeNotes = noteSpawner?.getActiveNotes() ?: emptyList()
        padStates.forEach { (padId, state) ->
            state.hasActiveNote = false
            state.bubbleScale = 0f
            state.glowIntensity = 0f
        }
        activeNotes.forEach { note ->

            if (note.isHit || note.isMissed) {
                if (note.type == NoteType.HOLD && holdEffects[note.padId]?.isCompleted != true) {
                    holdEffects.remove(note.padId)
                }
                return@forEach
            }

            val state = padStates[note.padId] ?: return@forEach
            val timeDiff = note.time - currentTime

            val spawnOffset = noteSpawner?.getSpawnOffset() ?: 0f
            val normalizedTime = ((spawnOffset - timeDiff) / spawnOffset).coerceIn(0f, 1f)

            state.hasActiveNote = true
            state.isHoldNote = note.type == NoteType.HOLD
            state.bubbleScale = normalizedTime
            state.bubblePulse = (currentTime * 2f) % 1f
            state.glowIntensity = normalizedTime
            Log.d(
                "BubbleDebug",
                "Pad ${note.padId}: scale=${state.bubbleScale}, pulse=${state.bubblePulse}, active=${state.hasActiveNote}"
            )

            if (note.type == NoteType.HOLD && note.isHolding) {
                val holdProgress = (currentTime - note.holdStartTime) / note.holdDuration
                holdEffects.getOrPut(note.padId) { HoldEffect() }.apply {
                    progress = holdProgress.coerceIn(0f, 1f)
                }
            }
        }
        val holdNotePads =
            activeNotes.filter { it.type == NoteType.HOLD && !it.isHit && !it.isMissed }
                .map { it.padId }
        holdEffects.keys.toList().forEach { padId ->
            if (padId !in holdNotePads && holdEffects[padId]?.isCompleted != true) {
                holdEffects.remove(padId)
            }
        }
    }

    fun triggerHitEffect(padId: Int) {
        val rect = pads[padId] ?: return
        hitEffects.add(HitEffect(padId, rect.centerX(), rect.centerY()))

        padStates[padId]?.apply {
            hasActiveNote = false
            isHoldNote = false
            bubbleScale = 0f
            glowIntensity = 0f
        }
    }

    fun triggerMissEffect(padId: Int) {
        val rect = pads[padId] ?: return
        holdEffects[padId]?.let {
            it.isMissed = true
        }
        missEffects.add(MissEffect(padId, rect.centerX(), rect.centerY()))

        padStates[padId]?.apply {
            hasActiveNote = false
            isHoldNote = false
            bubbleScale = 0f
            glowIntensity = 0f
        }
    }

    fun completeHoldNote(padId: Int) {
        holdEffects[padId]?.let { effect ->
            effect.isCompleted = true
            effect.completionTime = System.currentTimeMillis()

            // Tạo burst particles cho hiệu ứng kết thúc
            val rect = pads[padId] ?: return
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val baseColor = padColors[padId - 1]

            effect.particles.clear()

            // Tạo particles bay ra theo hình tròn
            val particleCount = 24
            for (i in 0 until particleCount) {
                val angle = (Math.PI * 2 * i / particleCount).toFloat()
                val speed = 3f + Math.random().toFloat() * 4f
                effect.particles.add(
                    Particle(
                        x = centerX,
                        y = centerY,
                        vx = cos(angle) * speed,
                        vy = sin(angle) * speed,
                        size = 6f + Math.random().toFloat() * 6f,
                        angle = angle,
                        rotation = 0f,
                        alpha = 1f,
                        color = if (i % 2 == 0) Color.WHITE else baseColor
                    )
                )
            }

            // Reset pad state
            padStates[padId]?.apply {
                isPressed = false
                hasActiveNote = false
                isHoldNote = false
                bubbleScale = 0f
                bubblePulse = 0f
                glowIntensity = 0f
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                handleTouch(event.getX(pointerIndex), event.getY(pointerIndex), pointerId, true)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                handleTouch(event.getX(pointerIndex), event.getY(pointerIndex), pointerId, false)
            }

            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    handleTouch(event.getX(i), event.getY(i), event.getPointerId(i), false)
                }
            }
        }
        return true
    }

    private val padPointers = mutableMapOf<Int, Int>()

    private fun handleTouch(x: Float, y: Float, pointerId: Int, isDown: Boolean) {
        for ((padId, rect) in pads) {
            if (rect.contains(x, y)) {
                if (isDown) {
                    padStates[padId]?.isPressed = true
                    padPointers[padId] = pointerId
                    onPadPressed?.invoke(padId)
                } else {
                    if (padPointers[padId] == pointerId) {
                        padStates[padId]?.isPressed = false
                        padPointers.remove(padId)
                        onPadReleased?.invoke(padId)
                    }
                }
                break
            }
        }
    }

    fun update() {
        val activeNotes = noteSpawner?.getActiveNotes() ?: emptyList()

        padStates.forEach { (padId, state) ->
            val hasActive = activeNotes.any { it.padId == padId && !it.isHit && !it.isMissed }
            if (!hasActive) {
                state.hasActiveNote = false
                state.isHoldNote = false
                state.bubbleScale = 0f
                state.glowIntensity = 0f
                state.bubblePulse = 0f
            }
        }

        invalidate()
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.rgb(
            (r + (255 - r) * factor).toInt().coerceIn(0, 255),
            (g + (255 - g) * factor).toInt().coerceIn(0, 255),
            (b + (255 - b) * factor).toInt().coerceIn(0, 255)
        )
    }

    fun resetPadState(padId: Int) {
        padStates[padId]?.apply {
            hasActiveNote = false
            isHoldNote = false
            bubbleScale = 0f
            bubblePulse = 0f
            glowIntensity = 0f
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun handlePerfect(x: Float, y: Float, color1: Int, color2: Int, color3: Int) {
        Log.d("PerfectEffect", "🔥 handlePerfect() called at ($x, $y)")

        val targetColors = intArrayOf(color1, color2, color3)
        backgroundRipples.add(BackgroundRipple(x, y, 0f, color2))

        // ✅ Animate từ 0 → 1 và CẬP NHẬT currentGradientColors
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 800
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            backgroundGradientProgress = progress

            // ✅ CẬP NHẬT currentGradientColors từ đen → màu target
            val darkColor = "#0a0a0a".toColorInt()
            currentGradientColors[0] = interpolateColor(darkColor, targetColors[0], progress)
            currentGradientColors[1] = interpolateColor(darkColor, targetColors[1], progress)
            currentGradientColors[2] = interpolateColor(darkColor, targetColors[2], progress)

            Log.d("PerfectEffect", "💫 Progress = ${"%.2f".format(progress)}")
            Log.d("PerfectEffect", "🌈 Current colors: [${currentGradientColors[0].toHexString()}, ${currentGradientColors[1].toHexString()}, ${currentGradientColors[2].toHexString()}]")

            invalidate()
        }
        animator.start()
        Log.d("PerfectEffect", "🚀 Animation started")
    }

    fun resetBackground() {
        targetGradientColors = null

        // Animate về màu ban đầu
        val animator = ValueAnimator.ofFloat(backgroundGradientProgress, 0f)
        animator.duration = 500
        animator.addUpdateListener { animation ->
            backgroundGradientProgress = animation.animatedValue as Float

            val darkColor = "#0a0a0a".toColorInt()
            currentGradientColors[0] = interpolateColor(currentGradientColors[0], darkColor, 1f - backgroundGradientProgress)
            currentGradientColors[1] = interpolateColor(currentGradientColors[1], darkColor, 1f - backgroundGradientProgress)
            currentGradientColors[2] = interpolateColor(currentGradientColors[2], darkColor, 1f - backgroundGradientProgress)
            invalidate()
        }
        animator.start()

        // Clear ripples
        backgroundRipples.clear()
    }

    private fun drawDynamicBackground(canvas: Canvas) {
        // ✅ LUÔN vẽ background (blend giữa đen và gradient colors)
        val gradient = LinearGradient(
            0f, 0f,
            width.toFloat(), height.toFloat(),
            currentGradientColors,
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val bgPaint = Paint().apply {
            shader = gradient
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Vẽ background ripples
        val ripplePaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val iterator = backgroundRipples.iterator()
        while (iterator.hasNext()) {
            val ripple = iterator.next()
            val elapsed = System.currentTimeMillis() - ripple.startTime
            ripple.progress = (elapsed / 1500f).coerceIn(0f, 1f)

            if (ripple.progress >= 1f) {
                iterator.remove()
                continue
            }

            val maxRadius = kotlin.math.max(width, height).toFloat() * 1.5f
            val currentRadius = maxRadius * ripple.progress
            val alpha = ((1f - ripple.progress) * 100).toInt()

            ripplePaint.shader = RadialGradient(
                ripple.x, ripple.y, currentRadius,
                intArrayOf(
                    Color.argb(alpha, Color.red(ripple.color), Color.green(ripple.color), Color.blue(ripple.color)),
                    Color.argb(alpha / 2, Color.red(ripple.color), Color.green(ripple.color), Color.blue(ripple.color)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(ripple.x, ripple.y, currentRadius, ripplePaint)
        }

        // Vẽ shimmer effect khi có gradient active
        if (backgroundGradientProgress > 0) {
            drawShimmerEffect(canvas)
        }
    }

    private fun drawShimmerEffect(canvas: Canvas) {
        val shimmerPaint = Paint().apply {
            isAntiAlias = true
        }

        val time = System.currentTimeMillis() / 1000f
        val shimmerCount = 5

        for (i in 0 until shimmerCount) {
            val offset = i * 0.2f
            val shimmerProgress = ((time + offset) % 2f) / 2f
            val shimmerX = width * shimmerProgress
            val shimmerY = height * (0.2f + i * 0.15f)

            val alpha = (backgroundGradientProgress * 60 * kotlin.math.sin(shimmerProgress * Math.PI).toFloat()).toInt()

            shimmerPaint.shader = RadialGradient(
                shimmerX, shimmerY, 150f,
                intArrayOf(
                    Color.argb(alpha, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(shimmerX, shimmerY, 150f, shimmerPaint)
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)

        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)

        val r = (startR + (endR - startR) * fraction).toInt()
        val g = (startG + (endG - startG) * fraction).toInt()
        val b = (startB + (endB - startB) * fraction).toInt()

        return Color.rgb(r, g, b)
    }

    var onPadPressed: ((Int) -> Unit)? = null
    var onPadReleased: ((Int) -> Unit)? = null

    fun setNoteSpawner(spawner: NoteSpawner) {
        this.noteSpawner = spawner
    }

    fun setAudioClock(clock: AudioSyncClock) {
        this.audioSyncClock = clock
    }
}