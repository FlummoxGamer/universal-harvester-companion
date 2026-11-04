package app.harvester.companion.splash

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CompositeSplashScreenOperation(private val operations: List<SplashScreenOperation>) : SplashScreenOperation {
    override val type: String
        get() = operations.joinToString("+") { it.type }

    override val dataFetched: Boolean
        get() = operations.all { it.dataFetched }

    override suspend fun run() = coroutineScope {
        operations.map { op -> async { op.run() } }.awaitAll()
        Unit
    }

    override fun dispose() {
        operations.forEach { it.dispose() }
    }
}
