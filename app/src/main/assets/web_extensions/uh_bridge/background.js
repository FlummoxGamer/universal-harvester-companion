const api = typeof browser !== "undefined" ? browser : chrome;

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === 'UH_GET_SCRIPTS') {
        api.runtime.sendNativeMessage('uhApp', { type: 'GET_SCRIPTS', url: message.url })
            .then(response => sendResponse(response))
            .catch(err => sendResponse(null));
        return true;
    } else if (message.type === 'UH_NATIVE_CALL') {
        api.runtime.sendNativeMessage('uhApp', message.payload)
            .then(response => sendResponse(response))
            .catch(err => sendResponse(null));
        return true;
    }
});
