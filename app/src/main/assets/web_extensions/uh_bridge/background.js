const api = typeof browser !== "undefined" ? browser : chrome;
// keepAlive
setInterval(() => {
    api.runtime.sendNativeMessage('uhApp', { type: 'PING' }).catch(() => {});
}, 30000);
