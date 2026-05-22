package com.example.myapplication.core

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

object ThemeAnimator {
    private var backgroundAnimator: ValueAnimator? = null
    private var animatedBackground: GradientDrawable? = null

    fun startBackgroundAnimation(context: Context, rootLayout: View) {
        val isDarkTheme =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val color1 = ContextCompat.getColor(
            context,
            if (isDarkTheme) R.color.theme2_bg_1_dark else R.color.theme2_bg_1
        )
        val color2 = ContextCompat.getColor(
            context,
            if (isDarkTheme) R.color.theme2_bg_2_dark else R.color.theme2_bg_2
        )
        val color3 = ContextCompat.getColor(
            context,
            if (isDarkTheme) R.color.theme2_bg_3_dark else R.color.theme2_bg_3
        )

        backgroundAnimator?.cancel()
        val drawable = animatedBackground ?: GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color1, color2, color3)
        ).also {
            animatedBackground = it
            rootLayout.background = it
        }
        drawable.orientation = GradientDrawable.Orientation.TL_BR
        backgroundAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE

            val evaluator = ArgbEvaluator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val c1 = evaluator.evaluate(fraction, color1, color2) as Int
                val c2 = evaluator.evaluate(fraction, color2, color3) as Int
                val c3 = evaluator.evaluate(fraction, color3, color1) as Int
                drawable.colors = intArrayOf(c1, c2, c3)
            }
            start()
        }
    }

    fun stopBackgroundAnimation() {
        backgroundAnimator?.cancel()
        backgroundAnimator = null
        animatedBackground = null
    }

    fun updateVisualizer(bars: List<View>, audioLevel: Int) {
        if (bars.size != 7) return

        val density = bars.first().context.resources.displayMetrics.density
        val safeLevel = audioLevel.coerceIn(0, 100)

        for (index in bars.indices) {
            val wave = 0.3 + (0.7 * abs(sin(index * 0.8 + safeLevel / 20.0)))
            val targetHeightDp = max(10.0, safeLevel * wave)
            val params = bars[index].layoutParams
            params.height = (targetHeightDp * density).toInt()
            bars[index].layoutParams = params
        }
    }
}
