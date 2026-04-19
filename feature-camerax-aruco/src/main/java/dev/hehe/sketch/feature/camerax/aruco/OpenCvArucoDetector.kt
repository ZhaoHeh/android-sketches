package dev.hehe.sketch.feature.camerax.aruco

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

class OpenCvArucoDetector {

    private val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
    private val detectorParameters = DetectorParameters()
    private val detector = ArucoDetector(dictionary, detectorParameters)

    fun detect(imageProxy: ImageProxy): ArucoDetectionResult {
        val rgbaMat = imageProxy.toRgbaMat()
        val orientedRgbaMat = rgbaMat.rotateToUpright(imageProxy.imageInfo.rotationDegrees)
        val grayMat = Mat()
        val corners = ArrayList<Mat>()
        val ids = Mat()

        return try {
            Imgproc.cvtColor(orientedRgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            detector.detectMarkers(grayMat, corners, ids)

            val markers = if (ids.empty()) {
                emptyList()
            } else {
                corners.mapIndexed { index, cornerMat ->
                    val id = ids.get(index, 0)[0].toInt()
                    DetectedArucoMarker(
                        id = id,
                        corners = cornerMat.toPointList()
                    )
                }.sortedBy { it.id }
            }

            ArucoDetectionResult(
                markers = markers,
                sourceWidth = orientedRgbaMat.cols(),
                sourceHeight = orientedRgbaMat.rows(),
                processingTimeMs = 0L
            )
        } finally {
            corners.forEach { it.release() }
            ids.release()
            grayMat.release()
            orientedRgbaMat.release()
        }
    }

    private fun ImageProxy.toRgbaMat(): Mat {
        val plane = planes.first()
        val width = width
        val height = height
        val packedBytes = ByteArray(width * height * 4)
        val rowBuffer = ByteArray(plane.rowStride)
        val sourceBuffer = plane.buffer.apply { rewind() }
        var destinationOffset = 0

        for (row in 0 until height) {
            sourceBuffer.get(rowBuffer, 0, plane.rowStride)
            if (plane.pixelStride == 4) {
                System.arraycopy(
                    rowBuffer,
                    0,
                    packedBytes,
                    destinationOffset,
                    width * 4
                )
            } else {
                for (column in 0 until width) {
                    val sourceOffset = column * plane.pixelStride
                    val targetOffset = destinationOffset + column * 4
                    for (channel in 0 until 4) {
                        packedBytes[targetOffset + channel] = rowBuffer[sourceOffset + channel]
                    }
                }
            }
            destinationOffset += width * 4
        }

        return Mat(height, width, CvType.CV_8UC4).apply {
            put(0, 0, packedBytes)
        }
    }

    private fun Mat.rotateToUpright(rotationDegrees: Int): Mat {
        if (rotationDegrees == 0) {
            return this
        }

        val rotated = Mat()
        when (rotationDegrees) {
            90 -> Core.rotate(this, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(this, rotated, Core.ROTATE_180)
            270 -> Core.rotate(this, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return this
        }
        release()
        return rotated
    }

    private fun Mat.toPointList(): List<PointF> {
        val values = FloatArray(8)
        get(0, 0, values)
        return listOf(
            Point(values[0].toDouble(), values[1].toDouble()),
            Point(values[2].toDouble(), values[3].toDouble()),
            Point(values[4].toDouble(), values[5].toDouble()),
            Point(values[6].toDouble(), values[7].toDouble())
        ).map { point -> PointF(point.x.toFloat(), point.y.toFloat()) }
    }
}
