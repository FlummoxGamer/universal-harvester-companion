const api = typeof browser !== "undefined" ? browser : chrome;

const injectScript = document.createElement('script');
injectScript.src = api.runtime.getURL('inject.js');
(document.head || document.documentElement).appendChild(injectScript);
injectScript.remove();

api.runtime.sendNativeMessage('uhApp', { type: 'GET_SCRIPTS', url: window.location.href })
    .then((response) => {
        if (response && response.scripts) {
            window.postMessage({ type: 'UH_RUN_SCRIPTS', scripts: response.scripts }, '*');
        }
    })
    .catch((e) => console.error('UH Bridge failed to get scripts:', e));

window.addEventListener('message', (event) => {
    if (event.source !== window || !event.data) return;
    if (event.data.type === 'UH_NATIVE_CALL') {
        const payload = event.data.payload;
        api.runtime.sendNativeMessage('uhApp', payload)
            .then((response) => {
                if (payload.id) {
                    window.postMessage({
                        type: 'UH_NATIVE_RESPONSE',
                        id: payload.id,
                        response: response
                    }, '*');
                }
            });
    }
});
