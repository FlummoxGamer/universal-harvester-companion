package app.harvester.companion.components

import android.content.Context
import androidx.paging.DataSource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissionsStorage
import app.harvester.companion.UHApp

class PermissionStorage(
    private val context: Context,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
    internal val permissionsStorage: SitePermissionsStorage = (context.applicationContext as UHApp).components.geckoSitePermissionsStorage,
) {
    suspend fun add(sitePermissions: SitePermissions, private: Boolean) = withContext(dispatcher) {
        permissionsStorage.save(sitePermissions, private = private)
    }

    suspend fun findSitePermissionsBy(origin: String, private: Boolean): SitePermissions? = withContext(dispatcher) {
        permissionsStorage.findSitePermissionsBy(origin, private = private)
    }

    suspend fun updateSitePermissions(sitePermissions: SitePermissions, private: Boolean) = withContext(dispatcher) {
        permissionsStorage.update(sitePermissions, private = private)
    }

    suspend fun getSitePermissionsPaged(): DataSource.Factory<Int, SitePermissions> {
        return permissionsStorage.getSitePermissionsPaged()
    }

    suspend fun deleteSitePermissions(sitePermissions: SitePermissions) = withContext(dispatcher) {
        permissionsStorage.remove(sitePermissions, private = false)
    }

    suspend fun deleteAllSitePermissions() = withContext(dispatcher) {
        permissionsStorage.removeAll()
    }
}
