const api = typeof browser !== "undefined" ? browser : chrome;

// requestScripts
api.runtime.sendNativeMessage('uhApp', { type: 'GET_SCRIPTS', url: window.location.href })
    .then((response) => {
        if (response && response.scripts) {
            const script = document.createElement('script');
            script.textContent = response.scripts;
            (document.head || document.documentElement).appendChild(script);
            script.remove();
        }
    })
    .catch((e) => console.error('UH Bridge failed to get scripts:', e));

// listenNativeCalls
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
