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


    var autoPlayBlockerScript: String = """
(function() {
    let userInteracted = false;
    let blockedVideos = new WeakSet();
    
    // Track user interactions
    const trackUserInteraction = function() {
        userInteracted = true;
//        document.removeEventListener('click', trackUserInteraction);
    };
    
    document.addEventListener('click', trackUserInteraction);
    document.addEventListener('scroll', () => {
        userInteracted = false;
    });
    
    // Override play() method for all video and audio elements
    const originalPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function() {
        // Allow play if user has interacted with the page
        if (userInteracted) {
            return originalPlay.call(this);
        }
        
        // Check if this is a trusted user event (direct user interaction)
        if (event && event.isTrusted) {
            return originalPlay.call(this);
        }
        
        // Block programmatic autoplay (like Instagram's intersection observer triggers)
        console.log('Autoplay blocked by Kahf Browser - programmatic play detected');
        blockedVideos.add(this);
        
        // Pause the video if it somehow starts playing
        setTimeout(() => {
            if (!this.paused && blockedVideos.has(this)) {
                this.pause();
            }
        }, 0);
        
        return Promise.reject(new DOMException('Autoplay blocked', 'NotAllowedError'));
    };
    
    // Override autoplay property setter
    const originalAutoplayDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'autoplay');
    Object.defineProperty(HTMLMediaElement.prototype, 'autoplay', {
        get: function() {
            return originalAutoplayDescriptor.get.call(this);
        },
        set: function(value) {
            // Always set to false to prevent autoplay
            return originalAutoplayDescriptor.set.call(this, false);
        },
        configurable: true
    });
    
    // Monitor for videos that start playing without user interaction
    const monitorVideoPlayback = function(video) {
        video.addEventListener('play', function() {
            if (!userInteracted && !blockedVideos.has(this)) {
                console.log('Autoplay detected and blocked - pausing video');
                this.pause();
                blockedVideos.add(this);
            }
        });
        
        video.addEventListener('loadstart', function() {
            if (!userInteracted) {
                this.removeAttribute('autoplay');
                this.autoplay = false;
                this.removeAttribute('muted');
                this.muted = false
            }
        });
    };
    
    // Remove autoplay attributes and monitor videos
    const processMediaElements = function(elements) {
        elements.forEach(function(el) {
            el.removeAttribute('autoplay');
            el.removeAttribute('muted');
            el.muted = false
            el.autoplay = false;
            monitorVideoPlayback(el);
            
            // Pause if already playing without user interaction
            if (!userInteracted && !el.paused) {
                el.pause();
                blockedVideos.add(el);
            }
        });
    };
    
    // Mutation observer for dynamically added elements
    const observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(mutation) {
            mutation.addedNodes.forEach(function(node) {
                if (node.nodeType === 1) { // Element node
                    if (node.tagName === 'VIDEO' || node.tagName === 'AUDIO') {
                        processMediaElements([node]);
                    }
                    // Also check child elements
                    const mediaElements = node.querySelectorAll ? node.querySelectorAll('video, audio') : [];
                    if (mediaElements.length > 0) {
                        processMediaElements(mediaElements);
                    }
                }
            });
        });
    });
    
    // Start observing when body is available
    const startObserving = function() {
        if (document.body) {
            observer.observe(document.body, {
                childList: true,
                subtree: true
            });
        } else {
            setTimeout(startObserving, 10);
        }
    };
    
    startObserving();
    
    // Process existing elements
    const processExistingElements = function() {
        const mediaElements = document.querySelectorAll('video, audio');
        processMediaElements(mediaElements);
    };
    
    // Run immediately and on DOM ready
    processExistingElements();
    
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', processExistingElements);
    }
})();
"""
}
