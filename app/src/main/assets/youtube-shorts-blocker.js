/**
 * SafeGaze YouTube Shorts Blocker - MAIN World Script
 * Version: 1.0.0
 *
 * This script runs in MAIN world for direct access to YouTube's JavaScript.
 * It communicates with ISOLATED world for rate limiting via postMessage.
 *
 * Features:
 * - Hiding Shorts from homepage, search results, and navigation
 * - Blocking scroll navigation within Shorts player
 * - Rate limiting shorts viewing
 * - Showing blocking overlay when limit reached
 * - Mobile pattern exposure for network-level blocking
 */
(function() {
  'use strict';

  console.log('[YouTubeShortsBlocker] ▶ SCRIPT ENTRY POINT - IIFE Started');
  console.log('[YouTubeShortsBlocker] Checking initialization flag:', window.__SAFEGAZE_YT_SHORTS_BLOCKER_INITIALIZED__);

  // Prevent multiple initialization
  if (window.__SAFEGAZE_YT_SHORTS_BLOCKER_INITIALIZED__) {
    console.log('[YouTubeShortsBlocker] ⚠ Script already initialized, skipping');
    return;
  }
  window.__SAFEGAZE_YT_SHORTS_BLOCKER_INITIALIZED__ = true;
  console.log('[YouTubeShortsBlocker] ✓ Initializing YouTube Shorts Blocker');

  const PLATFORM = 'youtube';
  const VERSION = '1.0.0';
  console.log('[YouTubeShortsBlocker] Platform:', PLATFORM, 'Version:', VERSION);

  // ========== MOBILE PATTERN EXPOSURE ==========
  window.SAFEGAZE_BLOCKED_SHORTS_PATTERNS = {
    version: VERSION,
    platform: PLATFORM,
    urlPatterns: [
      '*://www.youtube.com/shorts/*',
      '*://m.youtube.com/shorts/*',
      '*://youtube.com/shorts/*',
      '*://www.youtube.com/@*/shorts',
      '*://m.youtube.com/@*/shorts'
    ],
    domSelectors: [
      'ytd-reel-shelf-renderer',
      'ytd-reel-video-renderer',
      'ytd-shorts',
      '#shorts-container',
      'ytm-shorts-lockup-view-model',
      'ytm-reel-shelf-renderer',
      '[is-shorts]',
      'ytd-rich-shelf-renderer[is-shorts]'
    ],
    pathPatterns: ['/shorts/', '/@.*/shorts']
  };

  // ========== RATE LIMIT BRIDGE (postMessage Communication) ==========
  class RateLimitBridge {
    constructor() {
      console.log('[YouTubeShortsBlocker] RateLimitBridge: Initializing');
      this.pendingRequests = new Map();
      this.requestCounter = 0;
      this.onLimitReached = null;
      this.onLimitReset = null;
      this.onSettingsUpdate = null;

      this.setupMessageListener();
      console.log('[YouTubeShortsBlocker] RateLimitBridge: Initialized successfully');
    }

    setupMessageListener() {
      console.log('[YouTubeShortsBlocker] RateLimitBridge: Setting up message listener');
      window.addEventListener('message', (event) => {
        // Only accept messages from same window
        if (event.source !== window) return;

        const { type, requestId, result, timeRemaining, count, limit, settings, platform } = event.data;
        console.log('[YouTubeShortsBlocker] RateLimitBridge: Received message', { type, platform, requestId });

        // Filter broadcast events by platform - only handle messages for this platform
        const shouldHandleBroadcast = (
          type === 'SG_RATE_LIMIT_REACHED' ||
          type === 'SG_RATE_LIMIT_RESET' ||
          type === 'SG_RATE_LIMIT_SETTINGS_UPDATE'
        );

        if (shouldHandleBroadcast && platform !== PLATFORM) {
          // Ignore messages for other platforms
          console.log('[YouTubeShortsBlocker] RateLimitBridge: Ignoring message for different platform', platform);
          return;
        }

        if (type === 'SG_RATE_LIMIT_RESPONSE' && requestId) {
          console.log('[YouTubeShortsBlocker] RateLimitBridge: Handling rate limit response', requestId);
          const resolver = this.pendingRequests.get(requestId);
          if (resolver) {
            resolver(result);
            this.pendingRequests.delete(requestId);
          }
        } else if (type === 'SG_RATE_LIMIT_REACHED') {
          console.log('[YouTubeShortsBlocker] RateLimitBridge: Rate limit reached', { timeRemaining, count, limit });
          if (this.onLimitReached) {
            this.onLimitReached(timeRemaining, count, limit);
          }
        } else if (type === 'SG_RATE_LIMIT_RESET') {
          console.log('[YouTubeShortsBlocker] RateLimitBridge: Rate limit reset');
          if (this.onLimitReset) {
            this.onLimitReset();
          }
        } else if (type === 'SG_RATE_LIMIT_SETTINGS_UPDATE') {
          console.log('[YouTubeShortsBlocker] RateLimitBridge: Settings update', settings);
          if (this.onSettingsUpdate) {
            this.onSettingsUpdate(settings);
          }
        }
      });
    }

    sendRequest(type, data = {}) {
      console.log('[YouTubeShortsBlocker] RateLimitBridge: Sending request', { type, data });
      return new Promise((resolve) => {
        const requestId = `req_${++this.requestCounter}_${Date.now()}`;
        this.pendingRequests.set(requestId, resolve);

        window.postMessage({
          type,
          platform: PLATFORM,
          requestId,
          ...data
        }, '*');
        console.log('[YouTubeShortsBlocker] RateLimitBridge: Request sent', requestId);

        // Timeout after 5 seconds - fail open
        setTimeout(() => {
          if (this.pendingRequests.has(requestId)) {
            this.pendingRequests.delete(requestId);
            resolve({ allowed: true });
          }
        }, 5000);
      });
    }

    async canWatchShort(videoId) {
      return this.sendRequest('SG_RATE_LIMIT_CHECK', { videoId });
    }

    async recordWatch(videoId) {
      return this.sendRequest('SG_RATE_LIMIT_RECORD', { videoId });
    }

    async getStats() {
      return this.sendRequest('SG_RATE_LIMIT_GET_STATS');
    }

    async isLimitReached() {
      return this.sendRequest('SG_RATE_LIMIT_IS_REACHED');
    }

    setOnLimitReached(callback) {
      this.onLimitReached = callback;
    }

    setOnLimitReset(callback) {
      this.onLimitReset = callback;
    }

    setOnSettingsUpdate(callback) {
      this.onSettingsUpdate = callback;
    }
  }

  // ========== MAIN BLOCKER CLASS ==========
  class YouTubeShortsBlocker {
    constructor() {
      console.log('[YouTubeShortsBlocker] YouTubeShortsBlocker: Constructor called');
      console.log('[YouTubeShortsBlocker] User Agent:', navigator.userAgent);
      console.log('[YouTubeShortsBlocker] Current URL:', window.location.href);
      this.rateLimitBridge = new RateLimitBridge();
      this.styleElement = null;
      this.scrollBlockStyleElement = null;
      this.shortsHidingStyleElement = null;
      this.domObserver = null;  // Track DOM observer for dynamically loaded shorts
      this.overlayElement = null;
      this.currentShortId = null;
      this.countdownInterval = null;
      this.wheelHandler = null;
      this.keyHandler = null;
      this.touchStartHandler = null;
      this.touchMoveHandler = null;
      this.touchStartY = 0;
      this.navigationHandler = null;
      this.shortsWindowMinutes = 30; // Default value
      this.retryRemovalTimer = null;
      this.continuousMonitorInterval = null;
      this.cssWatcher = null;

      this.init();
    }

    async init() {
      console.log('[YouTubeShortsBlocker] YouTubeShortsBlocker: init() called');
      // Set up rate limiter callbacks
      this.rateLimitBridge.setOnLimitReached((timeRemaining, count, limit) => {
        console.log('[YouTubeShortsBlocker] init: Limit reached callback triggered', { timeRemaining, count, limit });
        this.enableShortsHiding();
        this.showLimitOverlay(timeRemaining, count, limit);
      });

      this.rateLimitBridge.setOnLimitReset(() => {
        console.log('[YouTubeShortsBlocker] init: Limit reset callback triggered');
        this.disableShortsHiding();
        this.hideLimitOverlay();
      });

      this.rateLimitBridge.setOnSettingsUpdate((settings) => {
        console.log('[YouTubeShortsBlocker] init: Settings update callback triggered', settings);
        if (settings && settings.shortsWindowMinutes) {
          this.shortsWindowMinutes = settings.shortsWindowMinutes;
        }
      });

      // Check initial state
      console.log('[YouTubeShortsBlocker] init: Checking initial limit state');
      const limitCheck = await this.rateLimitBridge.isLimitReached();
      console.log('[YouTubeShortsBlocker] init: Initial limit check result', limitCheck);

      // Handle both boolean and object response
      const isReached = (typeof limitCheck === 'boolean') ? limitCheck : (limitCheck && limitCheck.result);

      if (isReached) {
        console.log('[YouTubeShortsBlocker] init: Limit is reached, getting stats');
        const statsResponse = await this.rateLimitBridge.getStats();
        console.log('[YouTubeShortsBlocker] init: Stats response', statsResponse);

        // Handle both formats for stats
        const stats = (statsResponse && statsResponse.result) ? statsResponse.result : statsResponse;

        console.log('[YouTubeShortsBlocker] init: Enabling shorts hiding');
        this.enableShortsHiding();

        if (this.isOnShortsPage() && stats && stats.timeRemaining !== undefined) {
          this.showLimitOverlay(stats.timeRemaining, stats.count, stats.limit);
        }
      }

      // Inject CSS
      console.log('[YouTubeShortsBlocker] init: Injecting blocking CSS');
      this.injectBlockingCSS();

      // Handle current page
      console.log('[YouTubeShortsBlocker] init: Handling current page navigation');
      this.handleNavigation();

      // Set up navigation listener
      console.log('[YouTubeShortsBlocker] init: Setting up navigation listener');
      this.setupNavigationListener();

      // Set up scroll blocking
      console.log('[YouTubeShortsBlocker] init: Setting up scroll blockers');
      this.setupScrollBlockers();
      console.log('[YouTubeShortsBlocker] init: Initialization complete');
    }

    isOnShortsPage() {
      const path = window.location.pathname;
      const result = path.startsWith('/shorts/') || path.endsWith('/shorts');
      console.log('[YouTubeShortsBlocker] isOnShortsPage:', result, 'path:', path);
      return result;
    }

    isOnChannelShortsPage() {
      const path = window.location.pathname;
      // Match any channel URL containing /shorts but not direct shorts videos
      return path.includes('/shorts') && !path.startsWith('/shorts/');
    }

    getShortVideoId() {
      const path = window.location.pathname;
      if (path.startsWith('/shorts/')) {
        const parts = path.split('/shorts/')[1];
        if (parts) {
          return parts.split('/')[0].split('?')[0] || null;
        }
      }
      return null;
    }

    async handleNavigation() {
      console.log('[YouTubeShortsBlocker] handleNavigation: Called for URL:', window.location.href);

      // CRITICAL: Check limit status on EVERY navigation
      const limitStatus = await this.rateLimitBridge.isLimitReached();
      console.log('[YouTubeShortsBlocker] handleNavigation: Limit status', limitStatus);

      // Handle both boolean and object response formats
      let isLimitReached;
      if (typeof limitStatus === 'boolean') {
        // Bridge returned plain boolean
        isLimitReached = limitStatus;
      } else if (limitStatus && typeof limitStatus === 'object') {
        // Bridge returned object with result property
        isLimitReached = limitStatus.result;
      } else {
        // Fallback - assume not reached
        isLimitReached = false;
      }

      // Enable or disable hiding based on limit status (for ALL pages)
      if (isLimitReached) {
        console.log('[YouTubeShortsBlocker] handleNavigation: Limit reached, enabling shorts hiding');
        this.enableShortsHiding();

        // CRITICAL: Force restart continuous monitoring on navigation
        // This ensures monitoring works after SPA navigation
        this.setupContinuousMonitoring();

        // CRITICAL: Force immediate sweep on channel pages
        const isChannelPage = window.location.pathname.includes('/@') ||
          window.location.pathname.includes('/c/') ||
          window.location.pathname.includes('/channel/');

        if (isChannelPage) {
          // Immediate sweep
          this.performShortsSweep();

          // Additional sweeps for late-loading content
          setTimeout(() => this.performShortsSweep(), 100);
          setTimeout(() => this.performShortsSweep(), 300);
          setTimeout(() => this.performShortsSweep(), 600);
          setTimeout(() => this.performShortsSweep(), 1000);
        }
      } else {
        console.log('[YouTubeShortsBlocker] handleNavigation: Limit not reached, disabling shorts hiding');
        this.disableShortsHiding();
      }

      // Handle shorts player pages specifically
      if (this.isOnShortsPage()) {
        console.log('[YouTubeShortsBlocker] handleNavigation: On shorts page');
        if (this.isOnChannelShortsPage()) {
          // Channel shorts tab - show overlay
          if (isLimitReached) {
            const stats = await this.rateLimitBridge.getStats();

            // Handle both formats
            const statsData = (stats && stats.result) ? stats.result : stats;

            if (statsData && statsData.timeRemaining !== undefined) {
              this.showLimitOverlay(statsData.timeRemaining, statsData.count, statsData.limit);
            } else {
              console.error('[SafeGaze] ❌ Failed to get stats for overlay', stats);
            }
          }
          return;
        }

        // Direct shorts video - record watch and enable scroll blocking
        const videoId = this.getShortVideoId();
        if (!videoId) return;

        if (videoId !== this.currentShortId) {
          this.currentShortId = videoId;

          try {
            const checkResult = await this.rateLimitBridge.canWatchShort(videoId);
            if (!checkResult.allowed) {
              this.showLimitOverlay(checkResult.timeRemaining, checkResult.count, checkResult.limit);
              this.enableScrollBlocking();
              return;
            }

            await this.rateLimitBridge.recordWatch(videoId);
          } catch (error) {
            console.error('[SafeGaze] Error recording watch:', error);
          }
        }

        this.enableScrollBlocking();
      } else {
        // Not on shorts page - disable scroll blocking
        this.disableScrollBlocking();
        this.hideLimitOverlay();
        this.currentShortId = null;
      }
    }

    injectBlockingCSS() {
      console.log('[YouTubeShortsBlocker] injectBlockingCSS: Called');
      if (this.styleElement) {
        console.log('[YouTubeShortsBlocker] injectBlockingCSS: Removing existing style element');
        this.styleElement.remove();
      }

      const style = document.createElement('style');
      style.id = 'sg-shorts-blocker-styles';
      style.textContent = `
        /* ===== SAFEGAZE SHORTS BLOCKER ===== */
        /* NOTE: Shorts remain visible so users can click and watch them.
           Scroll blocking is handled separately in enableScrollBlocking().
           Rate limiting shows overlay when limit is reached. */
      `;

      const injectStyle = () => {
        const target = document.head || document.documentElement;
        console.log('[YouTubeShortsBlocker] injectBlockingCSS: Target element', target ? target.tagName : 'null');
        if (target && !document.getElementById('sg-shorts-blocker-styles')) {
          target.appendChild(style);
          this.styleElement = style;
          console.log('[YouTubeShortsBlocker] injectBlockingCSS: Style element injected successfully');
        }
      };

      if (document.head) {
        console.log('[YouTubeShortsBlocker] injectBlockingCSS: document.head exists, injecting now');
        injectStyle();
      } else {
        console.log('[YouTubeShortsBlocker] injectBlockingCSS: document.head not ready, waiting');
        const observer = new MutationObserver(() => {
          if (document.head) {
            injectStyle();
            observer.disconnect();
          }
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
      }
    }

    enableScrollBlocking() {
      console.log('[YouTubeShortsBlocker] enableScrollBlocking: Called');
      if (this.scrollBlockStyleElement) {
        console.log('[YouTubeShortsBlocker] enableScrollBlocking: Already enabled, skipping');
        return;
      }

      const style = document.createElement('style');
      style.id = 'sg-shorts-scroll-block-styles';
      style.textContent = `
        /* ===== SCROLL BLOCKING FOR SHORTS PAGE ===== */

        body.sg-shorts-scroll-blocked #shorts-container,
        body.sg-shorts-scroll-blocked ytd-shorts,
        body.sg-shorts-scroll-blocked .ytd-shorts {
          overflow: hidden !important;
          scroll-snap-type: none !important;
          touch-action: none !important;
          overscroll-behavior: none !important;
        }

        body.sg-shorts-scroll-blocked #navigation-button-down,
        body.sg-shorts-scroll-blocked #navigation-button-up,
        body.sg-shorts-scroll-blocked .navigation-button,
        body.sg-shorts-scroll-blocked ytd-shorts #navigation-button-down,
        body.sg-shorts-scroll-blocked ytd-shorts #navigation-button-up,
        body.sg-shorts-scroll-blocked [id*="navigation-button"] {
          display: none !important;
          visibility: hidden !important;
          pointer-events: none !important;
        }

        /* Hide navigation buttons - aria-label selectors (comprehensive) */
        body.sg-shorts-scroll-blocked [aria-label*="Next"],
        body.sg-shorts-scroll-blocked [aria-label*="next"],
        body.sg-shorts-scroll-blocked [aria-label*="Previous"],
        body.sg-shorts-scroll-blocked [aria-label*="previous"],
        body.sg-shorts-scroll-blocked [aria-label*="Scroll"],
        body.sg-shorts-scroll-blocked [aria-label*="scroll"],
        body.sg-shorts-scroll-blocked [aria-label*="Up"],
        body.sg-shorts-scroll-blocked [aria-label*="up"],
        body.sg-shorts-scroll-blocked [aria-label*="Down"],
        body.sg-shorts-scroll-blocked [aria-label*="down"] {
          display: none !important;
          visibility: hidden !important;
          pointer-events: none !important;
        }

        body.sg-shorts-scroll-blocked ytd-reel-video-renderer {
          scroll-snap-align: none !important;
        }

        body.sg-shorts-scroll-blocked #shorts-inner-container {
          overflow: hidden !important;
        }

        body.sg-shorts-scroll-blocked .reel-player-overlay-actions,
        body.sg-shorts-scroll-blocked ytd-reel-player-header-renderer [aria-label*="swipe"] {
          display: none !important;
        }
      `;

      const target = document.head || document.documentElement;
      target.appendChild(style);
      this.scrollBlockStyleElement = style;

      document.body.classList.add('sg-shorts-scroll-blocked');
      console.log('[YouTubeShortsBlocker] enableScrollBlocking: Scroll blocking enabled');
    }

    disableScrollBlocking() {
      console.log('[YouTubeShortsBlocker] disableScrollBlocking: Called');
      if (this.scrollBlockStyleElement) {
        this.scrollBlockStyleElement.remove();
        this.scrollBlockStyleElement = null;
        console.log('[YouTubeShortsBlocker] disableScrollBlocking: Style element removed');
      }

      document.body.classList.remove('sg-shorts-scroll-blocked');
      console.log('[YouTubeShortsBlocker] disableScrollBlocking: Scroll blocking disabled');
    }

    enableShortsHiding() {
      console.log('[YouTubeShortsBlocker] enableShortsHiding: Called');

      // CRITICAL: Always sweep for existing shorts on every call (new page may have them)
      // This must run BEFORE the early return check
      this.removeExistingShorts();

      // Ensure DOM observer is active (idempotent - won't create duplicates)
      this.setupDOMObserver();

      // Schedule retry for late-loading elements
      this.scheduleRetryRemoval();

      // Check if CSS is already injected to avoid duplicates
      if (this.shortsHidingStyleElement && this.shortsHidingStyleElement.parentNode) {
        console.log('[YouTubeShortsBlocker] enableShortsHiding: CSS already injected, skipping reinjection');
        return;  // CSS already injected, no need to reinject
      }

      console.log('[YouTubeShortsBlocker] enableShortsHiding: Creating and injecting hiding CSS');
      const style = document.createElement('style');
      style.id = 'sg-shorts-hiding-styles';
      style.textContent = `
        /* ===== HIDE SHORTS WHEN LIMIT REACHED ===== */

        ytd-reel-shelf-renderer {
          display: none !important;
        }

        ytd-shorts-lockup-view-model,
        ytd-shorts-lockup-view-model-v2,
        ytd-video-renderer[is-shorts],
        ytd-video-renderer[is-short],
        ytd-rich-item-renderer:has([href^="/shorts/"]),
        ytd-rich-item-renderer:has(ytd-shorts-lockup-view-model),
        ytd-grid-video-renderer:has([href^="/shorts/"]) {
          display: none !important;
        }

        ytd-video-renderer[overlay-style="SHORTS"],
        ytd-video-renderer:has([overlay-style="SHORTS"]),
        ytd-video-renderer:has(a[href^="/shorts/"]) {
          display: none !important;
        }

        ytd-guide-entry-renderer:has(a[href="/shorts"]),
        ytd-mini-guide-entry-renderer:has([title="Shorts"]) {
          display: none !important;
        }

        ytd-browse:not([page-subtype="channels"]) yt-chip-cloud-chip-renderer[chip-text="Shorts"],
        ytd-browse:not([page-subtype="channels"]) yt-chip-cloud-chip-renderer:has([aria-label*="Shorts"]) {
          display: none !important;
        }

        /* ===== ADDITIONAL COMPREHENSIVE SELECTORS ===== */

        /* Rich grid items containing shorts */
        ytd-rich-item-renderer:has(ytd-shorts-lockup-view-model-v2),
        ytd-rich-item-renderer:has(ytd-reel-item-renderer),
        ytd-rich-grid-renderer ytd-rich-item-renderer:has([href^="/shorts/"]) {
          display: none !important;
        }

        /* Grid shorts (channel pages, search) */
        ytd-grid-video-renderer:has([href^="/shorts/"]),
        ytd-grid-renderer ytd-grid-video-renderer:has(a[href^="/shorts/"]) {
          display: none !important;
        }

        /* Item section shorts (subscriptions feed) */
        ytd-item-section-renderer:has(ytd-reel-shelf-renderer),
        ytd-rich-section-renderer:has([is-shorts]) {
          display: none !important;
        }

        /* Channel shorts tab content - ENHANCED */
        ytd-grid-renderer[is-shorts-grid],
        ytd-grid-renderer[is-shorts-grid] ytd-grid-video-renderer {
          display: none !important;
        }

        ytd-browse[page-subtype="channels"] ytd-grid-video-renderer:has([href^="/shorts/"]),
        ytd-browse[page-subtype="channels"] ytd-grid-video-renderer:has(a[href^="/shorts/"]) {
          display: none !important;
        }

        /* Search result shorts (enhanced) */
        ytd-search ytd-video-renderer:has(a[href^="/shorts/"]) {
          display: none !important;
        }

        /* Shorts shelf in subscription feed */
        ytd-rich-shelf-renderer:has(ytd-reel-shelf-renderer),
        ytd-rich-shelf-renderer[is-shorts] {
          display: none !important;
        }

        /* Subscription feed shorts */
        ytd-browse[page-subtype="subscriptions"] ytd-grid-video-renderer:has([href^="/shorts/"]),
        ytd-browse[page-subtype="subscriptions"] ytd-rich-item-renderer:has([href^="/shorts/"]) {
          display: none !important;
        }

        /* Home feed shorts */
        ytd-browse[page-subtype="home"] ytd-grid-video-renderer:has([href^="/shorts/"]),
        ytd-browse[page-subtype="home"] ytd-rich-item-renderer:has([href^="/shorts/"]) {
          display: none !important;
        }

        /* Trending feed shorts */
        ytd-browse[page-subtype="trending"] ytd-grid-video-renderer:has([href^="/shorts/"]),
        ytd-browse[page-subtype="trending"] ytd-rich-item-renderer:has([href^="/shorts/"]) {
          display: none !important;
        }

        /* Two-column layouts (search results, channel pages) */
        ytd-two-column-browse-results-renderer ytd-video-renderer:has([href^="/shorts/"]),
        ytd-two-column-browse-results-renderer ytd-grid-video-renderer:has([href^="/shorts/"]) {
          display: none !important;
        }

        /* Section containers with shorts */
        ytd-item-section-renderer:has(ytd-grid-video-renderer a[href^="/shorts/"]),
        ytd-item-section-renderer:has(ytd-video-renderer[href^="/shorts/"]) {
          display: none !important;
        }

        /* Rich sections in feeds */
        ytd-rich-section-renderer:has(ytd-rich-item-renderer [href^="/shorts/"]) {
          display: none !important;
        }

        /* Mobile YouTube */
        ytm-shorts-lockup-view-model,
        ytm-reel-shelf-renderer,
        ytm-item-section-renderer:has([href^="/shorts/"]) {
          display: none !important;
        }

        /* ===== CHANNEL SHORTS TAB - TAB NAVIGATION ===== */
        /* [REMOVED - Let users see and click channel tabs including Shorts tab] */

        /* Hide shelf items containing shorts on channels */
        ytd-browse[page-subtype="channels"] ytd-shelf-renderer:has([href^="/shorts/"]),
        ytd-browse[page-subtype="channels"] ytd-item-section-renderer:has(ytd-grid-video-renderer a[href^="/shorts/"]),
        ytd-browse[page-subtype="channels"] ytd-rich-section-renderer:has(ytd-rich-item-renderer [href^="/shorts/"]) {
          display: none !important;
        }

        /* ===== SEARCH RESULTS - COMPREHENSIVE COVERAGE ===== */

        /* Grid items in search */
        ytd-search ytd-grid-video-renderer:has([href^="/shorts/"]),
        ytd-search ytd-grid-video-renderer[overlay-style="SHORTS"],

        /* Rich items in search */
        ytd-search ytd-rich-item-renderer:has([href^="/shorts/"]),
        ytd-search ytd-rich-item-renderer:has([overlay-style="SHORTS"]),

        /* Reel shelves in search */
        ytd-search ytd-reel-shelf-renderer,
        ytd-search ytd-reel-video-renderer,

        /* Primary search contents container */
        ytd-search-primary-contents ytd-video-renderer[overlay-style="SHORTS"],
        ytd-search-primary-contents ytd-grid-video-renderer[overlay-style="SHORTS"],

        /* Two-column search layout */
        ytd-two-column-search-results-renderer ytd-video-renderer[overlay-style="SHORTS"],
        ytd-two-column-search-results-renderer ytd-grid-video-renderer:has([href^="/shorts/"]),
        ytd-two-column-search-results-renderer ytd-rich-item-renderer:has([href^="/shorts/"]),

        /* Item section renderers in search - hide items not sections */
        ytd-search ytd-item-section-renderer ytd-grid-video-renderer:has(a[href^="/shorts/"]),
        ytd-search ytd-item-section-renderer ytd-video-renderer[overlay-style="SHORTS"],

        /* Section list renderer (older YouTube layouts) */
        ytd-section-list-renderer ytd-video-renderer:has([href^="/shorts/"]),
        ytd-section-list-renderer ytd-grid-video-renderer:has([href^="/shorts/"]),

        /* ===== AGGRESSIVE CHANNEL PAGE HIDING ===== */

        /* Channel page - all grid video renderers with shorts */
        ytd-browse ytd-grid-video-renderer:has(a[href^="/shorts/"]),
        ytd-browse ytd-grid-video-renderer:has([overlay-style="SHORTS"]),

        /* Channel rich items with shorts */
        ytd-browse ytd-rich-item-renderer:has(a[href^="/shorts/"]),
        ytd-browse ytd-rich-item-renderer:has([overlay-style="SHORTS"]),

        /* Channel shelves containing shorts */
        ytd-browse ytd-shelf-renderer:has(a[href^="/shorts/"]),

        /* Expanded shelf renderers (channel shorts section) */
        ytd-browse ytd-expanded-shelf-contents-renderer:has(a[href^="/shorts/"]),

        /* Grid containers specifically on browse pages */
        ytd-browse ytd-grid-renderer:has(ytd-grid-video-renderer a[href^="/shorts/"]),

        /* Section list on channel pages */
        ytd-browse ytd-section-list-renderer:has(a[href^="/shorts/"]),

        /* ===== FALLBACK SELECTORS (No :has() dependency) ===== */

        /* Direct attribute matching on browse pages */
        ytd-browse ytd-grid-video-renderer[is-shorts],
        ytd-browse ytd-grid-video-renderer[is-short],
        ytd-browse ytd-rich-item-renderer[is-shorts],
        ytd-browse ytd-rich-item-renderer[is-short],

        /* Overlay style attribute on browse pages */
        ytd-browse [overlay-style="SHORTS"],

        /* Reel-specific elements on browse pages */
        ytd-browse ytd-reel-shelf-renderer,
        ytd-browse ytd-reel-video-renderer,
        ytd-browse ytd-reel-item-renderer,

        /* Fallback - direct attribute matching without :has() */
        ytd-search ytd-video-renderer[is-shorts],
        ytd-search ytd-video-renderer[is-short],
        ytd-search ytd-grid-video-renderer[is-shorts],
        ytd-search ytd-grid-video-renderer[is-short] {
          display: none !important;
        }
      `;

      const target = document.head || document.documentElement;
      target.appendChild(style);
      this.shortsHidingStyleElement = style;
      console.log('[YouTubeShortsBlocker] enableShortsHiding: Hiding CSS injected successfully');

      // CRITICAL: Immediately verify CSS is working
      setTimeout(() => {
        // Check if shorts are still visible after CSS injection
        const visibleShorts = this.findShortsElements(document.body);
        if (visibleShorts.length > 0) {
          this.performShortsSweep();
        }

        // Validate CSS rendering on actual elements
        const testShorts = document.querySelector('ytd-grid-video-renderer a[href^="/shorts/"]');
        if (testShorts) {
          const renderer = testShorts.closest('ytd-grid-video-renderer');
          if (renderer) {
            const styles = window.getComputedStyle(renderer);

            if (styles.display !== 'none') {
              console.error('[SafeGaze] CSS hiding FAILED - check selectors or browser support');
            } else {
              console.log('[SafeGaze] ✅ CSS hiding working correctly');
            }
          }
        }
      }, 200);

      // Ensure CSS persists - re-inject if removed
      if (this.cssWatcher) {
        clearInterval(this.cssWatcher);
      }

      this.cssWatcher = setInterval(() => {
        if (this.shortsHidingStyleElement && !document.contains(this.shortsHidingStyleElement)) {
          const target = document.head || document.documentElement;
          target.appendChild(this.shortsHidingStyleElement);
        }
      }, 1000);

      // Start continuous monitoring for channel/search pages
      this.setupContinuousMonitoring();
    }

    disableShortsHiding() {
      console.log('[YouTubeShortsBlocker] disableShortsHiding: Called');
      if (this.shortsHidingStyleElement) {
        // Only try to remove if still in DOM
        if (this.shortsHidingStyleElement.parentNode) {
          this.shortsHidingStyleElement.remove();
          console.log('[YouTubeShortsBlocker] disableShortsHiding: Hiding CSS removed');
        }
        this.shortsHidingStyleElement = null;
      }

      // Disconnect DOM observer when limit resets
      this.disconnectDOMObserver();

      // Stop continuous monitoring
      this.teardownContinuousMonitoring();

      // Stop CSS persistence watcher
      if (this.cssWatcher) {
        clearInterval(this.cssWatcher);
        this.cssWatcher = null;
      }
    }

    disconnectDOMObserver() {
      if (this.domObserver) {
        this.domObserver.disconnect();
        this.domObserver = null;
      }
    }

    /**
     * Remove shorts that already exist in the DOM
     * Called when enabling hiding to clean up before observer starts
     */
    removeExistingShorts() {
      console.log('[YouTubeShortsBlocker] removeExistingShorts: Called');

      // Immediate sweep
      this.performShortsSweep();

      // Multiple delayed sweeps for async content
      setTimeout(() => this.performShortsSweep(), 100);
      setTimeout(() => this.performShortsSweep(), 300);
      setTimeout(() => this.performShortsSweep(), 600);
      setTimeout(() => this.performShortsSweep(), 1200);
      setTimeout(() => this.performShortsSweep(), 2500);
    }

    performShortsSweep() {
      console.log('[YouTubeShortsBlocker] performShortsSweep: Starting sweep');
      const existingShorts = this.findShortsElements(document.body);
      console.log('[YouTubeShortsBlocker] performShortsSweep: Found', existingShorts.length, 'shorts elements');
      let removedCount = 0;
      const removedElements = new Set();  // Track what we've removed

      existingShorts.forEach(el => {
        try {
          // Skip if already removed
          if (removedElements.has(el) || !el.isConnected) {
            return;
          }

          // Try to remove parent containers (most specific to least specific)
          let parent = el.closest('ytd-rich-item-renderer, ytd-grid-video-renderer, ytd-video-renderer, ytd-reel-item-renderer, ytd-item-section-renderer, ytd-rich-section-renderer, ytd-rich-shelf-renderer');

          // If no parent found, try going up one level and check again
          if (!parent || parent === el) {
            parent = el.parentElement?.closest('ytd-rich-item-renderer, ytd-grid-video-renderer, ytd-video-renderer');
          }

          if (parent && parent !== el && parent.isConnected) {
            parent.remove();
            removedElements.add(parent);
            removedElements.add(el);
            removedCount++;
          } else if (el.isConnected) {
            el.remove();
            removedElements.add(el);
            removedCount++;
          }
        } catch (e) {
          // Silently ignore errors
        }
      });

      // Also remove entire grid renderers that are marked as shorts grids
      try {
        const shortsGrids = document.querySelectorAll('ytd-grid-renderer[is-shorts-grid]');
        shortsGrids.forEach(grid => {
          if (!removedElements.has(grid) && grid.isConnected) {
            grid.remove();
            removedElements.add(grid);
            removedCount++;
          }
        });
      } catch (e) {
        // Silently ignore errors
      }

      if (removedCount > 0) {
        console.log(`[SafeGaze] Removed ${removedCount} shorts in sweep`);
      } else {
        console.log('[YouTubeShortsBlocker] performShortsSweep: No shorts removed in this sweep');
      }
    }

    /**
     * Retry removal with delay to catch late-loading elements
     */
    scheduleRetryRemoval() {
      // Clear any existing retry timer
      if (this.retryRemovalTimer) {
        clearTimeout(this.retryRemovalTimer);
      }

      // Retry after 500ms to catch elements that load after navigation
      this.retryRemovalTimer = setTimeout(() => {
        this.removeExistingShorts();
        this.retryRemovalTimer = null;
      }, 500);
    }

    setupDOMObserver() {
      console.log('[YouTubeShortsBlocker] setupDOMObserver: Called');
      // Disconnect any existing observer first (critical for SPA navigation)
      if (this.domObserver) {
        console.log('[YouTubeShortsBlocker] setupDOMObserver: Disconnecting existing observer');
        this.domObserver.disconnect();
        this.domObserver = null;
      }

      // Target main content container
      const targetNode = document.querySelector('ytd-app');
      console.log('[YouTubeShortsBlocker] setupDOMObserver: Target node', targetNode ? 'found' : 'not found');

      if (!targetNode) {
        console.log('[YouTubeShortsBlocker] setupDOMObserver: ytd-app not found, retrying in 100ms');
        // Retry after a short delay
        setTimeout(() => this.setupDOMObserver(), 100);
        return;
      }

      // Create observer to watch for new Shorts elements
      console.log('[YouTubeShortsBlocker] setupDOMObserver: Creating MutationObserver');
      this.domObserver = new MutationObserver((mutations) => {
        console.log('[YouTubeShortsBlocker] MutationObserver: Detected', mutations.length, 'mutations');
        let shortsFound = false;

        for (const mutation of mutations) {
          // Handle childList mutations (new elements added)
          if (mutation.type === 'childList') {
            mutation.addedNodes.forEach((node) => {
              if (node instanceof HTMLElement) {
                // Check if the added node or its children contain Shorts
                if (this.isShortsElement(node)) {
                  shortsFound = true;

                  // Remove parent container if it's a rich-item wrapper
                  const parent = node.closest('ytd-rich-item-renderer');
                  if (parent && parent !== node) {
                    parent.remove();
                  } else {
                    node.remove();
                  }
                }

                // Also check children
                const shortsChildren = this.findShortsElements(node);
                if (shortsChildren.length > 0) {
                  shortsFound = true;
                  shortsChildren.forEach(el => {
                    const parent = el.closest('ytd-rich-item-renderer');
                    if (parent && parent !== el) {
                      parent.remove();
                    } else {
                      el.remove();
                    }
                  });
                }
              }
            });
          }

          // Handle attribute mutations (is-shorts added dynamically)
          if (mutation.type === 'attributes' && mutation.target instanceof HTMLElement) {
            const target = mutation.target;
            if (this.isShortsElement(target)) {
              shortsFound = true;
              const parent = target.closest('ytd-rich-item-renderer');
              if (parent && parent !== target) {
                parent.remove();
              } else {
                target.remove();
              }
            }
          }
        }

        if (shortsFound) {
          console.log('[SafeGaze] Removed Shorts from dynamically loaded content');
        }
      });

      // Start observing
      this.domObserver.observe(targetNode, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['is-shorts', 'is-short', 'overlay-style', 'href']
      });
      console.log('[YouTubeShortsBlocker] setupDOMObserver: MutationObserver started');
    }

    setupContinuousMonitoring() {
      // ALWAYS clear existing monitor first
      if (this.continuousMonitorInterval) {
        clearInterval(this.continuousMonitorInterval);
        this.continuousMonitorInterval = null;
      }

      // Enable on ALL pages when shorts hiding is active
      if (this.shortsHidingStyleElement) {
        const path = window.location.pathname;

        // Adaptive interval based on page type
        let intervalMs = 800; // Default

        // More aggressive on problematic pages
        if (path.includes('/shorts') || path.startsWith('/results')) {
          intervalMs = 300; // 3x faster for channel shorts tab and search
        } else {
          console.log('[SafeGaze] Starting continuous monitoring (800ms) for', path);
        }

        // Start monitoring with adaptive interval
        this.continuousMonitorInterval = setInterval(() => {
          this.performShortsSweep();
        }, intervalMs);
      }
    }

    teardownContinuousMonitoring() {
      if (this.continuousMonitorInterval) {
        clearInterval(this.continuousMonitorInterval);
        this.continuousMonitorInterval = null;
      }
    }

    isShortsElement(element) {
      if (!element || !(element instanceof HTMLElement)) {
        return false;
      }
      console.log('[YouTubeShortsBlocker] isShortsElement: Checking element', element.tagName);

      // Direct shorts element types
      const shortsTypes = [
        'ytd-reel-shelf-renderer',
        'ytd-reel-video-renderer',
        'ytd-reel-item-renderer',
        'ytd-shorts-lockup-view-model',
        'ytd-shorts-lockup-view-model-v2',
        'ytm-shorts-lockup-view-model',
        'ytm-reel-shelf-renderer',
        'ytm-reel-item-renderer'
      ];

      const tagName = element.tagName.toLowerCase();
      if (shortsTypes.includes(tagName)) {
        return true;
      }

      // Check for shorts-specific attributes
      if (element.hasAttribute('is-shorts') ||
        element.hasAttribute('is-short') ||
        element.getAttribute('overlay-style') === 'SHORTS') {
        return true;
      }

      // Check for grid renderer marked as shorts grid
      if (tagName === 'ytd-grid-renderer' &&
        element.hasAttribute('is-shorts-grid')) {
        return true;
      }

      // Check if element contains a shorts link
      const shortsLink = element.querySelector('a[href^="/shorts/"]');
      if (shortsLink) {
        return true;
      }

      // Check if element itself is a shorts link
      if (tagName === 'a' && element.getAttribute('href')?.startsWith('/shorts/')) {
        return true;
      }

      return false;
    }

    findShortsElements(container) {
      console.log('[YouTubeShortsBlocker] findShortsElements: Searching in container', container.tagName);
      // Use Set to prevent duplicates
      const shortsElementsSet = new Set();

      // Base selectors that work everywhere
      const baseSelectors = [
        'ytd-reel-shelf-renderer',
        'ytd-reel-video-renderer',
        'ytd-reel-item-renderer',
        'ytd-shorts-lockup-view-model',
        'ytd-shorts-lockup-view-model-v2',
        'ytd-video-renderer[is-shorts]',
        'ytd-video-renderer[is-short]',
        '[overlay-style="SHORTS"]',
        'ytd-grid-renderer[is-shorts-grid]',
        'a[href^="/shorts/"]'
      ];

      // Context-specific selectors
      const path = window.location.pathname;
      let contextSelectors = [];

      // Search results context
      if (path.startsWith('/results')) {
        contextSelectors = [
          'ytd-search ytd-grid-video-renderer',
          'ytd-search ytd-video-renderer',
          'ytd-search ytd-rich-item-renderer',
          'ytd-search-primary-contents ytd-video-renderer',
          'ytd-two-column-search-results-renderer ytd-video-renderer',
          'ytd-item-section-renderer ytd-grid-video-renderer',
          'ytd-section-list-renderer ytd-video-renderer'
        ];
      }

      // Channel context
      else if (path.includes('/@') || path.includes('/c/') || path.includes('/channel/')) {
        contextSelectors = [
          'ytd-browse[page-subtype="channels"] ytd-grid-video-renderer',
          'ytd-browse[page-subtype="channels"] ytd-rich-item-renderer',
          'ytd-browse[page-subtype="channels"] ytd-shelf-renderer',
          'ytd-browse[page-subtype="channels"] ytd-item-section-renderer',
          'yt-chip-cloud-chip-renderer',
          'ytd-tab-renderer'
        ];
      }

      // Combine all selectors
      const allSelectors = [...baseSelectors, ...contextSelectors];

      // Add elements from direct selectors
      allSelectors.forEach(selector => {
        try {
          const elements = container.querySelectorAll(selector);
          elements.forEach(el => {
            if (el instanceof HTMLElement) {
              // Additional validation for context selectors
              if (contextSelectors.includes(selector)) {
                // Check if this element actually contains shorts
                const shortsLink = el.querySelector('a[href^="/shorts/"]');
                const shortsOverlay = el.querySelector('[overlay-style="SHORTS"]');
                const shortsAttr = el.hasAttribute('is-shorts') || el.hasAttribute('is-short');

                if (shortsLink || shortsOverlay || shortsAttr) {
                  shortsElementsSet.add(el);
                }
              } else {
                // Base selectors are already specific enough
                shortsElementsSet.add(el);
              }
            }
          });
        } catch (e) {
          // Silently ignore selector errors
        }
      });

      // Two-step matching for containers with shorts links
      try {
        // Find all grid video renderers
        const gridRenderers = container.querySelectorAll('ytd-grid-video-renderer');
        gridRenderers.forEach(renderer => {
          const shortsLink = renderer.querySelector('a[href^="/shorts/"]');
          if (shortsLink) {
            shortsElementsSet.add(renderer);
          }
        });

        // Find all rich item renderers
        const richItems = container.querySelectorAll('ytd-rich-item-renderer');
        richItems.forEach(item => {
          const shortsLink = item.querySelector('a[href^="/shorts/"]');
          if (shortsLink) {
            shortsElementsSet.add(item);
          }
        });

        // Find all video renderers
        const videoRenderers = container.querySelectorAll('ytd-video-renderer');
        videoRenderers.forEach(renderer => {
          const shortsLink = renderer.querySelector('a[href^="/shorts/"]');
          const shortsOverlay = renderer.hasAttribute('overlay-style') && renderer.getAttribute('overlay-style') === 'SHORTS';
          if (shortsLink || shortsOverlay) {
            shortsElementsSet.add(renderer);
          }
        });

        // Find all shelf renderers that might contain shorts
        const shelfRenderers = container.querySelectorAll('ytd-shelf-renderer');
        shelfRenderers.forEach(shelf => {
          const shortsLink = shelf.querySelector('a[href^="/shorts/"]');
          if (shortsLink) {
            shortsElementsSet.add(shelf);
          }
        });
      } catch (e) {
        // Silently ignore errors
      }

      const result = Array.from(shortsElementsSet);
      console.log('[YouTubeShortsBlocker] findShortsElements: Found', result.length, 'shorts elements');
      return result;
    }

    setupScrollBlockers() {
      console.log('[YouTubeShortsBlocker] setupScrollBlockers: Setting up scroll event handlers');
      this.wheelHandler = (e) => {
        if (this.isOnShortsPage()) {
          const shortsContainer = document.querySelector('#shorts-container, ytd-shorts');
          if (shortsContainer && (shortsContainer.contains(e.target) || e.target === shortsContainer)) {
            e.preventDefault();
            e.stopPropagation();
          }
        }
      };

      this.keyHandler = (e) => {
        if (this.isOnShortsPage()) {
          const blockedKeys = ['ArrowUp', 'ArrowDown', 'j', 'k', 'J', 'K'];
          if (blockedKeys.includes(e.key)) {
            e.preventDefault();
            e.stopPropagation();
          }
        }
      };

      this.touchStartHandler = (e) => {
        this.touchStartY = e.touches[0].clientY;
      };

      this.touchMoveHandler = (e) => {
        if (this.isOnShortsPage()) {
          const shortsContainer = document.querySelector('#shorts-container, ytd-shorts');
          if (shortsContainer && (shortsContainer.contains(e.target) || e.target === shortsContainer)) {
            const deltaY = Math.abs(e.touches[0].clientY - this.touchStartY);
            if (deltaY > 10) {
              e.preventDefault();
            }
          }
        }
      };

      document.addEventListener('wheel', this.wheelHandler, { capture: true, passive: false });
      document.addEventListener('keydown', this.keyHandler, { capture: true });
      document.addEventListener('touchstart', this.touchStartHandler, { capture: true, passive: true });
      document.addEventListener('touchmove', this.touchMoveHandler, { capture: true, passive: false });
      console.log('[YouTubeShortsBlocker] setupScrollBlockers: Event handlers registered');
    }

    showLimitOverlay(timeRemaining, count, limit) {
      console.log('[YouTubeShortsBlocker] showLimitOverlay: Called', { timeRemaining, count, limit });
      this.hideLimitOverlay();

      // Create overlay container
      const overlay = document.createElement('div');
      overlay.id = 'sg-shorts-limit-overlay';

      // Create and append style element
      const style = document.createElement('style');
      style.textContent = `
        #sg-shorts-limit-overlay {
          position: fixed !important;
          top: 0 !important;
          left: 0 !important;
          width: 100vw !important;
          height: 100vh !important;
          background: rgba(0, 0, 0, 0.95) !important;
          z-index: 999999 !important;
          display: flex !important;
          align-items: center !important;
          justify-content: center !important;
          backdrop-filter: blur(10px) !important;
          font-family: 'Roboto', 'Segoe UI', system-ui, sans-serif !important;
        }

        .sg-overlay-content {
          background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%) !important;
          border-radius: 16px !important;
          padding: 40px !important;
          max-width: 400px !important;
          text-align: center !important;
          box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5) !important;
          border: 1px solid rgba(148, 163, 184, 0.1) !important;
        }

        .sg-overlay-icon {
          width: 80px !important;
          height: 80px !important;
          margin: 0 auto 24px !important;
          background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%) !important;
          border-radius: 50% !important;
          display: flex !important;
          align-items: center !important;
          justify-content: center !important;
          color: white !important;
        }

        .sg-overlay-icon svg {
          width: 40px !important;
          height: 40px !important;
        }

        .sg-overlay-title {
          color: #f1f5f9 !important;
          font-size: 24px !important;
          font-weight: 700 !important;
          margin: 0 0 16px !important;
        }

        .sg-overlay-message {
          color: #94a3b8 !important;
          font-size: 16px !important;
          line-height: 1.6 !important;
          margin: 0 0 24px !important;
        }

        #sg-shorts-count, #sg-reset-timer {
          color: #f1f5f9 !important;
          font-weight: 600 !important;
        }

        .sg-overlay-progress {
          height: 8px !important;
          background: #334155 !important;
          border-radius: 4px !important;
          overflow: hidden !important;
          margin-bottom: 24px !important;
        }

        .sg-progress-bar {
          height: 100% !important;
          background: linear-gradient(90deg, #10b981 0%, #059669 100%) !important;
          border-radius: 4px !important;
          transition: width 1s linear !important;
          width: 0% !important;
        }

        .sg-overlay-back-btn {
          background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%) !important;
          color: white !important;
          border: none !important;
          padding: 12px 32px !important;
          border-radius: 8px !important;
          font-size: 16px !important;
          font-weight: 600 !important;
          cursor: pointer !important;
          transition: transform 0.2s, box-shadow 0.2s !important;
        }

        .sg-overlay-back-btn:hover {
          transform: translateY(-2px) !important;
          box-shadow: 0 10px 20px rgba(59, 130, 246, 0.3) !important;
        }
      `;
      overlay.appendChild(style);

      // Create content container
      const content = document.createElement('div');
      content.className = 'sg-overlay-content';

      // Create icon container
      const icon = document.createElement('div');
      icon.className = 'sg-overlay-icon';

      // Create SVG icon using createElementNS (CSP-safe)
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
      svg.setAttribute('width', '40');
      svg.setAttribute('height', '40');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('fill', 'none');
      svg.setAttribute('stroke', 'currentColor');
      svg.setAttribute('stroke-width', '2');
      svg.setAttribute('stroke-linecap', 'round');
      svg.setAttribute('stroke-linejoin', 'round');

      const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      circle.setAttribute('cx', '12');
      circle.setAttribute('cy', '12');
      circle.setAttribute('r', '10');

      const polyline = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
      polyline.setAttribute('points', '12 6 12 12 16 14');

      svg.appendChild(circle);
      svg.appendChild(polyline);
      icon.appendChild(svg);

      // Create title
      const title = document.createElement('h2');
      title.className = 'sg-overlay-title';
      title.textContent = 'Shorts Limit Reached';

      // Create message with dynamic content
      const message = document.createElement('p');
      message.className = 'sg-overlay-message';

      const countSpan = document.createElement('span');
      countSpan.id = 'sg-shorts-count';
      countSpan.textContent = `${count - 1}/${limit}`;

      const timerSpan = document.createElement('span');
      timerSpan.id = 'sg-reset-timer';
      timerSpan.textContent = this.formatTime(timeRemaining);

      message.appendChild(document.createTextNode('You have watched '));
      message.appendChild(countSpan);
      message.appendChild(document.createTextNode(' shorts.'));
      message.appendChild(document.createElement('br'));
      message.appendChild(document.createTextNode('Take a break! Timer resets in '));
      message.appendChild(timerSpan);

      // Create progress bar container
      const progressContainer = document.createElement('div');
      progressContainer.className = 'sg-overlay-progress';

      const progressBar = document.createElement('div');
      progressBar.className = 'sg-progress-bar';
      progressBar.id = 'sg-progress-bar';

      progressContainer.appendChild(progressBar);

      // Create back button
      const button = document.createElement('button');
      button.className = 'sg-overlay-back-btn';
      button.id = 'sg-back-btn';
      button.textContent = 'Go Back to YouTube';

      // Assemble the DOM tree
      content.appendChild(icon);
      content.appendChild(title);
      content.appendChild(message);
      content.appendChild(progressContainer);
      content.appendChild(button);
      overlay.appendChild(content);

      // Append to body
      document.body.appendChild(overlay);
      this.overlayElement = overlay;
      console.log('[YouTubeShortsBlocker] showLimitOverlay: Overlay created and appended to body');

      const backBtn = overlay.querySelector('#sg-back-btn');
      if (backBtn) {
        backBtn.addEventListener('click', () => {
          window.location.href = 'https://www.youtube.com';
        });
      }

      this.startCountdown(timeRemaining);
    }

    hideLimitOverlay() {
      if (this.overlayElement) {
        this.overlayElement.remove();
        this.overlayElement = null;
      }

      if (this.countdownInterval !== null) {
        clearInterval(this.countdownInterval);
        this.countdownInterval = null;
      }
    }

    startCountdown(initialTime) {
      const totalWindowMs = this.shortsWindowMinutes * 60 * 1000;
      let timeRemaining = initialTime;

      const updateDisplay = () => {
        const timerElement = document.getElementById('sg-reset-timer');
        const progressBar = document.getElementById('sg-progress-bar');

        if (timerElement) {
          timerElement.textContent = this.formatTime(timeRemaining);
        }

        if (progressBar) {
          const elapsed = totalWindowMs - timeRemaining;
          const progress = Math.min(100, (elapsed / totalWindowMs) * 100);
          progressBar.style.width = progress + '%';
        }
      };

      updateDisplay();

      this.countdownInterval = setInterval(() => {
        timeRemaining -= 1000;

        if (timeRemaining <= 0) {
          this.hideLimitOverlay();
          return;
        }

        updateDisplay();
      }, 1000);
    }

    formatTime(ms) {
      const totalSeconds = Math.floor(ms / 1000);
      const minutes = Math.floor(totalSeconds / 60);
      const seconds = totalSeconds % 60;
      return minutes.toString().padStart(2, '0') + ':' + seconds.toString().padStart(2, '0');
    }

    setupNavigationListener() {
      console.log('[YouTubeShortsBlocker] setupNavigationListener: Setting up navigation event handlers');
      this.navigationHandler = () => {
        console.log('[YouTubeShortsBlocker] Navigation event triggered');
        this.handleNavigation();
      };

      window.addEventListener('yt-navigate-finish', this.navigationHandler);
      window.addEventListener('popstate', this.navigationHandler);
      console.log('[YouTubeShortsBlocker] setupNavigationListener: Event handlers registered');

      const originalPushState = history.pushState;
      const originalReplaceState = history.replaceState;
      const self = this;

      history.pushState = function() {
        originalPushState.apply(this, arguments);
        setTimeout(() => {
          self.handleNavigation();
        }, 0);
      };

      history.replaceState = function() {
        originalReplaceState.apply(this, arguments);
        setTimeout(() => {
          self.handleNavigation();
        }, 0);
      };
    }

    destroy() {
      // Remove CSS
      if (this.styleElement) {
        this.styleElement.remove();
        this.styleElement = null;
      }

      // Remove scroll blocking
      this.disableScrollBlocking();

      // Remove shorts hiding
      this.disableShortsHiding();

      // Hide overlay
      this.hideLimitOverlay();

      // Remove event listeners
      if (this.wheelHandler) {
        document.removeEventListener('wheel', this.wheelHandler, { capture: true });
        this.wheelHandler = null;
      }
      if (this.keyHandler) {
        document.removeEventListener('keydown', this.keyHandler, { capture: true });
        this.keyHandler = null;
      }
      if (this.touchStartHandler) {
        document.removeEventListener('touchstart', this.touchStartHandler, { capture: true });
        this.touchStartHandler = null;
      }
      if (this.touchMoveHandler) {
        document.removeEventListener('touchmove', this.touchMoveHandler, { capture: true });
        this.touchMoveHandler = null;
      }

      // Remove navigation listener
      if (this.navigationHandler) {
        window.removeEventListener('yt-navigate-finish', this.navigationHandler);
        window.removeEventListener('popstate', this.navigationHandler);
        this.navigationHandler = null;
      }
    }
  }

  // ========== INITIALIZE ==========
  // Wait for DOM to be ready
  console.log('[YouTubeShortsBlocker] Document readyState:', document.readyState);
  if (document.readyState === 'loading') {
    console.log('[YouTubeShortsBlocker] DOM still loading, waiting for DOMContentLoaded');
    document.addEventListener('DOMContentLoaded', () => {
      console.log('[YouTubeShortsBlocker] DOMContentLoaded fired, creating blocker');
      const blocker = new YouTubeShortsBlocker();
      window.__SAFEGAZE_YT_SHORTS_BLOCKER__ = blocker;
    });
  } else {
    console.log('[YouTubeShortsBlocker] DOM already loaded, creating blocker immediately');
    const blocker = new YouTubeShortsBlocker();
    window.__SAFEGAZE_YT_SHORTS_BLOCKER__ = blocker;
  }
  console.log('[YouTubeShortsBlocker] Script setup complete');
})();