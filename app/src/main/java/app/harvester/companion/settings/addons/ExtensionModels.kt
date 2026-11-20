package app.harvester.companion.settings.addons

import mozilla.components.concept.engine.webextension.WebExtension
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

val BUILTIN_INTERNAL_EXTENSION_IDS: Set<String> = setOf(
    "uh-bridge@harvester.app",
    "header-injector@harvester.app",
)

enum class CatalogCategory(val label: String, val apiSlug: String?) {
    FEATURED("Featured", null),
    PRIVACY("Privacy & Security", "privacy-security"),
    APPEARANCE("Appearance", "appearance"),
    PRODUCTIVITY("Productivity", "productivity"),
    MEDIA("Media & Video", "photos-music-videos"),
    SHOPPING("Shopping", "shopping"),
}

enum class CatalogSort(val label: String, val apiParam: String) {
    POPULAR("Popular", "users"),
    TOP_RATED("Top Rated", "rating"),
    TRENDING("Trending", "hotness"),
}

enum class InstallStage(val label: String) {
    DOWNLOADING("Downloading…"),
    VERIFYING("Verifying package…"),
    AWAITING_APPROVAL("Waiting for approval…"),
    INSTALLING("Installing…"),
}

data class ExtensionInstallTask(
    val key: String,
    val name: String,
    val stage: InstallStage,
    val startedAtMs: Long = System.currentTimeMillis(),
)

data class ExtensionUpdateInfo(
    val extensionId: String,
    val extensionName: String,
    val currentVersion: String,
    val latestVersion: String,
    val xpiUrl: String,
)

data class InstalledExtensionItem(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val creatorName: String,
    val homepageUrl: String,
    val optionsPageUrl: String?,
    val openOptionsInTab: Boolean,
    val enabled: Boolean,
    val allowedInPrivateBrowsing: Boolean,
    val requiredPermissions: List<String>,
    val requiredOrigins: List<String>,
    val optionalPermissions: List<String>,
    val grantedOptionalPermissions: List<String>,
    val optionalOrigins: List<String>,
    val grantedOptionalOrigins: List<String>,
    val isUnsupported: Boolean,
    val updateInfo: ExtensionUpdateInfo? = null,
    val extensionRef: WebExtension,
)

data class ExtensionUiEvent(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val isError: Boolean = false,
)

sealed interface PendingExtensionPrompt {
    val id: String
    val extensionId: String
    val extensionName: String
    val extensionVersion: String
    val extensionRef: WebExtension
    val permissions: List<String>
    val origins: List<String>
    val dataCollectionPermissions: List<String>

    class Install(
        override val extensionRef: WebExtension,
        override val permissions: List<String>,
        override val origins: List<String>,
        override val dataCollectionPermissions: List<String>,
        private val onResult: (granted: Boolean, allowInPrivate: Boolean, allowDataCollection: Boolean) -> Unit,
    ) : PendingExtensionPrompt {
        override val id: String = UUID.randomUUID().toString()
        override val extensionId: String = extensionRef.id
        override val extensionName: String = extensionRef.getMetadata()?.name?.ifBlank { extensionRef.id } ?: extensionRef.id
        override val extensionVersion: String = extensionRef.getMetadata()?.version.orEmpty()
        private val handled = AtomicBoolean(false)

        fun respond(
            granted: Boolean,
            allowInPrivate: Boolean = false,
            allowDataCollection: Boolean = false,
        ) {
            if (handled.compareAndSet(false, true)) {
                onResult(granted, allowInPrivate, allowDataCollection)
            }
        }
    }

    class Update(
        override val extensionRef: WebExtension,
        override val permissions: List<String>,
        override val origins: List<String>,
        override val dataCollectionPermissions: List<String>,
        private val onResult: (granted: Boolean) -> Unit,
    ) : PendingExtensionPrompt {
        override val id: String = UUID.randomUUID().toString()
        override val extensionId: String = extensionRef.id
        override val extensionName: String = extensionRef.getMetadata()?.name?.ifBlank { extensionRef.id } ?: extensionRef.id
        override val extensionVersion: String = extensionRef.getMetadata()?.version.orEmpty()
        private val handled = AtomicBoolean(false)

        fun respond(granted: Boolean) {
            if (handled.compareAndSet(false, true)) {
                onResult(granted)
            }
        }
    }

    class Optional(
        override val extensionRef: WebExtension,
        override val permissions: List<String>,
        override val origins: List<String>,
        override val dataCollectionPermissions: List<String>,
        private val onResult: (granted: Boolean) -> Unit,
    ) : PendingExtensionPrompt {
        override val id: String = UUID.randomUUID().toString()
        override val extensionId: String = extensionRef.id
        override val extensionName: String = extensionRef.getMetadata()?.name?.ifBlank { extensionRef.id } ?: extensionRef.id
        override val extensionVersion: String = extensionRef.getMetadata()?.version.orEmpty()
        private val handled = AtomicBoolean(false)

        fun respond(granted: Boolean) {
            if (handled.compareAndSet(false, true)) {
                onResult(granted)
            }
        }
    }
}
