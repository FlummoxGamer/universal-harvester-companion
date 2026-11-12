package app.harvester.companion.service.nimbus

import android.content.Context
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.utils.NamedThreadFactory
import org.mozilla.experiments.nimbus.AbstractNimbusBuilder
import org.mozilla.experiments.nimbus.NimbusDelegate
import org.mozilla.experiments.nimbus.internal.NimbusServerSettings

private val logger = Logger("service/Nimbus")

class NimbusBuilder(context: Context) : AbstractNimbusBuilder<NimbusApi>(context) {
    override fun createDelegate(): NimbusDelegate =
        NimbusDelegate(
            dbScope = createNamedCoroutineScope("NimbusDbScope"),
            fetchScope = createNamedCoroutineScope("NimbusFetchScope"),
            errorReporter = errorReporter,
            logger = { logger.info(it) },
        )

    override fun newNimbus(
        appInfo: NimbusAppInfo,
        serverSettings: NimbusServerSettings?,
    ) =
        Nimbus(
                context,
                appInfo = appInfo,
                coenrollingFeatureIds = getCoenrollingFeatureIds(),
                server = serverSettings,
                deviceInfo = createDeviceInfo(),
                delegate = createDelegate(),
                recordedContext = recordedContext,
                geckoPrefHandler = geckoPrefHandler,
            )
            .apply {
                this.register(createObserver())
            }

    override fun newNimbusDisabled() = NimbusDisabled(context)
}

private fun createNamedCoroutineScope(name: String) =
    CoroutineScope(Executors.newSingleThreadExecutor(NamedThreadFactory(name)).asCoroutineDispatcher())
