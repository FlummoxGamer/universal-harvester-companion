package app.harvester.companion.service.nimbus

import android.content.Context
import mozilla.components.support.base.observer.Observable
import mozilla.components.support.base.observer.ObserverRegistry
import org.mozilla.experiments.nimbus.EnrolledExperiment
import org.mozilla.experiments.nimbus.Nimbus as ApplicationServicesNimbus
import org.mozilla.experiments.nimbus.NimbusAppInfo
import org.mozilla.experiments.nimbus.NimbusDelegate
import org.mozilla.experiments.nimbus.NimbusDeviceInfo
import org.mozilla.experiments.nimbus.NimbusInterface
import org.mozilla.experiments.nimbus.internal.GeckoPrefHandler
import org.mozilla.experiments.nimbus.internal.NimbusServerSettings
import org.mozilla.experiments.nimbus.internal.RecordedContext

interface NimbusApi : NimbusInterface, Observable<NimbusInterface.Observer>

typealias NimbusAppInfo = NimbusAppInfo

class Nimbus(
    context: Context,
    appInfo: NimbusAppInfo,
    coenrollingFeatureIds: List<String> = listOf(),
    server: NimbusServerSettings?,
    deviceInfo: NimbusDeviceInfo = NimbusDeviceInfo.default(),
    delegate: NimbusDelegate = NimbusDelegate.default(),
    recordedContext: RecordedContext?,
    geckoPrefHandler: GeckoPrefHandler? = null,
    private val observable: Observable<NimbusInterface.Observer> = ObserverRegistry(),
) :
    ApplicationServicesNimbus(
        context = context,
        appInfo = appInfo,
        coenrollingFeatureIds = coenrollingFeatureIds,
        server = server,
        deviceInfo = deviceInfo,
        delegate = delegate,
        observer = Observer(observable),
        recordedContext = recordedContext,
        geckoPrefHandler = geckoPrefHandler,
    ),
    NimbusApi,
    Observable<NimbusInterface.Observer> by observable {
    private class Observer(val observable: Observable<NimbusInterface.Observer>) : NimbusInterface.Observer {
        override fun onExperimentsFetched() {
            observable.notifyObservers { onExperimentsFetched() }
        }

        override fun onUpdatesApplied(updated: List<EnrolledExperiment>) {
            observable.notifyObservers { onUpdatesApplied(updated) }
        }
    }
}

class NimbusDisabled(
    override val context: Context,
    private val observable: Observable<NimbusInterface.Observer> = ObserverRegistry(),
) : NimbusApi, Observable<NimbusInterface.Observer> by observable
