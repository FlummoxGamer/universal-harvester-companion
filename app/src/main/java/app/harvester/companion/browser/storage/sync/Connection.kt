package app.harvester.companion.browser.storage.sync

import androidx.annotation.GuardedBy
import java.io.Closeable
import java.io.File
import mozilla.appservices.places.PlacesApi
import mozilla.appservices.places.PlacesReaderConnection
import mozilla.appservices.places.PlacesWriterConnection

const val DB_NAME = "places.sqlite"

internal interface Connection : Closeable {
    fun registerWithSyncManager()
    fun reader(): PlacesReaderConnection
    fun newReader(): PlacesReaderConnection
    fun writer(): PlacesWriterConnection
}

internal object RustPlacesConnection : Connection {
    @GuardedBy("this") private var api: PlacesApi? = null
    @GuardedBy("this") private var cachedReader: PlacesReaderConnection? = null

    fun init(parentDir: File) =
        synchronized(this) {
            if (api == null) {
                api = PlacesApi(File(parentDir, DB_NAME).canonicalPath)
            }
            cachedReader = api!!.openReader()
        }

    override fun registerWithSyncManager() {
        val api = safeGetApi()
        check(api != null) { "must call init first" }
        api.registerWithSyncManager()
    }

    override fun reader(): PlacesReaderConnection =
        synchronized(this) {
            check(cachedReader != null) { "must call init first" }
            return cachedReader!!
        }

    override fun newReader(): PlacesReaderConnection =
        synchronized(this) {
            val api = safeGetApi()
            check(api != null) { "must call init first" }
            return api.openReader()
        }

    override fun writer(): PlacesWriterConnection {
        val api = safeGetApi()
        check(api != null) { "must call init first" }
        return api.getWriter()
    }

    override fun close() =
        synchronized(this) {
            check(api != null) { "must call init first" }
            api!!.close()
            api = null
        }

    private fun safeGetApi(): PlacesApi? =
        synchronized(this) {
            return this.api
        }
}
