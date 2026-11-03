package app.harvester.companion.ext

import android.content.Context
import app.harvester.companion.UHApp
import app.harvester.companion.components.Components

val Context.components: Components
    get() = (applicationContext as UHApp).components
