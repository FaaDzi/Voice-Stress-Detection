package com.example.myapplication.ui

import android.graphics.Color
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.BarGraphSeries
import com.jjoe64.graphview.series.DataPoint

object PredictionChartStyler {
    private const val BAR_COLOR = "#0F766E"
    private const val BAR_SPACING = 30

    fun configure(chart: GraphView) {
        chart.gridLabelRenderer.apply {
            horizontalAxisTitle = ""
            verticalAxisTitle = ""
            isHorizontalLabelsVisible = false
            verticalLabelsColor = Color.BLACK
            gridStyle = GridLabelRenderer.GridStyle.HORIZONTAL
            isHighlightZeroLines = false
        }

        chart.viewport.apply {
            isXAxisBoundsManual = true
            setMinX(-0.5)
            setMaxX(4.5)
            isYAxisBoundsManual = true
            setMinY(0.0)
            setMaxY(100.0)
        }
    }

    fun render(chart: GraphView, percentages: List<Double>) {
        val clamped = percentages.map { it.coerceIn(0.0, 100.0) }
        val points = arrayOf(
            DataPoint(0.0, clamped.getOrElse(0) { 0.0 }),
            DataPoint(1.0, clamped.getOrElse(1) { 0.0 }),
            DataPoint(2.0, clamped.getOrElse(2) { 0.0 }),
            DataPoint(3.0, clamped.getOrElse(3) { 0.0 }),
            DataPoint(4.0, clamped.getOrElse(4) { 0.0 }),
        )

        val series = BarGraphSeries(points).apply {
            spacing = BAR_SPACING
            isAnimated = false
            color = Color.parseColor(BAR_COLOR)
        }

        chart.removeAllSeries()
        chart.addSeries(series)
        chart.invalidate()
    }
}
