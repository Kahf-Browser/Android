/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.app.safegaze.nsfwdetection

private val labels = listOf("drawing", "hentai", "neutral", "porn", "sexy")

data class NsfwPrediction(val predictions: FloatArray) {
    fun drawing() = predictions[0]
    fun hentai() = predictions[1]
    fun neutral() = predictions[2]
    fun porn() = predictions[3]
    fun sexy() = predictions[4]

    fun getLabelWithConfidence(): Pair<String, Float> {
        val maxIndex = predictions.indices.maxByOrNull { i -> predictions[i] } ?: -1
        val label = if (maxIndex != -1 && maxIndex < labels.size) {
            labels[maxIndex]
        } else {
            "Unknown"
        }

        return Pair(label, predictions[maxIndex])
    }

    fun safeScore() = drawing() + neutral()

    fun unsafeScore() = hentai() + porn() + sexy()

    fun isSafe(): Boolean {
        return unsafeScore() < 0.85
        // val x = predictions.indices.maxByOrNull { i -> predictions[i] } ?: -1
        // return x == 0 || x == 2
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as NsfwPrediction

        return predictions.contentEquals(other.predictions)
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + predictions.contentHashCode()
        return result
    }
}
