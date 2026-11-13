package app.harvester.companion.browser.storage.sync

import android.content.Context
import java.nio.charset.MalformedInputException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import mozilla.appservices.places.PlacesReaderConnection
import mozilla.appservices.places.PlacesWriterConnection
import mozilla.appservices.places.uniffi.InternalException as UniffiInternalException
import mozilla.appservices.places.uniffi.PlacesApiException
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.storage.Storage
import mozilla.components.concept.storage.StorageMaintenanceRegistry
import mozilla.components.concept.sync.SyncableStore
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.utils.NamedThreadFactory
import mozilla.components.support.utils.logElapsedTime

abstract class PlacesStorage(
    context: Context,
    val crashReporter: CrashReporting? = null,
    readDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val writeDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor(NamedThreadFactory("PlacesStorageWriteScope")).asCoroutineDispatcher(),
) : Storage, SyncableStore, StorageMaintenanceRegistry {
    internal val writeScope = CoroutineScope(writeDispatcher)

    internal val readScope = CoroutineScope(readDispatcher)
    private val storageDir by lazy { context.filesDir }

    private var lastCancelledQuery = ""

    abstract val logger: Logger

    internal open val places: Connection by lazy {
        RustPlacesConnection.init(storageDir)
        RustPlacesConnection
    }

    internal open val writer: PlacesWriterConnection by lazy { places.writer() }
    internal open val reader: PlacesReaderConnection by lazy { places.reader() }

    override suspend fun warmUp() {
        handlePlacesExceptions("warmUp") {
            logElapsedTime(logger, "Warming up places storage") {
                writer
                reader
            }
        }
    }

    override suspend fun runMaintenance(dbSizeLimit: UInt) {
        withContext(writeScope.coroutineContext) {
            places.writer().runMaintenance(dbSizeLimit)
        }
    }

    @Deprecated("Use `cancelWrites` and `cancelReads` to get a similar functionality.")
    override fun cleanup() {
        writeScope.coroutineContext.cancelChildren()
        readScope.coroutineContext.cancelChildren()
        places.close()
    }

    override fun cancelWrites() {
        interruptCurrentWrites()
        writeScope.coroutineContext.cancelChildren()
    }

    override fun cancelReads() {
        interruptCurrentReads()
        readScope.coroutineContext.cancelChildren()
    }

    override fun cancelReads(nextQuery: String) {
        if (nextQuery.isEmpty() || lastCancelledQuery != nextQuery) {
            lastCancelledQuery = nextQuery
            interruptCurrentReads()
            readScope.coroutineContext.cancelChildren()
        }
    }

    internal fun interruptCurrentWrites() {
        handlePlacesExceptions("interruptCurrentWrites") {
            writer.interrupt()
        }
    }

    internal fun interruptCurrentReads() {
        handlePlacesExceptions("interruptCurrentReads") {
            reader.interrupt()
        }
    }

    protected inline fun handlePlacesExceptions(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: MalformedInputException) {
            crashReporter?.submitCaughtException(e)
            logger.debug("Ignoring invalid invalid non utf-8 character when running $operation", e)
        } catch (e: PlacesApiException.OperationInterrupted) {
            logger.debug("Ignoring expected OperationInterrupted exception when running $operation", e)
        } catch (e: PlacesApiException.UrlParseFailed) {
            logger.debug("Ignoring invalid URL while running $operation", e)
        } catch (e: PlacesApiException) {
            crashReporter?.submitCaughtException(e)
            logger.warn("Ignoring PlacesApiException while running $operation", e)
        } catch (e: UniffiInternalException) {
            logger.error("Ignoring internal uniffi places exception when running $operation", e)
        }
    }

    inline fun <T> handlePlacesExceptions(operation: String, default: T, block: () -> T): T {
        return try {
            block()
        } catch (e: MalformedInputException) {
            crashReporter?.submitCaughtException(e)
            logger.debug("Ignoring invalid invalid non utf-8 character when running $operation", e)
            default
        } catch (e: PlacesApiException.OperationInterrupted) {
            logger.debug("Ignoring expected OperationInterrupted exception when running $operation", e)
            default
        } catch (e: PlacesApiException.UrlParseFailed) {
            logger.debug("Ignoring invalid URL while running $operation", e)
            default
        } catch (e: PlacesApiException) {
            crashReporter?.submitCaughtException(e)
            logger.warn("Ignoring PlacesApiException while running $operation", e)
            default
        } catch (e: UniffiInternalException) {
            logger.error("Ignoring internal uniffi places exception when running $operation", e)
            default
        }
    }

    override fun registerStorageMaintenanceWorker() {}
    override fun unregisterStorageMaintenanceWorker(uniqueWorkName: String) {}
}
