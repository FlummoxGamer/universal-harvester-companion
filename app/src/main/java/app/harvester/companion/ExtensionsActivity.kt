// FIX: PermissionPromptResponse constructor is package-private in GeckoView 153.
// Use reflection to instantiate it.
private fun buildPermissionResponse(
    permissionsGranted: Boolean
): WebExtension.PermissionPromptResponse {
    return try {
        // Get the constructor with three Boolean parameters
        val constructor = WebExtension.PermissionPromptResponse::class.java.getConstructor(
            java.lang.Boolean::class.java,
            java.lang.Boolean::class.java,
            java.lang.Boolean::class.java
        )
        // Create a new instance
        constructor.newInstance(
            permissionsGranted,
            false, // isPrivateModeGranted
            false  // isTechnicalAndInteractionDataGranted
        )
    } catch (e: Exception) {
        UHLog.throwable("ext", "reflection failed for PermissionPromptResponse", e)
        throw RuntimeException("Failed to create PermissionPromptResponse", e)
    }
}

override fun onInstallPromptRequest(
    extension: WebExtension,
    permissions: Array<out String>,
    origins: Array<out String>,
    dataCollectionPermissions: Array<out String>
): GeckoResult<WebExtension.PermissionPromptResponse> {
    val meta = extension.metaData
    val deferred = GeckoResult<WebExtension.PermissionPromptResponse>()

    runOnUiThread {
        val msg = buildString {
            append("Name: ").append(meta.name).append('\n')
            append("Version: ").append(meta.version).append('\n')
            append("Author: ").append(meta.creatorName ?: "unknown").append('\n')
            if (permissions.isNotEmpty()) {
                append("\nPermissions:\n")
                permissions.forEach { append(" • ").append(it).append('\n') }
            }
            if (origins.isNotEmpty()) {
                append("\nSite access:\n")
                origins.forEach { append(" • ").append(it).append('\n') }
            }
            if (dataCollectionPermissions.isNotEmpty()) {
                append("\nData collection:\n")
                dataCollectionPermissions.forEach {
                    append(" • ").append(it).append('\n')
                }
            }
        }

        AlertDialog.Builder(this@ExtensionsActivity)
            .setTitle("Install ${meta.name}?")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Install") { _, _ ->
                UHLog.d("ext", "approved install ${meta.name}")
                // FIX: Use reflection-based factory instead of direct constructor.
                deferred.complete(buildPermissionResponse(true))
            }
            .setNegativeButton("Cancel") { _, _ ->
                UHLog.d("ext", "denied install ${meta.name}")
                deferred.complete(buildPermissionResponse(false))
            }
            .show()
    }
    return deferred
}
