package app.harvester.companion.service.nimbus

import org.mozilla.experiments.nimbus.NimbusMessagingHelperInterface
import org.mozilla.experiments.nimbus.internal.NimbusException

fun NimbusMessagingHelperInterface.evalJexlSafe(condition: String) =
    try {
        evalJexl(condition)
    } catch (e: NimbusException.EvaluationException) {
        false
    }
