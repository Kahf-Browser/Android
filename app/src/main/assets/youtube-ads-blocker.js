/**
 * SafeGaze YouTube Ads Blocker - Standalone Script
 *
 * Cross-platform compatible script for blocking YouTube ads.
 * Can be used in:
 * - Browser Extensions (Chrome, Firefox, Edge)
 * - Android WebView
 * - iOS WKWebView
 *
 * This script combines three layers of ad blocking:
 * - Layer 1: Player data interception (ytInitialPlayerResponse, fetch, XHR hooks)
 * - Layer 2: Blocked URL patterns (for mobile network-level blocking)
 * - Layer 3: DOM-based fallback (ad detection, skip buttons, overlay removal)
 */
(function() {
  'use strict';

  // Prevent multiple initializations
  if (window.__SAFEGAZE_YT_AD_BLOCKER_INITIALIZED__) {
    return;
  }
  window.__SAFEGAZE_YT_AD_BLOCKER_INITIALIZED__ = true;

  // =============================================================================
  // LAYER 2: BLOCKED URL PATTERNS
  // These patterns can be used by mobile apps for network-level blocking
  // Access via: window.SAFEGAZE_BLOCKED_AD_PATTERNS
  // =============================================================================
  var BLOCKED_AD_PATTERNS = [
    // Ad serving domains
    '*://*.googlesyndication.com/*',
    '*://*.doubleclick.net/*',
    '*://googleads.g.doubleclick.net/*',
    '*://static.doubleclick.net/*',

    // YouTube ad endpoints (classic)
    '*://youtube.com/api/stats/ads*',
    '*://youtube.com/ptracking*',
    '*://youtube.com/pagead/*',
    '*://youtube.com/get_midroll_*',
    '*://youtube.com/ad_*',
    '*://youtube.com/adunit/*',
    '*://*.youtube.com/api/stats/ads*',
    '*://*.youtube.com/ptracking*',
    '*://*.youtube.com/pagead/*',
    '*://*.youtube.com/get_midroll_*',
    '*://*.youtube.com/ad_*',

    // YouTube API v1 ad endpoints
    '*://youtube.com/youtubei/v1/player/ad_*',
    '*://*.youtube.com/youtubei/v1/player/ad_*',
    '*://youtube.com/api/stats/qoe*',
    '*://*.youtube.com/api/stats/qoe*',

    // Video ad segments (googlevideo.com with ad parameters)
    '*://googlevideo.com/videoplayback*&aclk=*',
    '*://googlevideo.com/videoplayback*&ad=*',
    '*://googlevideo.com/videoplayback*ad_*',
    '*://googlevideo.com/pcs/activeview*',
    '*://*.googlevideo.com/videoplayback*&aclk=*',
    '*://*.googlevideo.com/videoplayback*&ad=*',
    '*://*.googlevideo.com/videoplayback*ad_*',
    '*://*.googlevideo.com/pcs/activeview*',

    // Ad tracking and analytics
    '*://youtube.com/api/stats/watchtime*',
    '*://*.youtube.com/api/stats/watchtime*',
    '*://google.com/pagead/*',
    '*://*.google.com/pagead/*',
    '*://youtube.com/pagead/interaction/*',
    '*://*.youtube.com/pagead/interaction/*'
  ];

  // Expose blocked patterns for mobile apps
  window.SAFEGAZE_BLOCKED_AD_PATTERNS = BLOCKED_AD_PATTERNS;

  // =============================================================================
  // LAYER 1: PLAYER DATA INTERCEPTION
  // Intercepts YouTube's player data to remove ads before they load
  // =============================================================================

  /**
   * Remove ad-related properties from YouTube player data
   * Modifies objects in-place to preserve object types and avoid cloning overhead
   * @param {Object} data - The data object to clean
   * @returns {Object} The cleaned data object
   */
  function removeAdData(data) {
    if (!data || typeof data !== 'object') {
      return data;
    }

    // Only remove confirmed ad-related properties
    var adProps = [
      'playerAds',
      'adPlacements',
      'adSlots',
      'ads',
      'adBreakParams',
      'companions'
    ];

    function cleanObject(obj) {
      if (!obj || typeof obj !== 'object') return;

      // Remove ad properties directly (no cloning)
      for (var i = 0; i < adProps.length; i++) {
        if (obj.hasOwnProperty(adProps[i])) {
          delete obj[adProps[i]];
        }
      }

      // Recursively clean nested objects
      var keys = Object.keys(obj);
      for (var j = 0; j < keys.length; j++) {
        var key = keys[j];
        if (obj[key] && typeof obj[key] === 'object') {
          cleanObject(obj[key]);
        }
      }
    }

    cleanObject(data);
    return data;
  }

  // Layer 1A: Intercept ytInitialPlayerResponse (page load)
  try {
    var _ytInitialPlayerResponse;

    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      set: function(value) {
        _ytInitialPlayerResponse = removeAdData(value);
      },
      get: function() {
        return _ytInitialPlayerResponse;
      },
      configurable: true,
      enumerable: true
    });
  } catch (error) {
    console.error('[SafeGaze] Failed to hook ytInitialPlayerResponse:', error);
  }

  // Layer 1B: Intercept fetch() API (dynamic requests)
  try {
    var originalFetch = window.fetch;

    window.fetch = function(input, init) {
      var url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;

      // PERFORMANCE: Skip video CDN entirely (never intercept video segments)
      if (url && url.indexOf('googlevideo.com') !== -1) {
        return originalFetch.call(this, input, init);
      }

      // Call original fetch
      return originalFetch.call(this, input, init).then(function(response) {
        // PERFORMANCE: Only process JSON responses (skip binary video/images)
        var contentType = response.headers.get('content-type');
        if (!contentType || contentType.indexOf('application/json') === -1) {
          return response;
        }

        // Only intercept player API, NOT comments (/next) or navigation
        if (url && url.indexOf('/youtubei/v1/player') !== -1 && url.indexOf('/next') === -1) {
          // Clone response to read it
          var cloned = response.clone();
          return cloned.text().then(function(text) {
            // Try to parse as JSON
            var data;
            try {
              data = JSON.parse(text);
            } catch (e) {
              // Not JSON, return original
              return response;
            }

            // Remove ad data (modifies in place)
            removeAdData(data);

            // Reconstruct response with proper headers
            var modifiedText = JSON.stringify(data);
            var modifiedHeaders = new Headers(response.headers);
            modifiedHeaders.set('content-length', modifiedText.length.toString());

            return new Response(modifiedText, {
              status: response.status,
              statusText: response.statusText,
              headers: modifiedHeaders
            });
          }).catch(function() {
            return response;
          });
        }

        return response;
      });
    };
  } catch (error) {
    console.error('[SafeGaze] Failed to hook fetch():', error);
  }

  // Layer 1C: Intercept XMLHttpRequest (legacy support)
  try {
    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
      this._sgUrl = url ? url.toString() : '';
      return originalOpen.call(this, method, url, async !== false, user, password);
    };

    XMLHttpRequest.prototype.send = function() {
      var self = this;
      var url = this._sgUrl || '';
      var args = arguments;

      // CRITICAL: Only intercept player API, NOT comments (/next) or navigation
      if (url.indexOf('/youtubei/v1/player') !== -1 && url.indexOf('/next') === -1) {
        this.addEventListener('readystatechange', function() {
          if (self.readyState === 4 && self.responseText) {
            try {
              var data = JSON.parse(self.responseText);
              removeAdData(data);

              // Override responseText getter
              Object.defineProperty(self, 'responseText', {
                writable: false,
                configurable: true,
                value: JSON.stringify(data)
              });
            } catch (e) {
              // Not JSON or parsing failed, ignore
            }
          }
        });
      }

      return originalSend.apply(this, args);
    };
  } catch (error) {
    console.error('[SafeGaze] Failed to hook XMLHttpRequest:', error);
  }

  // =============================================================================
  // LAYER 3: DOM-BASED FALLBACK
  // Detects and skips ads that slip through Layer 1-2
  // =============================================================================

  var YouTubeAdSkipper = {
    observer: null,
    checkInterval: null,
    isInitialized: false,
    lastAdState: false,
    userWasMuted: false,

    /**
     * Initialize ad skipper
     */
    init: function() {
      var self = this;
      if (this.isInitialized) return;

      // Inject CSS for ad hiding
      this.injectAdBlockingCSS();

      // Wait for player then start detection
      this.waitForPlayer().then(function() {
        self.setupAdDetection();
        self.isInitialized = true;
      });

      // Handle YouTube SPA navigation
      this.observeYouTubeNavigation();
    },

    /**
     * Wait for YouTube player to be ready
     */
    waitForPlayer: function() {
      return new Promise(function(resolve) {
        function checkPlayer() {
          var moviePlayer = document.getElementById('movie_player');
          var video = document.querySelector('.video-stream');

          if (moviePlayer && video) {
            resolve();
          } else {
            setTimeout(checkPlayer, 100);
          }
        }

        checkPlayer();
      });
    },

    /**
     * Setup ad detection - Simple and fast
     */
    setupAdDetection: function() {
      var self = this;
      var moviePlayer = document.getElementById('movie_player');
      if (!moviePlayer) return;

      // MutationObserver for class changes
      this.observer = new MutationObserver(function() {
        self.handleAdDetection();
      });

      if (moviePlayer instanceof Node) {
        this.observer.observe(moviePlayer, {
          attributes: true,
          attributeFilter: ['class']
        });
      }

      // Backup polling every 100ms for reliability
      this.checkInterval = setInterval(function() {
        self.handleAdDetection();
      }, 100);
    },

    /**
     * Layer 3: Fallback ad detection and skipping
     * Only triggers if ads slip through Layers 1-2 (should be rare)
     * More aggressive detection and immediate skipping
     */
    handleAdDetection: function() {
      var video = document.querySelector('.video-stream');
      var moviePlayer = document.getElementById('movie_player');

      if (!video || !moviePlayer) return;

      // Multi-signal ad detection (more comprehensive)
      var isNowInAd = (moviePlayer && (
          moviePlayer.classList.contains('ad-showing') ||
          moviePlayer.classList.contains('ad-interrupting')
        )) ||
        document.querySelector('.ytp-ad-player-overlay') !== null ||
        document.querySelector('.video-ads.ytp-ad-module') !== null ||
        document.querySelector('.ytp-ad-text') !== null;

      var wasInAd = this.lastAdState;

      // STATE TRANSITION: Entering ad state (fallback - should be rare with MAIN world script)
      if (isNowInAd && !wasInAd) {
        // Save user's mute preference
        this.userWasMuted = video.muted;

        // Mute immediately
        video.muted = true;

        // Skip to end IMMEDIATELY (most aggressive)
        if (video.duration && !isNaN(video.duration)) {
          video.currentTime = video.duration;
        }

        // Also try speed-up as backup
        video.playbackRate = 16;

        // Click skip buttons
        this.clickSkipButton();
        this.removeAdOverlays();
      }

      // STATE TRANSITION: Exiting ad state
      if (!isNowInAd && wasInAd) {
        // Restore user's original mute preference
        video.muted = this.userWasMuted;

        // Reset playback speed
        video.playbackRate = 1;

        // Auto-resume playback
        setTimeout(function() {
          if (video.paused) {
            video.play().catch(function() {
              // Ignore autoplay errors
            });
          }
        }, 50);
      }

      // When STAYING in ad state, continuously try to skip
      if (isNowInAd && wasInAd) {
        // Keep jumping to end
        if (video.duration && !isNaN(video.duration)) {
          if (video.currentTime < video.duration - 0.3) {
            video.currentTime = video.duration;
          }
        }
        this.clickSkipButton();
        this.removeAdOverlays();
      }

      // When STAYING in content state - DO NOTHING (respect user controls)

      // Update state for next check
      this.lastAdState = isNowInAd;
    },

    /**
     * Click skip ad button
     */
    clickSkipButton: function() {
      var skipSelectors = [
        '.ytp-ad-skip-button',
        '.ytp-ad-skip-button-modern',
        '.ytp-skip-ad-button'
      ];

      for (var i = 0; i < skipSelectors.length; i++) {
        var button = document.querySelector(skipSelectors[i]);
        if (button && button.offsetParent !== null) {
          button.click();
          break;
        }
      }
    },

    /**
     * Remove ad overlay elements from DOM
     */
    removeAdOverlays: function() {
      var adOverlaySelectors = [
        '.ytp-ad-overlay-container',
        '.ytp-ad-text-overlay',
        '.ytp-ad-image-overlay',
        '.ytp-ad-player-overlay-flyout-cta',
        '.ytp-ad-overlay-close-container'
      ];

      for (var i = 0; i < adOverlaySelectors.length; i++) {
        var elements = document.querySelectorAll(adOverlaySelectors[i]);
        for (var j = 0; j < elements.length; j++) {
          elements[j].remove();
        }
      }
    },

    /**
     * Inject CSS for ad hiding
     */
    injectAdBlockingCSS: function() {
      var existingStyle = document.getElementById('sg-youtube-ad-skipper-styles');
      if (existingStyle) return;

      var style = document.createElement('style');
      style.id = 'sg-youtube-ad-skipper-styles';
      style.textContent =
        '/* Hide ad-related elements */\n' +
        '.ad-showing .video-ads,\n' +
        '.ad-showing .ytp-ad-module,\n' +
        '.ad-showing .ytp-ad-player-overlay,\n' +
        '.ad-interrupting .video-ads,\n' +
        '.ad-interrupting .ytp-ad-module,\n' +
        '.ad-interrupting .ytp-ad-player-overlay {\n' +
        '  display: none !important;\n' +
        '  visibility: hidden !important;\n' +
        '}\n' +
        '\n' +
        '/* Hide YouTube ad renderers */\n' +
        'ytd-display-ad-renderer,\n' +
        'ytd-video-masthead-ad-v3-renderer,\n' +
        'ytd-promoted-sparkles-web-renderer,\n' +
        'ytd-compact-promoted-video-renderer,\n' +
        'ytd-promoted-video-renderer,\n' +
        'ytd-banner-promo-renderer,\n' +
        'ytd-action-companion-ad-renderer {\n' +
        '  display: none !important;\n' +
        '}\n' +
        '\n' +
        '/* Hide skip ad button container */\n' +
        '.ytp-ad-skip-button-container {\n' +
        '  display: none !important;\n' +
        '}';

      // Append to head or documentElement (for early injection)
      var target = document.head || document.documentElement;
      if (target) {
        target.appendChild(style);
      }
    },

    /**
     * Observe YouTube SPA navigation
     */
    observeYouTubeNavigation: function() {
      var self = this;

      window.addEventListener('yt-navigate-finish', function() {
        if (self.isWatchPage()) {
          self.cleanup();
          self.isInitialized = false;
          self.lastAdState = false; // Reset state for new video
          self.init();
        }
      });

      window.addEventListener('popstate', function() {
        if (self.isWatchPage()) {
          self.cleanup();
          self.isInitialized = false;
          self.lastAdState = false; // Reset state for new video
          self.init();
        }
      });
    },

    /**
     * Check if current page is a YouTube watch page
     */
    isWatchPage: function() {
      return window.location.pathname === '/watch' && window.location.search.indexOf('v=') !== -1;
    },

    /**
     * Cleanup observers and intervals
     */
    cleanup: function() {
      if (this.observer) {
        this.observer.disconnect();
        this.observer = null;
      }

      if (this.checkInterval !== null) {
        clearInterval(this.checkInterval);
        this.checkInterval = null;
      }
    },

    /**
     * Destroy the ad skipper
     */
    destroy: function() {
      this.cleanup();
      this.isInitialized = false;
    }
  };

  // =============================================================================
  // AUTO-INITIALIZATION
  // =============================================================================

  // Only run on YouTube domains
  if (window.location.hostname.indexOf('youtube.com') !== -1) {
    // Initialize Layer 3 (DOM-based fallback)
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function() {
        YouTubeAdSkipper.init();
      });
    } else {
      YouTubeAdSkipper.init();
    }
  }

  // Expose for debugging (optional - remove in production if desired)
  window.__SAFEGAZE_YT_AD_SKIPPER__ = YouTubeAdSkipper;

})();
