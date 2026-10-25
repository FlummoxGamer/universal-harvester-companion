(function() {
    if (window.__uh_bridge_inject_ready) return;
    window.__uh_bridge_inject_ready = true;

    window.addEventListener('message', (event) => {
        if (!event.data) return;
        if (event.data.type === 'UH_RUN_SCRIPTS') {
            const scripts = event.data.scripts;
            if (scripts) {
                try {
                    const script = document.createElement('script');
                    script.textContent = scripts;
                    (document.head || document.documentElement).appendChild(script);
                    script.remove();
                } catch (e) {
                    console.error('UH Bridge script injection failed', e);
                }
            }
        } else if (event.data.type === 'UH_NATIVE_RESPONSE') {
            window.postMessage(event.data, '*');
        }
    });

    window.postMessage({ type: 'UH_INJECT_READY' }, '*');
})();
