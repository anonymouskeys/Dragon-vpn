package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.anonymouskeys.monstervpn.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dpi.ByeDpiManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.NotificationHelper
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CoreTestService : Service() {

    // Service callbacks run on Android's main thread. ByeDpiManager.acquire() starts a native
    // process and probes its local SOCKS port with a blocking Socket.connect(), so all test
    // commands must be dispatched to IO before touching the DPI runtime.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO + CoroutineName("CoreTestService")
    )
    private val commandGeneration = AtomicInteger(0)
    private val commandMutex = Mutex()

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    @Volatile
    private var dpiTestOwned = false

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "CoreTestService is being destroyed, cancelling ${activeWorkers.size} active workers")
        commandGeneration.incrementAndGet()
        serviceScope.cancel()
        // cancel any active workers
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        releaseDpiTestOwner()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val generation = commandGeneration.incrementAndGet()
        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> serviceScope.launch {
                commandMutex.withLock {
                    handleMeasureStart(message, startId, generation)
                }
            }
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> serviceScope.launch {
                commandMutex.withLock {
                    handleMeasureCancel()
                }
            }
            else -> {
                NotificationHelper.stopForeground(this)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(
        message: TestServiceMessage,
        startId: Int,
        generation: Int
    ) {
        LogUtil.i(AppConfig.TAG, "CoreTestService starting worker   subscription ${message.subscriptionId}")

        val titleRes = when (message.testMode) {
            TestServiceMessage.TEST_MODE_SMART -> R.string.title_smart_test_all_server
            TestServiceMessage.TEST_MODE_TCP -> R.string.title_tcp_test_all_server
            else -> R.string.title_handshake_test_all_server
        }
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            getString(titleRes)
        )

        val guidsList = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }.distinct()

        if (guidsList.isEmpty()) {
            NotificationHelper.stopForeground(this)
            stopSelf(startId)
            return
        }

        // A second tap while a batch is alive must not cancel and restart hundreds of tests.
        if (activeWorkers.isNotEmpty()) {
            LogUtil.i(AppConfig.TAG, "CoreTestService: batch already running; duplicate start ignored")
            return
        }

        if (generation != commandGeneration.get() || serviceJob.isCancelled) return

        when (message.testMode) {
            TestServiceMessage.TEST_MODE_SMART -> startSmartTest(guidsList, generation)
            TestServiceMessage.TEST_MODE_HANDSHAKE -> {
                acquireDpiTestOwnerIfNeeded()
                if (generation != commandGeneration.get() || serviceJob.isCancelled) {
                    releaseDpiTestOwner()
                    return
                }
                startStandardWorker(guidsList, TestServiceMessage.TEST_MODE_HANDSHAKE)
            }
            else -> startStandardWorker(guidsList, TestServiceMessage.TEST_MODE_TCP)
        }
    }

    private fun startStandardWorker(guids: List<String>, testMode: String) {
        handleWorkerEvent(RealPingEvent.Progress("0 / ${guids.size}")) {}

        lateinit var worker: RealPingWorkerService
        worker = RealPingWorkerService(
            context = this,
            guids = guids,
            testMode = testMode,
            onEvent = { event -> handleWorkerEvent(event) { activeWorkers.remove(worker) } }
        )
        activeWorkers.add(worker)
        worker.start()
    }

    /**
     * Smart Test is the only two-stage mode: direct TCP against every visible profile, followed by
     * a real protocol handshake only for TCP-capable profiles that passed. ByeDPI ownership is
     * acquired immediately before the handshake stage and released by the normal finish path, so
     * standalone TCP testing never starts or changes the DPI runtime.
     */
    private fun startSmartTest(guids: List<String>, generation: Int) {
        val tcpGuids = guids.filter(RealPingWorkerService::supportsTcpPrecheck)
        val directHandshakeGuids = guids.filterNot(RealPingWorkerService::supportsTcpPrecheck)
        val tcpPassed = Collections.synchronizedList(mutableListOf<String>())

        if (tcpGuids.isEmpty()) {
            acquireDpiTestOwnerIfNeeded()
            if (generation != commandGeneration.get() || serviceJob.isCancelled) {
                releaseDpiTestOwner()
                finishBatch("-1")
                return
            }
            startSmartHandshake(directHandshakeGuids)
            return
        }

        publishStageProgress("TCP", "0 / ${tcpGuids.size}")

        lateinit var tcpWorker: RealPingWorkerService
        tcpWorker = RealPingWorkerService(
            context = this,
            guids = tcpGuids,
            testMode = TestServiceMessage.TEST_MODE_TCP,
            onEvent = onEvent@{ event ->
                when (event) {
                    is RealPingEvent.Result -> {
                        MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                        MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, event.guid)
                        if (event.delayMillis >= 0L) tcpPassed.add(event.guid)
                    }
                    is RealPingEvent.Progress -> publishStageProgress("TCP", event.text)
                    is RealPingEvent.Finish -> {
                        activeWorkers.remove(tcpWorker)
                        if (event.status != "0" || generation != commandGeneration.get() || serviceJob.isCancelled) {
                            finishBatch(event.status)
                            return@onEvent
                        }

                        // UDP/QUIC/complex profiles cannot be meaningfully pre-checked with a
                        // plain TCP socket, so Smart Test sends them directly to handshake.
                        val handshakeGuids = (directHandshakeGuids + tcpPassed).distinct()
                        if (handshakeGuids.isEmpty()) {
                            finishBatch("0")
                            return@onEvent
                        }

                        acquireDpiTestOwnerIfNeeded()
                        if (generation != commandGeneration.get() || serviceJob.isCancelled) {
                            releaseDpiTestOwner()
                            finishBatch("-1")
                            return@onEvent
                        }
                        startSmartHandshake(handshakeGuids)
                    }
                }
            }
        )
        activeWorkers.add(tcpWorker)
        tcpWorker.start()
    }

    private fun startSmartHandshake(guids: List<String>) {
        publishStageProgress("Handshake", "0 / ${guids.size}")
        lateinit var worker: RealPingWorkerService
        worker = RealPingWorkerService(
            context = this,
            guids = guids,
            testMode = TestServiceMessage.TEST_MODE_HANDSHAKE,
            onEvent = { event ->
                when (event) {
                    is RealPingEvent.Progress -> publishStageProgress("Handshake", event.text)
                    else -> handleWorkerEvent(event) { activeWorkers.remove(worker) }
                }
            }
        )
        activeWorkers.add(worker)
        worker.start()
    }

    private fun publishStageProgress(stage: String, progress: String) {
        val text = "$stage: $progress"
        NotificationHelper.updateNotification(
            channelType = NotificationChannelType.CORE_TEST,
            context = this,
            content = getString(R.string.connection_runing_task_left, text)
        )
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, text)
    }

    private fun finishBatch(status: String) {
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, status)
        releaseDpiTestOwner()
        NotificationHelper.stopForeground(this)
        stopSelf()
    }

    private fun handleWorkerEvent(event: RealPingEvent, onWorkerDone: () -> Unit) {
        when (event) {
            is RealPingEvent.Progress -> {
                NotificationHelper.updateNotification(
                    channelType = NotificationChannelType.CORE_TEST,
                    context = this,
                    content = getString(R.string.connection_runing_task_left, event.text)
                )
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, event.text)
            }

            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, event.guid)
            }

            is RealPingEvent.Finish -> {
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, event.status)
                onWorkerDone()
                if (activeWorkers.isEmpty()) {
                    releaseDpiTestOwner()
                    NotificationHelper.stopForeground(this)
                    stopSelf()
                }
            }
        }
    }

    private fun handleMeasureCancel() {
        LogUtil.i(AppConfig.TAG, "CoreTestService received cancel message, cancelling ${activeWorkers.size} active workers")
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        releaseDpiTestOwner()
        NotificationHelper.stopForeground(this)
        stopSelf()
    }
    /**
     * Keep one ciadpi process alive for the complete batch. Speed-test configs are generated
     * after this acquisition, so CoreConfigManager can safely insert the local SOCKS chain.
     */
    @Synchronized
    private fun acquireDpiTestOwnerIfNeeded() {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_DPI_ENABLED, false) || dpiTestOwned) return

        dpiTestOwned = ByeDpiManager.acquire(applicationContext, ByeDpiManager.Owner.TEST_SERVICE)
        if (!dpiTestOwned) {
            LogUtil.w(AppConfig.TAG, "CoreTestService: ByeDPI is unavailable; tests will use the normal path")
        }
    }

    @Synchronized
    private fun releaseDpiTestOwner() {
        if (!dpiTestOwned) return
        ByeDpiManager.release(ByeDpiManager.Owner.TEST_SERVICE)
        dpiTestOwned = false
    }

}