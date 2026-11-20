(function () {
    if (window.__uh_bridge_inject_ready) return;
    window.__uh_bridge_inject_ready = true;

    function execCodeSafe(code) {
        if (!code) return;
        let blocked = false;
        const marker = "__uh_exec_" + Math.random().toString(36).slice(2);
        const onCsp = (e) => {
            if (e.violatedDirective && e.violatedDirective.indexOf("script-src") !== -1) {
                blocked = true;
            }
        };
        document.addEventListener("securitypolicyviolation", onCsp);
        try {
            const s = document.createElement("script");
            s.textContent = "window['" + marker + "']=1;\n" + code;
            (document.head || document.documentElement).appendChild(s);
            s.remove();
        } catch (_e) {
            blocked = true;
        }
        document.removeEventListener("securitypolicyviolation", onCsp);

        if (blocked || !window[marker]) {
            try {
                const fn = new Function(code);
                fn.call(window);
            } catch (e) {
                try {
                    const blob = new Blob([code], { type: "application/javascript" });
                    const bUrl = URL.createObjectURL(blob);
                    const s2 = document.createElement("script");
                    s2.src = bUrl;
                    (document.head || document.documentElement).appendChild(s2);
                    setTimeout(() => {
                        s2.remove();
                        URL.revokeObjectURL(bUrl);
                    }, 100);
                } catch (_e2) {
                    console.error("UH Bridge fallback exec failed", e);
                }
            }
        }
        try { delete window[marker]; } catch (_e) {}
    }

    function applyInPageTranslate(lang) {
        try {
            const existing = document.getElementById("uh-gt-frame");
            if (existing) existing.remove();
            const url = window.location.href;
            if (!url || !url.startsWith("http")) return;
            const tl = encodeURIComponent(lang || "en");
            const gtUrl = "https://translate.google.com/translate?sl=auto&tl=" + tl + "&u=" + encodeURIComponent(url);
            window.location.href = gtUrl;
        } catch (_e) {}
    }

    window.addEventListener("message", (event) => {
        if (!event.data) return;
        if (event.data.type === "UH_BOOT_DATA" && event.data.bootPayload) {
            execCodeSafe(event.data.bootPayload);
        } else if (event.data.type === "UH_RUN_PACKET" && event.data.packet) {
            execCodeSafe(event.data.packet.code);
        } else if (event.data.type === "UH_RUN_SCRIPTS" && event.data.scripts) {
            execCodeSafe(event.data.scripts);
        } else if (event.data.type === "UH_RUN_MENU_COMMAND" && event.data.commandId) {
            if (typeof window.__uh_runMenuCommand === "function") {
                try { window.__uh_runMenuCommand(event.data.commandId); } catch (_e) {}
            }
        } else if (event.data.type === "UH_TRANSLATE_PAGE") {
            applyInPageTranslate(event.data.targetLang);
        }
    });

    window.postMessage({ type: "UH_INJECT_READY" }, "*");
})();
