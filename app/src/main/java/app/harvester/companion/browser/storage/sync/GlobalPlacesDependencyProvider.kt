package app.harvester.companion.browser.storage.sync

import androidx.annotation.VisibleForTesting

object GlobalPlacesDependencyProvider {

    @VisibleForTesting internal var placesStorage: PlacesStorage? = null

    fun initialize(placesStorage: PlacesStorage) {
        this.placesStorage = placesStorage
    }

    internal fun requirePlacesStorage(): PlacesStorage {
        return requireNotNull(placesStorage) {
            "GlobalPlacesDependencyProvider.initialize must be called before accessing the Places storage"
        }
    }
}
