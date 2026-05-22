package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sweepFraction = 0f
    private var theme = 1               // 1 or 2
    private var isScanning = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sweepAnimator: ValueAnimator? = null

    fun startScanning(themeNumber: Int) {
        theme = themeNumber
        isScanning = true
        visibility = VISIBLE
        sweepAnimator?.cancel()
        val duration = if (themeNumber == 2) 4000L else 3000L
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = if (themeNumber == 2) ValueAnimator.REVERSE else ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                sweepFraction = anim.animatedFraction
                invalidate()
            }
        }
        sweepAnimator?.start()
    }

    fun stopScanning() {
        isScanning = false
        sweepAnimator?.cancel()
        sweepAnimator = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!isScanning || width == 0 || height == 0) return
        val x = sweepFraction * width
        when (theme) {
            2 -> drawTheme2(canvas, x)
            else -> drawTheme1(canvas, x)
        }
    }

    private fun drawTheme1(canvas: Canvas, x: Float) {
        val trailWidth = 48f * resources.displayMetrics.density
        val trailStart = (x - trailWidth).coerceAtLeast(0f)
        val trailShader = android.graphics.LinearGradient(
            trailStart, 0f, x, 0f,
            intArrayOf(android.graphics.Color.TRANSPARENT, 0x4D2563EB.toInt()),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.style = android.graphics.Paint.Style.FILL
        paint.shader = trailShader
        paint.strokeWidth = 0f
        canvas.drawRect(trailStart, 0f, x, height.toFloat(), paint)

        paint.shader = null
        paint.style = android.graphics.Paint.Style.STROKE
        paint.color = 0xFF2563EB.toInt()
        paint.strokeWidth = 2f * resources.displayMetrics.density
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
    }

    private fun drawTheme2(canvas: Canvas, x: Float) {
        val glowRadius = 36f * resources.displayMetrics.density
        val glowShader = android.graphics.LinearGradient(
            (x - glowRadius).coerceAtLeast(0f), 0f,
            (x + glowRadius).coerceAtMost(width.toFloat()), 0f,
            intArrayOf(
                android.graphics.Color.TRANSPARENT,
                0x5500CED1.toInt(),
                0xAAFFFFFF.toInt(),
                0x5500CED1.toInt(),
                android.graphics.Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.style = android.graphics.Paint.Style.FILL
        paint.shader = glowShader
        canvas.drawRect(
            (x - glowRadius).coerceAtLeast(0f), 0f,
            (x + glowRadius).coerceAtMost(width.toFloat()), height.toFloat(),
            paint
        )
        paint.shader = null
    }
}
