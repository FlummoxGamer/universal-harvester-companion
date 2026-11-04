package app.harvester.companion.splash

interface SplashScreenOperation {
    val type: String
    val dataFetched: Boolean
    suspend fun run()
    fun dispose()
}
