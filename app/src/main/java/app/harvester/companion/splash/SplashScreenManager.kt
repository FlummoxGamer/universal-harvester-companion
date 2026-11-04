package app.harvester.companion.splash

import androidx.core.splashscreen.SplashScreen.KeepOnScreenCondition
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

sealed class SplashScreenManagerResult {
    data class OperationFinished(val type: String, val dataFetched: Boolean) : SplashScreenManagerResult()
    data class TimeoutExceeded(val type: String, val dataFetched: Boolean) : SplashScreenManagerResult()
    data object DidNotPresentSplashScreen : SplashScreenManagerResult()
}

class SplashScreenManager(
    private val splashScreenOperation: SplashScreenOperation,
    private val splashScreenTimeout: Long,
    private val scope: CoroutineScope = MainScope(),
    private val coroutineContext: CoroutineContext = Dispatchers.Main,
    private val showSplashScreen: (KeepOnScreenCondition) -> Unit,
    private val onSplashScreenFinished: (SplashScreenManagerResult) -> Unit,
) : KeepOnScreenCondition {

    private var isSplashScreenFinished = false

    override fun shouldKeepOnScreen(): Boolean {
        return !isSplashScreenFinished
    }

    fun showSplashScreen() {
        showSplashScreen(this)

        scope.launch {
            val result = select {
                async {
                    splashScreenOperation.run()
                    isSplashScreenFinished = true
                    return@async SplashScreenManagerResult.OperationFinished(
                        type = splashScreenOperation.type,
                        dataFetched = splashScreenOperation.dataFetched,
                    )
                }.onAwait { it }

                async {
                    delay(splashScreenTimeout)
                    return@async SplashScreenManagerResult.TimeoutExceeded(
                        type = splashScreenOperation.type,
                        dataFetched = splashScreenOperation.dataFetched,
                    )
                }.onAwait { it }
            }

            splashScreenOperation.dispose()

            withContext(this@SplashScreenManager.coroutineContext) {
                isSplashScreenFinished = true
                onSplashScreenFinished(result)
            }
        }
    }
}
