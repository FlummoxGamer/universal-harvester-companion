package app.harvester.companion

import okhttp3.Interceptor
import okhttp3.Response

override fun intercept(chain: Interceptor.Chain): Response {
    val req = chain.request()
    if (req.url.scheme == "http") {
        val headers = req.headers
        var leaked = false
        var leakType = ""
        
        for (name in headers.names()) {
            val lower = name.lowercase()
            if (lower == "authorization" || lower == "cookie" || lower == "set-cookie") {
                leaked = true
                leakType += "[Header: $name] "
            }
            val value = headers[name] ?: ""
            if (value.startsWith("Bearer ") || value.startsWith("Basic ")) {
                leaked = true
                leakType += "[Token in: $name] "
            }
        }
        
        if (leaked) {
            val trace = Thread.currentThread().stackTrace
                .take(10) // Limit to 10 lines to avoid infinite logcat loops
                .joinToString("\n") { "  at $it" }
            UHLog.writeLeakLog("SECURITY CLEARTEXT LEAK: ${req.url}\nTypes: $leakType\n$trace")
        }
    }
    return chain.proceed(req)
  }
}
