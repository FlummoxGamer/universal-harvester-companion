package app.harvester.companion.components.appstate

import android.content.res.Configuration

enum class OrientationMode {
    Undefined,
    Portrait,
    Landscape;

    companion object {
        fun fromInteger(orientation: Int): OrientationMode {
            return when (orientation) {
                Configuration.ORIENTATION_UNDEFINED -> Undefined
                Configuration.ORIENTATION_PORTRAIT -> Portrait
                Configuration.ORIENTATION_LANDSCAPE -> Landscape
                else -> Undefined
            }
        }
    }
}
