package app.harvester.companion.splash

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeckoInitOperation(private val context: Context) : SplashScreenOperation {
    override val type = "gecko-init"
    override var dataFetched: Boolean = false
        private set

    override suspend fun run() {
        withContext(Dispatchers.IO) {
            app.harvester.companion.GeckoEngine.getRuntime(context)
            dataFetched = true
        }
    }

    override fun dispose() {}
}
