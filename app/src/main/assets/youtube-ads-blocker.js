/**
 * SafeGaze YouTube Ads Blocker
 * Version: 2.4.0
 * Updated: 2026-01-21
 *
 * CROSS-PLATFORM VERSION - Desktop + Mobile support
 * - Layer 1: Player data interception (video ads)
 * - Layer 2: Network rules (ad domains)
 * - Layer 3: Video player DOM fallback (skip buttons)
 * - Layer 4: Feed ad blocking (Desktop ytd-* + Mobile ytm-*)
 *
 * Supported platforms:
 * - Browser Extensions (Chrome, Firefox, Edge)
 * - Android WebView
 * - iOS WKWebView
 *
 * Use --local flag when building to preserve these changes
 */

(function() {
  'use strict';

  // Prevent multiple initializations
  if (window.__SAFEGAZE_YT_AD_BLOCKER_INITIALIZED__) {
    return;
  }
  window.__SAFEGAZE_YT_AD_BLOCKER_INITIALIZED__ = true;

  // =============================================================================
  // LAYER 2: BLOCKED URL PATTERNS (for reference - used by manifest/network rules)
  // =============================================================================
  var BLOCKED_AD_PATTERNS = [
    '*://*.googlesyndication.com/*',
    '*://*.doubleclick.net/*',
    '*://youtube.com/api/stats/ads*',
    '*://youtube.com/pagead/*',
    '*://*.youtube.com/api/stats/ads*',
    '*://*.youtube.com/pagead/*',
    '*://m.youtube.com/api/stats/ads*',
    '*://m.youtube.com/pagead/*'
  ];
  window.SAFEGAZE_BLOCKED_AD_PATTERNS = BLOCKED_AD_PATTERNS;

  // =============================================================================
  // LAYER 1: PLAYER DATA INTERCEPTION (Primary ad blocking - zero performance cost)
  // Works on both desktop and mobile
  // =============================================================================

  /**
   * Remove ad-related properties from YouTube player data
   */
  function removeAdData(data) {
    if (!data || typeof data !== 'object') return data;

    var adProps = ['playerAds', 'adPlacements', 'adSlots', 'ads', 'adBreakParams', 'companions'];

    function cleanObject(obj) {
      if (!obj || typeof obj !== 'object') return;

      for (var i = 0; i < adProps.length; i++) {
        if (obj.hasOwnProperty(adProps[i])) {
          delete obj[adProps[i]];
        }
      }

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

  // Layer 1A: Intercept ytInitialPlayerResponse
  try {
    var _ytInitialPlayerResponse;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      set: function(value) { _ytInitialPlayerResponse = removeAdData(value); },
      get: function() { return _ytInitialPlayerResponse; },
      configurable: true,
      enumerable: true
    });
  } catch (e) {}

  // Layer 1B: Intercept fetch() - ONLY player API
  try {
    var originalFetch = window.fetch;
    window.fetch = function(input, init) {
      var url = typeof input === 'string' ? input : (input instanceof URL ? input.toString() : (input && input.url));

      // Skip ALL video/media requests entirely
      if (!url || url.indexOf('googlevideo.com') !== -1 || url.indexOf('.jpg') !== -1 ||
          url.indexOf('.png') !== -1 || url.indexOf('.webp') !== -1) {
        return originalFetch.call(this, input, init);
      }

      // Only intercept /youtubei/v1/player (not /next, /search, /browse, etc.)
      if (url.indexOf('/youtubei/v1/player') !== -1 && url.indexOf('/next') === -1) {
        return originalFetch.call(this, input, init).then(function(response) {
          var contentType = response.headers.get('content-type');
          if (!contentType || contentType.indexOf('application/json') === -1) {
            return response;
          }

          var cloned = response.clone();
          return cloned.text().then(function(text) {
            try {
              var data = JSON.parse(text);
              removeAdData(data);
              return new Response(JSON.stringify(data), {
                status: response.status,
                statusText: response.statusText,
                headers: response.headers
              });
            } catch (e) {
              return response;
            }
          }).catch(function() { return response; });
        });
      }

      return originalFetch.call(this, input, init);
    };
  } catch (e) {}

  // Layer 1C: Intercept XMLHttpRequest - ONLY player API
  try {
    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function(method, url) {
      this._sgUrl = url ? url.toString() : '';
      return originalOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function() {
      var self = this;
      var url = this._sgUrl || '';

      if (url.indexOf('/youtubei/v1/player') !== -1 && url.indexOf('/next') === -1) {
        this.addEventListener('readystatechange', function() {
          if (self.readyState === 4 && self.responseText) {
            try {
              var data = JSON.parse(self.responseText);
              removeAdData(data);
              Object.defineProperty(self, 'responseText', {
                writable: false,
                configurable: true,
                value: JSON.stringify(data)
              });
            } catch (e) {}
          }
        });
      }

      return originalSend.apply(this, arguments);
    };
  } catch (e) {}

  // =============================================================================
  // LAYER 4: FEED AD BLOCKING UTILITIES (Desktop + Mobile)
  // =============================================================================

  /**
   * Check if element contains legitimate video content (not an ad)
   * Works for both desktop (ytd-*) and mobile (ytm-*) elements
   */
  function isLegitimateVideo(element) {
    if (!element) return false;

    // Check for video renderers (legitimate content) - Desktop + Mobile
    var hasVideo = element.querySelector(
      // Desktop
      'ytd-video-renderer, ytd-grid-video-renderer, ' +
      'ytd-compact-video-renderer, ytd-playlist-video-renderer, ' +
      'ytd-rich-grid-media, ' +
      // Mobile
      'ytm-video-with-context-renderer, ytm-compact-video-renderer, ' +
      'ytm-media-item, ytm-rich-grid-media'
    );

    // Check for ad renderers - Desktop + Mobile
    var hasAd = element.querySelector(
      // Desktop
      'ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ' +
      'ytd-display-ad-renderer, ytd-promoted-video-renderer, ' +
      'ytd-promoted-sparkles-web-renderer, ' +
      // Mobile
      'ytm-ad-slot-renderer, ytm-promoted-video-renderer, ' +
      'ytm-companion-ad-renderer, ytm-in-feed-ad-layout-renderer'
    );

    // Legitimate if has video AND no ad
    return hasVideo && !hasAd;
  }

  /**
   * One-time cleanup of ad containers
   * Removes parent elements to eliminate black boxes
   * Supports both Desktop (ytd-*) and Mobile (ytm-*) selectors
   */
  function cleanupAdContainers() {
    // Desktop ad selectors
    var desktopAdSelectors = [
      'ytd-ad-slot-renderer',
      'ytd-in-feed-ad-layout-renderer',
      'ytd-promoted-video-renderer',
      'ytd-display-ad-renderer',
      'ytd-promoted-sparkles-web-renderer',
      'ytd-video-masthead-ad-v3-renderer',
      'ytd-video-masthead-ad-primary-video-renderer',
      'ytd-statement-banner-renderer',
      'ytd-banner-promo-renderer',
      'ytd-primetime-promo-renderer',
      'ytd-search-pyv-renderer',
      'ytd-compact-promoted-video-renderer'
    ];

    // Mobile ad selectors
    var mobileAdSelectors = [
      'ytm-ad-slot-renderer',
      'ytm-promoted-video-renderer',
      'ytm-companion-ad-renderer',
      'ytm-in-feed-ad-layout-renderer',
      'ytm-statement-banner-renderer',
      'ytm-primetime-promo-renderer',
      'ytm-display-ad-renderer',
      'ytm-rich-item-renderer[is-ad]',
      'ytm-video-with-context-renderer[is-ad]'
    ];

    var allSelectors = desktopAdSelectors.concat(mobileAdSelectors);
    var removedCount = 0;

    allSelectors.forEach(function(selector) {
      try {
        var ads = document.querySelectorAll(selector);
        ads.forEach(function(ad) {
          // Desktop parent containers
          var richItem = ad.closest('ytd-rich-item-renderer');
          if (richItem && !isLegitimateVideo(richItem)) {
            richItem.remove();
            removedCount++;
            return;
          }

          var itemSection = ad.closest('ytd-item-section-renderer');
          if (itemSection && !itemSection.querySelector('ytd-video-renderer')) {
            itemSection.remove();
            removedCount++;
            return;
          }

          var richSection = ad.closest('ytd-rich-section-renderer');
          if (richSection && !isLegitimateVideo(richSection)) {
            richSection.remove();
            removedCount++;
            return;
          }

          // Mobile parent containers
          var mobileItemSection = ad.closest('ytm-item-section-renderer');
          if (mobileItemSection && !mobileItemSection.querySelector('ytm-video-with-context-renderer')) {
            mobileItemSection.remove();
            removedCount++;
            return;
          }

          var mobileRichItem = ad.closest('ytm-rich-item-renderer');
          if (mobileRichItem && !isLegitimateVideo(mobileRichItem)) {
            mobileRichItem.remove();
            removedCount++;
            return;
          }

          // Fallback: remove the ad element itself
          ad.remove();
          removedCount++;
        });
      } catch (e) {}
    });

    return removedCount;
  }

  // =============================================================================
  // LAYER 3 & 4: DOM-BASED AD HANDLING (Desktop + Mobile)
  // =============================================================================

  var YouTubeAdBlocker = {
    observer: null,
    isHandlingAd: false,
    userWasMuted: false,
    initialized: false,
    scrollTimeout: null,
    cleanupPending: false,

    init: function() {
      if (this.initialized) return;
      this.initialized = true;

      var self = this;

      // Inject comprehensive CSS (includes mobile selectors)
      this.injectCSS();

      // Initial ad cleanup (runs once)
      this.scheduleCleanup();

      // Setup video player monitoring on watch pages
      if (this.isWatchPage()) {
        this.waitForPlayer(function(moviePlayer) {
          if (!moviePlayer) return;
          self.setupPlayerObserver(moviePlayer);
        });
      }

      // Handle YouTube SPA navigation (works on both desktop and mobile)
      window.addEventListener('yt-navigate-finish', function() {
        self.scheduleCleanup();

        if (self.isWatchPage()) {
          self.cleanupPlayerObserver();
          self.waitForPlayer(function(moviePlayer) {
            if (moviePlayer) self.setupPlayerObserver(moviePlayer);
          });
        }
      });

      // Debounced scroll handler for lazy-loaded content
      window.addEventListener('scroll', function() {
        if (self.scrollTimeout) clearTimeout(self.scrollTimeout);
        self.scrollTimeout = setTimeout(function() {
          self.scheduleCleanup();
        }, 500);
      }, { passive: true });

      // Touch scroll for mobile
      window.addEventListener('touchend', function() {
        if (self.scrollTimeout) clearTimeout(self.scrollTimeout);
        self.scrollTimeout = setTimeout(function() {
          self.scheduleCleanup();
        }, 500);
      }, { passive: true });
    },

    /**
     * Schedule ad cleanup using requestIdleCallback
     */
    scheduleCleanup: function() {
      if (this.cleanupPending) return;
      this.cleanupPending = true;

      var self = this;
      var doCleanup = function() {
        cleanupAdContainers();
        self.cleanupPending = false;
      };

      if (window.requestIdleCallback) {
        requestIdleCallback(doCleanup, { timeout: 1000 });
      } else {
        setTimeout(doCleanup, 100);
      }
    },

    isWatchPage: function() {
      return window.location.pathname === '/watch' ||
             window.location.pathname.indexOf('/watch') === 0;
    },

    /**
     * Wait for YouTube player - supports both desktop and mobile players
     */
    waitForPlayer: function(callback) {
      var attempts = 0;
      function check() {
        // Desktop player
        var player = document.getElementById('movie_player');

        // Mobile player fallbacks
        if (!player) player = document.querySelector('.html5-video-player');
        if (!player) player = document.querySelector('ytm-player');
        if (!player) player = document.querySelector('#player-container .html5-video-player');

        if (player) {
          callback(player);
        } else if (attempts < 30) {
          attempts++;
          setTimeout(check, 200);
        }
      }
      check();
    },

    setupPlayerObserver: function(moviePlayer) {
      var self = this;

      // Single observer - only watches for ad-showing class
      this.observer = new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
          if (mutations[i].attributeName === 'class') {
            var hasAd = moviePlayer.classList.contains('ad-showing') ||
                        moviePlayer.classList.contains('ad-interrupting');

            if (hasAd && !self.isHandlingAd) {
              self.handleAdStart();
            } else if (!hasAd && self.isHandlingAd) {
              self.handleAdEnd();
            }
          }
        }
      });

      this.observer.observe(moviePlayer, {
        attributes: true,
        attributeFilter: ['class']
      });
    },

    cleanupPlayerObserver: function() {
      if (this.observer) {
        this.observer.disconnect();
        this.observer = null;
      }
      this.isHandlingAd = false;
    },

    handleAdStart: function() {
      this.isHandlingAd = true;

      var video = document.querySelector('.video-stream') ||
                  document.querySelector('video');
      if (!video) return;

      // Save mute state
      this.userWasMuted = video.muted;

      // Mute and skip to end
      video.muted = true;
      if (video.duration && !isNaN(video.duration)) {
        video.currentTime = video.duration;
      }
      video.playbackRate = 16;

      // Click skip button
      this.clickSkipButton();
    },

    handleAdEnd: function() {
      this.isHandlingAd = false;

      var video = document.querySelector('.video-stream') ||
                  document.querySelector('video');
      if (!video) return;

      // Restore state
      video.muted = this.userWasMuted;
      video.playbackRate = 1;

      // Auto-resume
      if (video.paused) {
        video.play().catch(function() {});
      }
    },

    /**
     * Click skip button - supports both desktop and mobile selectors
     */
    clickSkipButton: function() {
      var selectors = [
        // Desktop
        '.ytp-ad-skip-button',
        '.ytp-ad-skip-button-modern',
        '.ytp-skip-ad-button',
        // Mobile
        '.ytp-ad-skip-button-slot',
        '.skipButton',
        'button[class*="skip"]'
      ];

      for (var i = 0; i < selectors.length; i++) {
        var btn = document.querySelector(selectors[i]);
        if (btn && btn.offsetParent !== null) {
          btn.click();
          return;
        }
      }

      // Retry if still in ad
      var self = this;
      if (this.isHandlingAd) {
        setTimeout(function() {
          if (self.isHandlingAd) self.clickSkipButton();
        }, 500);
      }
    },

    /**
     * Inject comprehensive CSS for ad hiding
     * Includes both desktop (ytd-*) and mobile (ytm-*) selectors
     * Uses :has() where supported, direct selectors as fallback
     */
    injectCSS: function() {
      if (document.getElementById('sg-yt-ad-blocker-css')) return;

      var style = document.createElement('style');
      style.id = 'sg-yt-ad-blocker-css';
      style.textContent = [
        // ===== VIDEO PLAYER ADS (Desktop + Mobile) =====
        '.ad-showing .video-ads,',
        '.ad-showing .ytp-ad-module,',
        '.ad-showing .ytp-ad-player-overlay,',
        '.ad-showing .ytp-ad-overlay-container,',
        '.ad-interrupting .video-ads,',
        '.ad-interrupting .ytp-ad-module,',
        '.html5-video-player.ad-showing .video-ads,',
        '.html5-video-player.ad-interrupting .video-ads {',
        '  display: none !important;',
        '  visibility: hidden !important;',
        '}',

        // ===== DESKTOP FEED ADS - PARENT CONTAINER COLLAPSE (using :has()) =====
        'ytd-rich-item-renderer:has(> #content > ytd-ad-slot-renderer),',
        'ytd-rich-item-renderer:has(> #content > ytd-in-feed-ad-layout-renderer),',
        'ytd-rich-item-renderer:has(ytd-display-ad-renderer),',
        'ytd-rich-item-renderer:has(ytd-promoted-video-renderer),',
        'ytd-rich-item-renderer:has(ytd-promoted-sparkles-web-renderer) {',
        '  display: none !important;',
        '  height: 0 !important;',
        '  min-height: 0 !important;',
        '  max-height: 0 !important;',
        '  margin: 0 !important;',
        '  padding: 0 !important;',
        '  overflow: hidden !important;',
        '}',

        // ===== DESKTOP SECTION CONTAINERS =====
        'ytd-item-section-renderer:has(ytd-ad-slot-renderer),',
        'ytd-item-section-renderer:has(ytd-in-feed-ad-layout-renderer) {',
        '  display: none !important;',
        '  height: 0 !important;',
        '  margin: 0 !important;',
        '  padding: 0 !important;',
        '}',

        // ===== DESKTOP RICH SECTION CONTAINERS =====
        'ytd-rich-section-renderer:has(ytd-ad-slot-renderer):not(:has(ytd-rich-grid-media)),',
        'ytd-rich-section-renderer:has(ytd-statement-banner-renderer) {',
        '  display: none !important;',
        '  height: 0 !important;',
        '}',

        // ===== MOBILE AD ELEMENTS (ytm-*) - Direct selectors, no :has() needed =====
        'ytm-ad-slot-renderer,',
        'ytm-promoted-video-renderer,',
        'ytm-companion-ad-renderer,',
        'ytm-in-feed-ad-layout-renderer,',
        'ytm-statement-banner-renderer,',
        'ytm-primetime-promo-renderer,',
        'ytm-display-ad-renderer,',
        'ytm-rich-item-renderer[is-ad],',
        'ytm-video-with-context-renderer[is-ad] {',
        '  display: none !important;',
        '  height: 0 !important;',
        '  min-height: 0 !important;',
        '  margin: 0 !important;',
        '  padding: 0 !important;',
        '  overflow: hidden !important;',
        '}',

        // ===== MOBILE SECTION CONTAINERS =====
        'ytm-item-section-renderer:has(ytm-ad-slot-renderer),',
        'ytm-item-section-renderer:has(ytm-promoted-video-renderer) {',
        '  display: none !important;',
        '  height: 0 !important;',
        '}',

        // ===== DESKTOP MASTHEAD / BANNER ADS =====
        'ytd-video-masthead-ad-v3-renderer,',
        'ytd-video-masthead-ad-primary-video-renderer,',
        'ytd-primetime-promo-renderer,',
        'ytd-statement-banner-renderer,',
        'ytd-banner-promo-renderer {',
        '  display: none !important;',
        '  height: 0 !important;',
        '}',

        // ===== DESKTOP SEARCH RESULT ADS =====
        'ytd-search-pyv-renderer,',
        'ytd-promoted-sparkles-text-search-renderer {',
        '  display: none !important;',
        '}',

        // ===== DESKTOP SIDEBAR / RELATED ADS =====
        'ytd-compact-promoted-video-renderer,',
        '#related ytd-promoted-sparkles-web-renderer,',
        'ytd-action-companion-ad-renderer {',
        '  display: none !important;',
        '}',

        // ===== DIRECT AD ELEMENT HIDING (fallback for both desktop & mobile) =====
        'ytd-ad-slot-renderer,',
        'ytd-in-feed-ad-layout-renderer,',
        'ytd-display-ad-renderer,',
        'ytd-promoted-video-renderer,',
        'ytd-promoted-sparkles-web-renderer,',
        'ad-badge-view-model {',
        '  display: none !important;',
        '  visibility: hidden !important;',
        '}',

        // ===== MISC AD ELEMENTS (Desktop + Mobile) =====
        '.ytd-merch-shelf-renderer,',
        '.ytd-single-option-survey-renderer,',
        '#player-ads,',
        '.ytp-ad-overlay-container,',
        '.companion-ad,',
        '.iv-promo,',
        // Mobile specific
        '.ytm-promoted-sparkles-web-renderer,',
        '.ytm-ad-renderer,',
        'ytm-player-microformat-renderer[has-ads] {',
        '  display: none !important;',
        '}',

        // ===== GRID COLLAPSE FIX (Desktop) =====
        'ytd-rich-grid-renderer {',
        '  grid-auto-rows: minmax(0, auto) !important;',
        '}',

        // ===== EMPTY SECTION CLEANUP =====
        'ytd-rich-section-renderer:empty,',
        'ytd-rich-section-renderer:has(> #content:empty),',
        'ytm-item-section-renderer:empty {',
        '  display: none !important;',
        '  height: 0 !important;',
        '}'
      ].join('\n');

      (document.head || document.documentElement).appendChild(style);
    }
  };

  // =============================================================================
  // INITIALIZATION
  // =============================================================================

  // Support both desktop and mobile YouTube domains
  var hostname = window.location.hostname;
  if (hostname.indexOf('youtube.com') !== -1 ||
      hostname.indexOf('m.youtube.com') !== -1) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function() {
        YouTubeAdBlocker.init();
      });
    } else {
      YouTubeAdBlocker.init();
    }
  }

  // Expose for debugging
  window.__SAFEGAZE_YT_AD_SKIPPER__ = YouTubeAdBlocker;

})();
