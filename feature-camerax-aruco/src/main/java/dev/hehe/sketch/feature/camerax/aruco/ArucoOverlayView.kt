package dev.hehe.sketch.feature.camerax.aruco

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class ArucoOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val idBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC111111")
        style = Paint.Style.FILL
    }

    private val idTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
    }

    private var result: ArucoDetectionResult? = null

    fun submitResult(result: ArucoDetectionResult) {
        this.result = result
        invalidate()
    }

    fun clear() {
        result = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val detection = result ?: return
        if (detection.sourceWidth <= 0 || detection.sourceHeight <= 0) {
            return
        }

        val scale = max(
            width / detection.sourceWidth.toFloat(),
            height / detection.sourceHeight.toFloat()
        )
        val horizontalOffset = (width - detection.sourceWidth * scale) / 2f
        val verticalOffset = (height - detection.sourceHeight * scale) / 2f

        detection.markers.forEach { marker ->
            val points = marker.corners.map { point ->
                PointF(
                    point.x * scale + horizontalOffset,
                    point.y * scale + verticalOffset
                )
            }
            drawMarker(canvas, marker.id, points)
        }
    }

    private fun drawMarker(canvas: Canvas, id: Int, points: List<PointF>) {
        if (points.size < 4) {
            return
        }

        for (index in points.indices) {
            val start = points[index]
            val end = points[(index + 1) % points.size]
            canvas.drawLine(start.x, start.y, end.x, end.y, borderPaint)
        }

        val anchor = points.first()
        val label = "ID $id"
        val textWidth = idTextPaint.measureText(label)
        val textPaddingHorizontal = 18f
        val textPaddingVertical = 12f
        val badgeLeft = anchor.x
        val badgeTop = anchor.y - idTextPaint.textSize - textPaddingVertical * 2
        val badgeRight = anchor.x + textWidth + textPaddingHorizontal * 2
        val badgeBottom = anchor.y

        canvas.drawRoundRect(
            badgeLeft,
            badgeTop,
            badgeRight,
            badgeBottom,
            14f,
            14f,
            idBadgePaint
        )
        canvas.drawText(
            label,
            badgeLeft + textPaddingHorizontal,
            badgeBottom - textPaddingVertical,
            idTextPaint
        )
    }
}
