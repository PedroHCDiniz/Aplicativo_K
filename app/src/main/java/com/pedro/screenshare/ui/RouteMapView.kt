package com.pedro.screenshare.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.pedro.screenshare.signaling.RoutePoint

class RouteMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val points = mutableListOf<RoutePoint>()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 247, 250)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 214, 220)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(27, 94, 32)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 118, 210)
        style = Paint.Style.FILL
    }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(211, 47, 47)
        style = Paint.Style.FILL
    }

    fun setRoutePoints(newPoints: List<RoutePoint>) {
        points.clear()
        points.addAll(newPoints)
        invalidate()
    }

    fun addRoutePoint(point: RoutePoint) {
        points.add(point)
        if (points.size > 2000) {
            points.removeAt(0)
        }
        invalidate()
    }

    fun clearRoute() {
        points.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthF = width.toFloat()
        val heightF = height.toFloat()
        canvas.drawRoundRect(0f, 0f, widthF, heightF, 8f, 8f, backgroundPaint)
        canvas.drawRoundRect(1f, 1f, widthF - 1f, heightF - 1f, 8f, 8f, borderPaint)

        if (points.isEmpty()) return

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }
        val latRange = (maxLat - minLat).takeIf { it > 0.000001 } ?: 0.000001
        val lngRange = (maxLng - minLng).takeIf { it > 0.000001 } ?: 0.000001
        val padding = 18f
        val drawableWidth = widthF - padding * 2
        val drawableHeight = heightF - padding * 2

        fun x(point: RoutePoint): Float {
            return padding + (((point.longitude - minLng) / lngRange).toFloat() * drawableWidth)
        }

        fun y(point: RoutePoint): Float {
            return padding + ((1f - ((point.latitude - minLat) / latRange).toFloat()) * drawableHeight)
        }

        if (points.size == 1) {
            canvas.drawCircle(x(points.first()), y(points.first()), 8f, endPaint)
            return
        }

        val path = Path()
        points.forEachIndexed { index, point ->
            val pointX = x(point)
            val pointY = y(point)
            if (index == 0) {
                path.moveTo(pointX, pointY)
            } else {
                path.lineTo(pointX, pointY)
            }
        }
        canvas.drawPath(path, routePaint)
        canvas.drawCircle(x(points.first()), y(points.first()), 7f, startPaint)
        canvas.drawCircle(x(points.last()), y(points.last()), 8f, endPaint)
    }
}
