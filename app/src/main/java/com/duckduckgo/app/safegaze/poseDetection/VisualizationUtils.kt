/* Copyright 2021 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

package com.duckduckgo.app.safegaze.poseDetection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.duckduckgo.common.utils.SAFE_GAZE_MIN_FACE_SIZE
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object VisualizationUtils {
    /** Radius of circle used to draw keypoints.  */
    private const val CIRCLE_RADIUS = 6f

    /** Width of line used to connected two keypoints.  */
    private const val LINE_WIDTH = 4f

    /** The text size of the person id that will be displayed when the tracker is available.  */
    private const val PERSON_ID_TEXT_SIZE = 30f

    /** Distance from person id to the nose keypoint.  */
    private const val PERSON_ID_MARGIN = 6f

    /** Pair of keypoints to draw lines between.  */
    private val bodyJoints = listOf(
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        Pair(BodyPart.NOSE, BodyPart.LEFT_SHOULDER),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )

    // Draw line and point indicate body pose
    fun drawBodyKeypoints(
        input: Bitmap,
        persons: List<Person>,
        isTrackerEnabled: Boolean = false
    ): Bitmap {
        val paintCircle = Paint().apply {
            strokeWidth = CIRCLE_RADIUS
            color = Color.RED
            style = Paint.Style.FILL
        }
        val paintLine = Paint().apply {
            strokeWidth = LINE_WIDTH
            color = Color.RED
            style = Paint.Style.STROKE
        }

        val paintText = Paint().apply {
            textSize = PERSON_ID_TEXT_SIZE
            color = Color.BLUE
            textAlign = Paint.Align.LEFT
        }

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val originalSizeCanvas = Canvas(output)
        persons.forEach { person ->
            // draw person id if tracker is enable
            if (isTrackerEnabled) {
                person.poseBox?.let {
                    val personIdX = max(0f, it.left)
                    val personIdY = max(0f, it.top)

                    originalSizeCanvas.drawText(
                        person.id.toString(),
                        personIdX,
                        personIdY - PERSON_ID_MARGIN,
                        paintText
                    )
                    originalSizeCanvas.drawRect(it, paintLine)
                }
            }
            bodyJoints.forEach {
                val pointA = person.keyPoints[it.first.position].coordinate
                val pointB = person.keyPoints[it.second.position].coordinate
                originalSizeCanvas.drawLine(pointA.x, pointA.y, pointB.x, pointB.y, paintLine)
            }

            person.keyPoints.forEach { point ->
                originalSizeCanvas.drawCircle(
                    point.coordinate.x,
                    point.coordinate.y,
                    CIRCLE_RADIUS,
                    paintCircle
                )
            }
        }
        return output
    }


    fun cropToBBox(image: Bitmap, boundingBox: Rect): Bitmap? {
        // Ensure boundingBox coordinates are within the image bounds
        val left = boundingBox.left.coerceAtLeast(0)
        val top = boundingBox.top.coerceAtLeast(0)
        val right = boundingBox.right.coerceAtMost(image.width)
        val bottom = boundingBox.bottom.coerceAtMost(image.height)

        // Calculate the width and height of the cropped area
        val width = right - left
        val height = bottom - top

        if (width < SAFE_GAZE_MIN_FACE_SIZE || height < SAFE_GAZE_MIN_FACE_SIZE) {
            return null
        }

        // Create and return the cropped bitmap
        return Bitmap.createBitmap(image, left, top, width, height)
    }

    fun getFaceRegion(person: Person, confidenceThreshold: Float = 0.1f): Box? {
        val faceParts = setOf(
            BodyPart.NOSE,
            BodyPart.LEFT_EYE,
            BodyPart.RIGHT_EYE,
            BodyPart.LEFT_EAR,
            BodyPart.RIGHT_EAR,
        )
        val facePoints = person.keyPoints.filter { it.bodyPart in faceParts && it.score >= confidenceThreshold }

        if (facePoints.size < 2) return null

        // Get shoulder points if they exist
        val leftShoulder = person.keyPoints.find { it.bodyPart == BodyPart.LEFT_SHOULDER && it.score > 0 }
        val rightShoulder = person.keyPoints.find { it.bodyPart == BodyPart.RIGHT_SHOULDER && it.score > 0 }

        // Calculate base dimensions from face points
        val xs = facePoints.map { it.coordinate.x }
        val ys = facePoints.map { it.coordinate.y }
        val minX = xs.minOrNull() ?: 0f
        val maxX = xs.maxOrNull() ?: 0f
        val minY = ys.minOrNull() ?: 0f
        val maxY = ys.maxOrNull() ?: 0f
        val baseWidth = maxX - minX
        val baseHeight = maxY - minY

        // Get reference points for height calculation
        val leftEye = person.keyPoints.find { it.bodyPart == BodyPart.LEFT_EYE && it.score > 0 }
        val rightEye = person.keyPoints.find { it.bodyPart == BodyPart.LEFT_EYE && it.score > 0 }
        val nose = person.keyPoints.find { it.bodyPart == BodyPart.NOSE && it.score > 0 }

        if (leftEye == null && rightEye == null) return null
        if (nose == null) return null

        val heightBetweenEyeAndNose = abs((rightEye ?: leftEye)!!.coordinate.y - nose.coordinate.y)

        // Calculate width constraints
        val maxWidth = if (leftShoulder != null && rightShoulder != null) {
            abs(rightShoulder.coordinate.x - leftShoulder.coordinate.x)
        } else {
            baseWidth * 2
        }

        // Calculate height constraints
        val maxHeight = heightBetweenEyeAndNose * 4

        // Calculate padded dimensions (constrained by maxWidth/maxHeight)
        val desiredPaddedWidth = baseWidth * 1.2f // 20% padding
        val desiredPaddedHeight = baseHeight * 4f // 140% padding

        val finalWidth = Math.min(desiredPaddedWidth, maxWidth)
        val finalHeight = Math.min(desiredPaddedHeight, maxHeight)

        // Calculate padding to add (divided by 2 since we add to both sides)
        val widthPadding = (finalWidth - baseWidth) / 2
        val heightPadding = (finalHeight - baseHeight) / 2

        // Return padded region, ensuring we don't go below 0
        return Box(
            max(0f, minX - widthPadding),
            max(0f, minY - heightPadding * 0.5f),
            width = finalWidth,
            height = finalHeight,
        )
    }

    data class FaceMatch(
        val box: Box,
        val source: String
    )

    data class Box(
        val xMin: Float,
        val yMin: Float,
        val width: Float,
        val height: Float
    )

    fun rectToBox(rect: Rect?): Box? {
        if (rect == null) return null

        return Box(
            xMin = rect.left.toFloat(),
            yMin = rect.top.toFloat(),
            width = rect.width().toFloat(),
            height = rect.height().toFloat()
        )
    }

    fun getMatches(persons: List<Person>, mlfaces: List<Rect>): List<Person> {
        val faces = mlfaces.map { rectToBox(it)!! }
        val matches = mutableListOf<FaceMatch>()

        for (person in persons) {
            val poseBasedFaceRegion = getFaceRegion(person)

            if (poseBasedFaceRegion == null) {
                person.faceBox = null
                continue
            }

            var bestMatch: FaceMatch? = null
            var bestScore = -1f

            val poseCenter = Pair(
                poseBasedFaceRegion.xMin + poseBasedFaceRegion.width / 2,
                poseBasedFaceRegion.yMin + poseBasedFaceRegion.height / 2
            )

            for (face in faces) {
                // Skip if this face has already been matched
                if (matches.any { it.box == face }) continue

                val detectedFaceCenter = Pair(
                    face.xMin + face.width / 2,
                    face.yMin + face.height / 2
                )

                // Use pose-based face region for distance threshold
                val maxAllowedDistance = max(poseBasedFaceRegion.width, poseBasedFaceRegion.height)
                val centerDistance = getDistance(poseCenter, detectedFaceCenter)

                // If centers are too far apart, skip this match
                if (centerDistance > maxAllowedDistance) {
                    continue
                }

                val score = 1 - (centerDistance / maxAllowedDistance)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = FaceMatch(
                        box = face,
                        source = "face"
                    )
                }
            }

            // If no match was found, use pose-based face region as fallback
            if (bestMatch == null) {
                bestMatch = FaceMatch(
                    box = Box(
                        xMin = poseBasedFaceRegion.xMin,
                        yMin = poseBasedFaceRegion.yMin,
                        width = poseBasedFaceRegion.width,
                        height = poseBasedFaceRegion.height
                    ),
                    source = "pose"
                )
            }

            matches.add(bestMatch)
            person.faceBox = RectF(
                bestMatch.box.xMin,
                bestMatch.box.yMin,
                bestMatch.box.xMin + bestMatch.box.width,
                bestMatch.box.yMin + bestMatch.box.height
            )
        }

        return persons
    }

    private fun getDistance(point1: Pair<Float, Float>, point2: Pair<Float, Float>): Float {
        return sqrt(
            (point1.first - point2.first).pow(2) + (point1.second - point2.second).pow(2)
        )
    }
}
