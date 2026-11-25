// -----------------------------------------------------------------------------
// UH Bridge Content Module 3: Rich Media Metadata Enricher & Video-Only PiP
// -----------------------------------------------------------------------------
(function (C) {
    const api = C.api;

    let activeMediaEl = null;
    let pipIsolatedVideo = null;
    let pipSavedStyle = null;
    let pipSavedParent = null;
    let pipSavedNextSibling = null;
    let pipSavedScrollX = 0;
    let pipSavedScrollY = 0;
    let mediaReportTimer = null;

    function findPrimaryMediaElement() {
        const videos = Array.from(document.querySelectorAll("video"));
        let best = null;
        let bestScore = -1;
        for (let i = 0; i < videos.length; i++) {
            const v = videos[i];
            const rect = v.getBoundingClientRect();
            const area = Math.max(0, rect.width) * Math.max(0, rect.height);
            const playingBonus = (!v.paused && !v.ended && v.readyState >= 2) ? 10000000 : 0;
            const score = playingBonus + area + ((v.videoWidth || 0) * (v.videoHeight || 0));
            if (score > bestScore && (v.readyState >= 1 || !v.paused || area > 10000)) {
                bestScore = score;
                best = v;
            }
        }
        if (best) return best;
        const audios = Array.from(document.querySelectorAll("audio"));
        for (let i = 0; i < audios.length; i++) {
            if (!audios[i].paused && !audios[i].ended) return audios[i];
        }
        return audios[0] || null;
    }

    function extractCleanMediaMetadata(mediaEl) {
        const ms = navigator.mediaSession && navigator.mediaSession.metadata;
        const host = C.currentNormalizedHost();
        let title = (ms && ms.title) ? String(ms.title).trim() : "";
        let artist = (ms && ms.artist) ? String(ms.artist).trim() : "";
        let artworkUrl = "";

        if (ms && Array.isArray(ms.artwork) && ms.artwork.length > 0) {
            artworkUrl = ms.artwork[ms.artwork.length - 1].src || ms.artwork[0].src || "";
        }

        if (!title) {
            const ogTitle = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
            title = (ogTitle && ogTitle.content) ? ogTitle.content.trim() : "";
        }
        if (!title) {
            const h1 = document.querySelector("h1.ytd-watch-metadata, h1.slim-video-information-title, h1");
            if (h1) title = (h1.textContent || "").trim();
        }
        if (!title) {
            title = (document.title || "").trim();
        }
        title = title.replace(/\s*[-–|·]\s*(YouTube|Vimeo|Twitch|Dailymotion|Facebook|Instagram|TikTok|X)$/i, "").trim();
        if (!title || /^https?:\/\//i.test(title)) {
            title = host ? ("Playing on " + host) : "Media Playback";
        }

        if (!artist) {
            const channelEl = document.querySelector("#channel-name a, .slim-owner-channel-name, meta[name='author'], meta[property='og:site_name']");
            if (channelEl) {
                artist = (channelEl.content || channelEl.textContent || "").trim();
            }
        }
        if (!artist) {
            artist = host || "UH Browser";
        }

        // Artwork fallbacks: YouTube thumbnail -> video poster -> og:image
        if (!artworkUrl && (host === "youtube.com" || host === "m.youtube.com" || host === "youtu.be")) {
            try {
                const u = new URL(window.location.href);
                const vid = u.searchParams.get("v") || (host === "youtu.be" ? u.pathname.slice(1) : "");
                if (vid && /^[a-zA-Z0-9_-]{6,16}$/.test(vid)) {
                    artworkUrl = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
                }
            } catch (_e) {}
        }
        if (!artworkUrl && mediaEl && mediaEl.poster) {
            artworkUrl = mediaEl.poster;
        }
        if (!artworkUrl) {
            const ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
            if (ogImg && ogImg.content) artworkUrl = ogImg.content;
        }
        if (artworkUrl && !artworkUrl.startsWith("http") && !artworkUrl.startsWith("data:")) {
            try { artworkUrl = new URL(artworkUrl, window.location.href).href; } catch (_e) {}
        }

        return { title, artist, artworkUrl };
    }

    function reportMediaStateNow() {
        const el = findPrimaryMediaElement();
        activeMediaEl = el;
        if (!el) return;
        const isVideo = el.tagName === "VIDEO";
        const meta = extractCleanMediaMetadata(el);
        const dur = isFinite(el.duration) && el.duration > 0 ? el.duration : 0;
        const pos = isFinite(el.currentTime) && el.currentTime >= 0 ? el.currentTime : 0;
        api.runtime.sendMessage({
            type: "UH_MEDIA_STATE",
            title: meta.title,
            artist: meta.artist,
            artworkUrl: meta.artworkUrl,
            pageUrl: window.location.href,
            playing: !el.paused && !el.ended,
            hasVideo: isVideo,
            durationSec: dur,
            positionSec: pos,
            videoWidth: isVideo ? (el.videoWidth || el.clientWidth || 16) : 0,
            videoHeight: isVideo ? (el.videoHeight || el.clientHeight || 9) : 0
        }).catch(() => {});
    }

    function scheduleMediaReport() {
        if (mediaReportTimer) return;
        mediaReportTimer = setTimeout(() => {
            mediaReportTimer = null;
            reportMediaStateNow();
        }, 250);
    }

    ["play", "playing", "pause", "ended", "loadedmetadata", "durationchange", "seeked"].forEach((evt) => {
        document.addEventListener(evt, (e) => {
            const t = e && e.target;
            if (t && (t.tagName === "VIDEO" || t.tagName === "AUDIO")) {
                activeMediaEl = t;
                scheduleMediaReport();
            }
        }, true);
    });

    setInterval(() => {
        if (activeMediaEl && !activeMediaEl.paused && !activeMediaEl.ended) {
            reportMediaStateNow();
        }
    }, 2000);

    function handleMediaControlAction(msg) {
        const el = activeMediaEl || findPrimaryMediaElement();
        if (!el) return;
        try {
            switch (msg.command) {
                case "PLAY":
                    el.play().catch(() => {});
                    break;
                case "PAUSE":
                    el.pause();
                    break;
                case "TOGGLE_PLAY_PAUSE":
                    if (el.paused) el.play().catch(() => {}); else el.pause();
                    break;
                case "SEEK_RELATIVE": {
                    const delta = Number(msg.deltaSec) || 0;
                    const dur = isFinite(el.duration) ? el.duration : (el.currentTime + Math.abs(delta));
                    el.currentTime = Math.max(0, Math.min(dur, (el.currentTime || 0) + delta));
                    break;
                }
                case "SEEK_TO": {
                    const target = Number(msg.positionSec);
                    if (isFinite(target) && target >= 0) {
                        el.currentTime = target;
                    }
                    break;
                }
                case "NEXT": {
                    const nextBtn = document.querySelector(".ytp-next-button, button[aria-label*='Next' i], a[aria-label*='Next' i]");
                    if (nextBtn) nextBtn.click();
                    else if (isFinite(el.duration)) el.currentTime = Math.min(el.duration, el.currentTime + 30);
                    break;
                }
                case "PREV": {
                    if (el.currentTime > 4) {
                        el.currentTime = 0;
                    } else {
                        const prevBtn = document.querySelector(".ytp-prev-button, button[aria-label*='Previous' i]");
                        if (prevBtn) prevBtn.click();
                        else el.currentTime = 0;
                    }
                    break;
                }
            }
            setTimeout(reportMediaStateNow, 120);
        } catch (_e) {}
    }

    function setPipVideoIsolation(enable) {
        const styleId = "__uh_pip_isolation_style";
        if (enable) {
            if (pipIsolatedVideo) return;
            const video = (activeMediaEl && activeMediaEl.tagName === "VIDEO") ? activeMediaEl : findPrimaryMediaElement();
            if (!video || video.tagName !== "VIDEO") return;

            pipIsolatedVideo = video;
            pipSavedScrollX = window.scrollX || 0;
            pipSavedScrollY = window.scrollY || 0;
            pipSavedStyle = video.getAttribute("style");
            pipSavedParent = video.parentNode;
            pipSavedNextSibling = video.nextSibling;

            let styleEl = document.getElementById(styleId);
            if (!styleEl) {
                styleEl = document.createElement("style");
                styleEl.id = styleId;
                styleEl.textContent = [
                    "html.__uh_pip_active, html.__uh_pip_active body { margin: 0 !important; padding: 0 !important; overflow: hidden !important; background: #000000 !important; width: 100vw !important; height: 100vh !important; }",
                    "html.__uh_pip_active body > *:not(.__uh_pip_host) { visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; }",
                    ".__uh_pip_host { position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important; background: #000000 !important; z-index: 2147483647 !important; display: flex !important; align-items: center !important; justify-content: center !important; visibility: visible !important; opacity: 1 !important; }",
                    ".__uh_pip_video_only { position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important; max-width: 100vw !important; max-height: 100vh !important; object-fit: contain !important; background: #000000 !important; margin: 0 !important; padding: 0 !important; transform: none !important; visibility: visible !important; opacity: 1 !important; z-index: 2147483647 !important; }"
                ].join("\n");
                (document.head || document.documentElement).appendChild(styleEl);
            }

            const wasPlaying = !video.paused && !video.ended;
            const host = document.createElement("div");
            host.id = "__uh_pip_host";
            host.className = "__uh_pip_host";
            document.documentElement.classList.add("__uh_pip_active");
            video.classList.add("__uh_pip_video_only");
            host.appendChild(video);
            (document.body || document.documentElement).appendChild(host);
            if (wasPlaying && video.paused) {
                video.play().catch(() => {});
            }
        } else {
            document.documentElement.classList.remove("__uh_pip_active");
            const styleEl = document.getElementById(styleId);
            if (styleEl) styleEl.remove();

            const video = pipIsolatedVideo;
            if (video) {
                const wasPlaying = !video.paused && !video.ended;
                video.classList.remove("__uh_pip_video_only");
                if (pipSavedStyle !== null) video.setAttribute("style", pipSavedStyle);
                else video.removeAttribute("style");

                if (pipSavedParent && document.contains(pipSavedParent)) {
                    try {
                        if (pipSavedNextSibling && pipSavedNextSibling.parentNode === pipSavedParent) {
                            pipSavedParent.insertBefore(video, pipSavedNextSibling);
                        } else {
                            pipSavedParent.appendChild(video);
                        }
                    } catch (_e) {}
                }
                const host = document.getElementById("__uh_pip_host");
                if (host) host.remove();
                if (wasPlaying && video.paused) {
                    video.play().catch(() => {});
                }
                window.scrollTo(pipSavedScrollX, pipSavedScrollY);
            }
            pipIsolatedVideo = null;
            pipSavedStyle = null;
            pipSavedParent = null;
            pipSavedNextSibling = null;
        }
    }

    C.handleMediaControlAction = handleMediaControlAction;
    C.setPipVideoIsolation = setPipVideoIsolation;
})(globalThis.__uhCs);
