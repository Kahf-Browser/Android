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

package com.duckduckgo.app.safegaze.genderdetection

import android.graphics.Rect

data class GenderPrediction(
    var faceCount: Int = 0,
    var hasMale: Boolean = false,
    var hasFemale: Boolean = false,
    var maleConfidence: Float = 0.0f,
    var femaleConfidence: Float = 0.0f,
    var boundingBox: MutableList<Rect> = mutableListOf()
)
