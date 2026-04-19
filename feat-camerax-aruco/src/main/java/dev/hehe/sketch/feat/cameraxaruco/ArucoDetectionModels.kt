package dev.hehe.sketch.feat.cameraxaruco

import android.graphics.PointF

data class DetectedArucoMarker(
    val id: Int,
    val corners: List<PointF>
)

data class ArucoDetectionResult(
    val markers: List<DetectedArucoMarker>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val processingTimeMs: Long
) {
    val markerIds: List<Int> = markers.map { it.id }.sorted()
}
