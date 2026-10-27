package app.harvester.companion

import okhttp3.Interceptor
import okhttp3.Response

class SecurityInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.scheme == "http") {
            val headers = req.headers
            for (name in headers.names()) {
                val lower = name.lowercase()
                if (lower == "authorization" || lower == "cookie" || lower == "set-cookie") {
                    UHLog.e("security", "CLEARTEXT LEAK: ${req.url.host} exposed $name")
                }
                val value = headers[name] ?: ""
                if (value.startsWith("Bearer ") || value.startsWith("Basic ")) {
                    UHLog.e("security", "CLEARTEXT TOKEN: ${req.url.host} exposed $name")
                }
            }
        }
        return chain.proceed(req)
    }
}
