/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.graphics.BitmapFactory
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import io.kahf.porda_segmentation.ImageProcessor
import io.kahf.porda_segmentation.InputImage
import timber.log.Timber
import kotlin.system.measureTimeMillis

/**
 * Check if the hardware is compatible with running the on-device models
 * We run the models on a test image and check if the inference time is within limits
 * @return true if the hardware is compatible, false otherwise
 */
suspend fun isHardwareCompatible(
    context: Context,
    nsfwDetector: NsfwDetector,
    imageDetector: ImageProcessor
): Boolean {
    Timber.d("kLog checking hardware compatibility")

    val bitmap = context.assets.open("test_image.webp").use {
        // convert input stream to byte array
        val buffer = ByteArray(it.available())
        it.read(buffer)
        it.close()

        BitmapFactory.decodeByteArray(
            buffer, 0, buffer.size,
            BitmapFactory.Options().also { op ->
                op.inSampleSize = 3
            },
        )
    }

    val inferenceTime = measureTimeMillis {
        val nsfwPrediction = nsfwDetector.isNsfw(bitmap)
        Timber.d("kLog nsfw classified. IsSafe: ${nsfwPrediction?.isSafe()}")

        val segmentationResult = imageDetector.downloadAndStore(
            InputImage(
                src = "test_image.webp",
                id = "test_image",
                width = bitmap.width,
                height = bitmap.height,
                imgBitmap = bitmap,
                baseImg = "none", // Assuming toBase64() is a valid method
            ),
        )
        Timber.d("kLog segmentation completed. Image modified: ${segmentationResult.isManipulated}")
    }

    return if (inferenceTime > 750) {
        Timber.e("kLog Will make it slower: $inferenceTime ms")
        false
    } else {
        Timber.d("kLog Will run fine: $inferenceTime ms")
        true
    }
}
