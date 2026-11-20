const api = typeof browser !== "undefined" ? browser : chrome;

function mapFetchDest(type) {
    switch (type) {
        case "main_frame": return "document";
        case "sub_frame": return "iframe";
        case "stylesheet": return "style";
        case "script": return "script";
        case "image": return "image";
        case "font": return "font";
        case "media": return "video";
        case "websocket": return "websocket";
        default: return "empty";
    }
}

function mapFetchMode(type) {
    if (type === "main_frame" || type === "sub_frame") return "navigate";
    if (type === "image" || type === "stylesheet" || type === "font" || type === "media") return "no-cors";
    return "cors";
}

api.webRequest.onBeforeSendHeaders.addListener(
    function (details) {
        const headers = details.requestHeaders || [];
        const hasSecFetch = headers.some((h) => h.name && h.name.toLowerCase() === "sec-fetch-mode");

        if (!hasSecFetch) {
            const dest = mapFetchDest(details.type);
            const mode = mapFetchMode(details.type);
            headers.push({ name: "Sec-Fetch-Dest", value: dest });
            headers.push({ name: "Sec-Fetch-Mode", value: mode });
            headers.push({ name: "Sec-Fetch-Site", value: details.type === "main_frame" ? "none" : "same-site" });
            if (details.type === "main_frame") {
                headers.push({ name: "Sec-Fetch-User", value: "?1" });
                headers.push({ name: "Upgrade-Insecure-Requests", value: "1" });
            }
            headers.push({ name: "Sec-CH-UA", value: '"Chromium";v="134", "Not:A-Brand";v="24"' });
            headers.push({ name: "Sec-CH-UA-Mobile", value: "?1" });
            headers.push({ name: "Sec-CH-UA-Platform", value: '"Android"' });
        }

        if (details.type === "main_frame" || details.type === "xmlhttprequest" || details.type === "sub_frame") {
            try {
                const headerMap = {};
                for (const h of headers) {
                    if (h.name) headerMap[h.name] = h.value || "";
                }
                api.runtime.sendNativeMessage("uhApp", {
                    type: "HEADER_EVENT",
                    url: details.url,
                    method: details.method || "GET",
                    reqType: details.type,
                    headers: headerMap
                }).catch(() => {});
            } catch (_e) {}
        }

        return { requestHeaders: headers };
    },
    { urls: ["<all_urls>"] },
    ["blocking", "requestHeaders"]
);
