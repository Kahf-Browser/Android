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
import androidx.core.graphics.toRectF
import com.duckduckgo.app.browser.safe_gaze.SafeGazeResult
import com.duckduckgo.common.utils.SAFE_GAZE_MIN_FACE_SIZE
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

    fun debugDraw(
        input: Bitmap?,
        personList: List<Person>,
        drawFace: Boolean = true,
        drawPose: Boolean = true,
        drawBodyMask: Boolean = false,
    ): Bitmap {
        if (input == null) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val paintCircle = Paint().apply {
            strokeWidth = calculateStrokeWidth(input.width.toFloat(), input.height.toFloat()).toFloat()
            color = Color.GRAY
            style = Paint.Style.FILL
        }
        val paintLine = Paint().apply {
            strokeWidth = calculateStrokeWidth(input.width.toFloat(), input.height.toFloat()).toFloat()
            color = Color.GRAY
            style = Paint.Style.STROKE
        }

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val originalSizeCanvas = Canvas(output)

        val listOfTenColors = listOf(
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.CYAN,
            Color.MAGENTA,
            Color.WHITE,
            Color.LTGRAY,
            Color.DKGRAY,
            Color.BLACK
        )

        personList.forEachIndexed { i, person ->

            if (drawFace) {
                person.faceBox?.let { faceBox ->
                    val paint = Paint().apply {
                        strokeWidth = 5f
                        color = listOfTenColors[i % 10]
                        style = Paint.Style.STROKE
                    }
                    originalSizeCanvas.drawRect(faceBox, paint)
                }
            }

            if (drawPose) {
                person.poseBox?.let { poseBox ->
                    val paint = Paint().apply {
                        strokeWidth = 5f
                        color = listOfTenColors[i % 10]
                        style = Paint.Style.STROKE
                    }
                    originalSizeCanvas.drawRect(poseBox, paint)
                }
            }

            if (drawBodyMask) {
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
        }

        return output
    }

    private fun calculateStrokeWidth(
        bitmapWidth: Float,
        bitmapHeight: Float,
        mode: String = "standard",
        gender: String = "female",
        keyPoints: List<KeyPoint> = emptyList()
    ): Int {
        // Filter valid keypoints (with a score)
        val validPoints = keyPoints.filter { it.score > 0 }

        if (validPoints.isEmpty()) {
            // Fallback to old calculation if no valid points
            val smallerDimension = minOf(bitmapWidth, bitmapHeight)
            return maxOf(2, (smallerDimension * if (gender == "female") 0.32 else 0.25).toInt())
        }

        // Extract x and y values from keypoints
        val xs = validPoints.map { it.coordinate.x }
        val ys = validPoints.map { it.coordinate.y }
        val minX = xs.minOrNull() ?: 0f
        val maxX = xs.maxOrNull() ?: 0f
        val minY = ys.minOrNull() ?: 0f
        val maxY = ys.maxOrNull() ?: 0f

        // Calculate pose dimensions
        val poseWidth = maxX - minX
        val poseHeight = maxY - minY

        // Calculate pose size relative to the image
        val widthRatio = poseWidth / bitmapWidth
        val heightRatio = poseHeight / bitmapHeight
        val poseSizeRatio = maxOf(widthRatio, heightRatio)

        // Use the smaller of image or pose dimension for base calculation
        val poseDimension = minOf(poseWidth, poseHeight)
        val imageDimension = minOf(bitmapWidth, bitmapHeight) / 1.2f

        // Blend between pose-based and image-based scaling based on pose size
        val baseSize = poseDimension * (1 - poseSizeRatio) + imageDimension * poseSizeRatio
        val multiplier = if (gender == "female") 0.3f else 0.25f

        return when (mode) {
            "standard" -> {
                // Adjust base multiplier based on gender and pose size
                maxOf(2, (baseSize * multiplier * poseSizeRatio).toInt())
            }
            "debug" -> 5
            "detection" -> {
                // Similarly adjust detection stroke width
                maxOf(15, (baseSize * multiplier * poseSizeRatio).toInt())
            }
            else -> throw IllegalArgumentException("Unknown drawing mode: $mode")
        }
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

    data class Match(
        val pose: Person,
        val face: FaceMatch?
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

    fun boxToRect(box: Box?): Rect? {
        if (box == null) return null

        return Rect(
            box.xMin.toInt(),
            box.yMin.toInt(),
            (box.xMin + box.width).toInt(),
            (box.yMin + box.height).toInt(),
        )
    }

    fun matchFacesToPoses(personList: List<Person>, facesRect: List<Rect>): List<Person> {
        val matches = mutableListOf<Match>()
        val faces = facesRect.mapNotNull { rectToBox(it) }

        for (person in personList) {
            val poseBasedFaceRegion = getFaceRegion(person)
            if (poseBasedFaceRegion == null) {
                matches.add(Match(person, null))
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
                if (matches.any { it.face?.box == face }) continue

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

            matches.add(Match(person, bestMatch))
        }

        matches.forEach {
            it.pose.faceBox = boxToRect(it.face?.box)?.toRectF()
        }

        return matches.map { it.pose }.toList()
    }

    private fun getDistance(point1: Pair<Float, Float>, point2: Pair<Float, Float>): Float {
        return sqrt(
            (point1.first - point2.first).pow(2) + (point1.second - point2.second).pow(2)
        )
    }

    fun toJson(gson: Gson, safeGazeResult: SafeGazeResult): String {
        val resultArray = JsonArray()

        safeGazeResult.persons.forEach { person ->
            val jsonObject = JsonObject()

            // Transform keyPoints
            val keyPointsArray = JsonArray()
            person.keyPoints.forEach { keyPoint ->
                val keypointObject = JsonObject()
                keypointObject.addProperty("score", keyPoint.score)
                keypointObject.addProperty("name", keyPoint.bodyPart.name.lowercase())
                keypointObject.addProperty("x", keyPoint.coordinate.x) // Adjust x as needed
                keypointObject.addProperty("y", keyPoint.coordinate.y) // Adjust y as needed
                keyPointsArray.add(keypointObject)
            }
            jsonObject.add("keypoints", keyPointsArray)

            // Add poseScore
            jsonObject.addProperty("poseScore", person.poseScore)

            // Transform faceBox
            person.faceBox?.let {
                val faceBoxObject = JsonObject()
                faceBoxObject.addProperty("xMin", it.left)
                faceBoxObject.addProperty("xMax", it.right)
                faceBoxObject.addProperty("yMin", it.top)
                faceBoxObject.addProperty("yMax", it.bottom)
                faceBoxObject.addProperty("width", it.width())
                faceBoxObject.addProperty("height", it.height())
                jsonObject.add("faceBox", faceBoxObject)
            }

            // Add other properties
            jsonObject.addProperty("isFemale", person.isFemale)
            jsonObject.addProperty("genderScore", person.genderScore)

            // Add to result array
            resultArray.add(jsonObject)
        }

        // Serialize to JSON
        val jsonResult = JsonObject().also {
            it.addProperty("imageWidth", safeGazeResult.imageWidth)
            it.addProperty("imageHeight", safeGazeResult.imageHeight)
            it.add("persons", resultArray)
        }

        return gson.toJson(jsonResult)
    }
}
