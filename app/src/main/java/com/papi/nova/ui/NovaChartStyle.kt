package com.papi.nova.ui

import android.content.Context
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.BarDataSet
import com.papi.nova.R

/**
 * Applies Nova color palette to MPAndroidChart instances.
 * Call these methods after setting data to apply consistent theming.
 */
object NovaChartStyle {

    fun applyLineChartTheme(context: Context, chart: LineChart) {
        val ice = context.getColor(R.color.nova_ice)
        val muted = context.getColor(R.color.nova_text_muted)
        val bg = context.getColor(R.color.nova_bg_elevated)

        chart.setBackgroundColor(bg)
        chart.description.isEnabled = false
        chart.legend.textColor = ice
        chart.setDrawGridBackground(false)
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(false)

        chart.xAxis.apply {
            textColor = muted
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
        }

        chart.axisLeft.apply {
            textColor = muted
            setDrawGridLines(true)
            gridColor = context.getColor(R.color.nova_divider)
        }

        chart.axisRight.isEnabled = false
    }

    fun applyBarChartTheme(context: Context, chart: BarChart) {
        val ice = context.getColor(R.color.nova_ice)
        val muted = context.getColor(R.color.nova_text_muted)
        val bg = context.getColor(R.color.nova_bg_elevated)

        chart.setBackgroundColor(bg)
        chart.description.isEnabled = false
        chart.legend.textColor = ice
        chart.setDrawGridBackground(false)
        chart.setFitBars(true)

        chart.xAxis.apply {
            textColor = muted
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
        }

        chart.axisLeft.apply {
            textColor = muted
            setDrawGridLines(true)
            gridColor = context.getColor(R.color.nova_divider)
        }

        chart.axisRight.isEnabled = false
    }

    fun styleLineDataSet(context: Context, dataSet: LineDataSet, type: DataSetType = DataSetType.PRIMARY) {
        val color = when (type) {
            DataSetType.PRIMARY -> context.getColor(R.color.nova_accent)
            DataSetType.SUCCESS -> context.getColor(R.color.nova_success)
            DataSetType.WARNING -> context.getColor(R.color.nova_warning)
            DataSetType.ERROR -> context.getColor(R.color.nova_error)
        }

        dataSet.color = color
        dataSet.setCircleColor(color)
        dataSet.lineWidth = 2f
        dataSet.circleRadius = 3f
        dataSet.setDrawCircleHole(false)
        dataSet.valueTextColor = context.getColor(R.color.nova_text_muted)
        dataSet.valueTextSize = 9f
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = color
        dataSet.fillAlpha = 30
    }

    fun styleBarDataSet(context: Context, dataSet: BarDataSet, type: DataSetType = DataSetType.PRIMARY) {
        val color = when (type) {
            DataSetType.PRIMARY -> context.getColor(R.color.nova_accent)
            DataSetType.SUCCESS -> context.getColor(R.color.nova_success)
            DataSetType.WARNING -> context.getColor(R.color.nova_warning)
            DataSetType.ERROR -> context.getColor(R.color.nova_error)
        }

        dataSet.color = color
        dataSet.valueTextColor = context.getColor(R.color.nova_text_muted)
        dataSet.valueTextSize = 9f
    }

    enum class DataSetType {
        PRIMARY, SUCCESS, WARNING, ERROR
    }
}
