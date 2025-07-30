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
const val SAFE_GAZE_WHITELIST = "https://raw.githubusercontent.com/Kahf-Browser/public/refs/heads/main/config/whitelist.txt"
const val KAHF_FEEDBACK_FORM = "https://tally.so/r/mKkz2K"

const val SAFE_GAZE_JS_FILENAME = "safe_gaze.js"
const val SAFE_GAZE_INTERFACE = "SafeGazeInterface"
const val SAFE_GAZE_PREFERENCES = "safe_gaze_preferences"
const val SAFE_GAZE_FACE_COVER = "safe_gaze_face_cover"
const val SAFE_GAZE_AUTOPLAY_VIDEO = "safe_gaze_autoplay_video"
const val SAFE_GAZE_LOCK = "safe_gaze_lock"
const val SAFE_GAZE_MAX_IMG_SIZE = 300
const val KAHF_GUARD_BLOCKED_WITH_DOT = "blocked.kahfguard.com."
const val KAHF_GUARD_BLOCKED_URL = "blocked.kahfguard.com"
const val KAHF_GUARD_BLOCKED_IP = "blocked.kahfguard.com"

// Private DNS Settings
const val KAHF_GUARD_INTENSITY = "kahf_guard_intensity"
const val KAHF_GUARD_DEFAULT = "Medium"

// SafeGaze (image blur) Settings
const val SAFE_GAZE_DEFAULT = "Pixelation"
const val SAFE_GAZE_MODE = "safe_gaze_mode"
const val DEFAULT_FACE_COVER = false
const val DEFAULT_AUTOPLAY_VIDEO = true

// Firebase Remote Config
const val MIN_VERSION = "minimum_version"
const val AD_REFRESH_INTERVAL = "ad_refresh_interval"
const val EPOM_PLACEMENT_ID = "0dfa8081b94508f158a190b8805ed9e8"
const val FALLBACK_PUBLISHER_ID = "kahf-browser"
const val LAST_DEFAULT_APP_CHECK_TIME = "last_default_app_check_time"
