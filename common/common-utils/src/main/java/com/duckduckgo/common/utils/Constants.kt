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

package com.duckduckgo.common.utils

const val SAFE_GAZE_JS_URL_DEV = "https://raw.githubusercontent.com/AsilHQ/Android/js_code_dev/node_modules/%40duckduckgo/privacy-dashboard/build/app/safe_gaze_v2.js"
const val SAFE_GAZE_JS_URL_PROD = "https://raw.githubusercontent.com/AsilHQ/Android/js_code_release/node_modules/%40duckduckgo/privacy-dashboard/build/app/safe_gaze_v2.js"
const val SAFE_GAZE_JS_FILENAME = "safe_gaze.js"
const val SAFE_GAZE_SOLID_COLOR_EFFECT = "safe_gaze_solid_color_effect"
const val SAFE_GAZE_INTERFACE = "SafeGazeInterface"
const val SAFE_GAZE_PREFERENCES = "safe_gaze_preferences"
const val SAFE_GAZE_DEFAULT_SOLID_COLOR_EFFECT = false
const val SAFE_GAZE_MIN_FACE_SIZE = 15
const val SAFE_GAZE_MIN_IMG_SIZE = 45
const val SAFE_GAZE_MAX_IMG_SIZE= 800
const val KAHF_GUARD_BLOCKED_WITH_DOT = "blocked.kahfguard.com."
const val KAHF_GUARD_BLOCKED_URL = "blocked.kahfguard.com"
const val KAHF_GUARD_BLOCKED_IP = "blocked.kahfguard.com"

// Private DNS Settings
const val KAHF_GUARD_INTENSITY = "kahf_guard_intensity"
const val KAHF_GUARD_DEFAULT = "High"

// SafeGaze (image blur) Settings
const val SAFE_GAZE_DEFAULT = "Off"
const val SAFE_GAZE_MODE = "safe_gaze_mode"

// SafeGaze Quota Count
const val SAFE_GAZE_LAST_RESET_DATE = "SafeGazeLastResetDate"
const val SAFE_GAZE_API_CALLS_COUNT = "SafeGazeAPICallsCount"
const val SAFE_GAZE_QUOTA_LIMIT = 60
