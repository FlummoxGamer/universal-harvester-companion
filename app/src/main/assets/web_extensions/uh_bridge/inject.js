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

    function applyGlobalZoom(percent) {
        try {
            const clamped = Math.max(50, Math.min(300, Number(percent) || 100));
            let styleEl = document.getElementById("__uh_global_zoom_style");
            if (clamped === 100) {
                if (styleEl) styleEl.remove();
                if (document.documentElement) {
                    document.documentElement.style.removeProperty("zoom");
                }
                return;
            }
            const factor = (clamped / 100).toFixed(2);
            if (!styleEl) {
                styleEl = document.createElement("style");
                styleEl.id = "__uh_global_zoom_style";
                (document.head || document.documentElement).appendChild(styleEl);
            }
            // Gecko 126+ natively supports standard CSS zoom across full layout, text, and images
            styleEl.textContent = "html { zoom: " + factor + " !important; -webkit-text-size-adjust: none; }";
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
        } else if (event.data.type === "UH_TOGGLE_DEVTOOLS") {
            if (typeof window.__uhToggleDevTools === "function") {
                try { window.__uhToggleDevTools(event.data.darkTheme !== false); } catch (_e) {}
            }
        } else if (event.data.type === "UH_APPLY_GLOBAL_ZOOM") {
            applyGlobalZoom(event.data.percent);
        }
    });

    window.postMessage({ type: "UH_INJECT_READY" }, "*");
})();
