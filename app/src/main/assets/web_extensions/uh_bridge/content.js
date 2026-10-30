const api = typeof browser !== "undefined" ? browser : chrome;

window.addEventListener('message', (event) => {
    if (!event.data) return;
    if (event.data.type === 'UH_INJECT_READY') {
        api.runtime.sendMessage({ type: 'UH_GET_SCRIPTS', url: window.location.href })
            .then(response => {
                if (response && response.scripts) {
                    window.postMessage({ type: 'UH_RUN_SCRIPTS', scripts: response.scripts }, '*');
                }
            });
    } else if (event.data.type === 'UH_NATIVE_CALL') {
        api.runtime.sendMessage({ type: 'UH_NATIVE_CALL', payload: event.data.payload })
            .then(response => {
                if (event.data.payload.id) {
                    window.postMessage({
                        type: 'UH_NATIVE_RESPONSE',
                        id: event.data.payload.id,
                        response: response
                    }, '*');
                }
            });
    }
});

const injectScript = document.createElement('script');
injectScript.src = api.runtime.getURL('inject.js');
(document.head || document.documentElement).appendChild(injectScript);
injectScript.remove();
