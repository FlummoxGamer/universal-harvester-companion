const api = typeof browser !== "undefined" ? browser : chrome;

api.webRequest.onBeforeSendHeaders.addListener(
    function(details) {
        let headers = details.requestHeaders;
        let hasSecFetch = headers.some(h => h.name.toLowerCase() === "sec-fetch-mode");
        
        if (!hasSecFetch) {
            headers.push({name: "Sec-Fetch-Dest", value: "document"});
            headers.push({name: "Sec-Fetch-Mode", value: "navigate"});
            headers.push({name: "Sec-Fetch-Site", value: "none"});
            headers.push({name: "Sec-Fetch-User", value: "?1"});
            headers.push({name: "Upgrade-Insecure-Requests", value: "1"});
        }
        return {requestHeaders: headers};
    },
    {urls: ["<all_urls>"]},
    ["blocking", "requestHeaders"]
);
