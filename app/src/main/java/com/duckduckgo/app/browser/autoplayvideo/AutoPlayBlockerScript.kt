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

package com.duckduckgo.app.browser.autoplayvideo

object AutoPlayBlockerScript {

    val gptSampleScript = """
        (function() {
            function disableAutoplay(media) {
                if (!media) return;

                // Prevent autoplay behavior
                media.autoplay = false;
                media.muted = false;
                media.removeAttribute('autoplay');
                media.removeAttribute('muted');

                // If video has started playing without user interaction, pause it
                if (!media.paused && !media._userInteracted) {
                    media.pause();
                }

                // Mark as handled
                if (!media._listenerAttached) {
                    media._listenerAttached = true;

                    // Respect user intent: allow playback if user interacts
                    media.addEventListener('play', () => {
                        media._userInteracted = true;
                    }, { once: true });

                    media.addEventListener('click', () => {
                        media._userInteracted = true;
                    });
                }
            }

            function handleAllMedia() {
                const allMedia = document.querySelectorAll('video, audio');
                allMedia.forEach(media => disableAutoplay(media));
            }

            // Initial run
            handleAllMedia();

            // Watch for new media in dynamic feeds
            const observer = new MutationObserver(() => {
                handleAllMedia();
            });

            observer.observe(document.body, {
                childList: true,
                subtree: true
            });
        })();
    """.trimIndent()
}
