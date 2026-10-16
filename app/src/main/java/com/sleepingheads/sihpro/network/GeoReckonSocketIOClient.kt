package com.sleepingheads.sihpro.network

import android.os.SystemClock
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Production Socket.IO Client for GeoReckon Protocol v3.2
 *
 * Connects the Android app to the cloud-hosted / AWS EC2 Python server
 * running the KinoNet-R2 neural network and InEKF dead-reckoning engine.
 *
 * Implements strict schema validation, monotonic elapsedRealtime timestamps,
 * sequence progression, event listeners, and observable StateFlow state.
 */
class GeoReckonSocketIOClient(
    var config: GeoReckonConfig = GeoReckonConfig()
) {
    companion object {
        private const val TAG = "GeoReckonSocketIOClient"

        /**
         * Safe monotonic millisecond timestamp provider.
         * Uses SystemClock.elapsedRealtime() on Android, falls back to
         * System.nanoTime() in plain JVM unit tests.
         */
        fun nowMonotonicMs(): Long {
            return try {
                SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.nanoTime() / 1_000_000L
            }
        }
    }

    private var socket: Socket? = null
    private val sequenceCounter = AtomicLong(0L)
    private var activeSessionId: String = "sess_${nowMonotonicMs()}"

    private val _connectionState = MutableStateFlow(GeoReckonConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GeoReckonConnectionState> = _connectionState.asStateFlow()

    private val _lastPosition = MutableStateFlow<PositionResponse?>(null)
    val lastPosition: StateFlow<PositionResponse?> = _lastPosition.asStateFlow()

    private val _lastCalibration = MutableStateFlow<CalibrateDoneResponse?>(null)
    val lastCalibration: StateFlow<CalibrateDoneResponse?> = _lastCalibration.asStateFlow()

    private val _lastError = MutableStateFlow<ErrorResponse?>(null)
    val lastError: StateFlow<ErrorResponse?> = _lastError.asStateFlow()

    private val _lastRttMs = MutableStateFlow(0.0)
    val lastRttMs: StateFlow<Double> = _lastRttMs.asStateFlow()

    var onCalibrateDone: ((CalibrateDoneResponse) -> Unit)? = null
    var onPosition: ((PositionResponse) -> Unit)? = null
    var onError: ((ErrorResponse) -> Unit)? = null
    var onResetDone: ((ResetDoneResponse) -> Unit)? = null
    var onPong: ((PongPacket, Double) -> Unit)? = null
    var onSensorSent: ((SensorPacket) -> Unit)? = null
    var onPacketEmitted: ((Long, Long) -> Unit)? = null

    val isConnected: Boolean
        get() = socket?.connected() == true

    fun nextSeq(): Long = sequenceCounter.incrementAndGet()

    fun resetSequence(baseline: Long = 1L) {
        sequenceCounter.set(baseline)
    }

    fun startNewSession(prefix: String = "sess"): String {
        activeSessionId = "${prefix}_${nowMonotonicMs()}"
        resetSequence(1L)
        return activeSessionId
    }

    fun getActiveSessionId(): String = activeSessionId

    /**
     * Updates client configuration. If connected, drops current connection to allow reconnecting to new URL.
     */
    fun updateConfig(newConfig: GeoReckonConfig) {
        val wasConnected = isConnected
        if (wasConnected) {
            disconnect()
        }
        config = newConfig
    }

    /**
     * Connects to the GeoReckon Socket.IO server using the configured host/port/auth.
     */
    fun connect() {
        if (socket?.connected() == true) {
            Log.d(TAG, "Already connected to ${config.serverUrl}")
            return
        }

        _connectionState.value = GeoReckonConnectionState.CONNECTING

        try {
            val opts = IO.Options().apply {
                // Support both WebSocket upgrade and HTTP polling fallback for maximum reliability across AWS / LAN
                transports = arrayOf("websocket", "polling")
                timeout = config.connectTimeoutMs
                reconnection = true
                reconnectionAttempts = config.reconnectMaxRetries
                reconnectionDelay = config.reconnectDelayMs
                if (!config.authToken.isNullOrEmpty()) {
                    auth = mapOf("token" to config.authToken)
                }
            }

            val targetUrl = config.serverUrl
            Log.i(TAG, "Initiating Socket.IO connection to $targetUrl (Transports: websocket, polling)")
            val uri = URI.create(targetUrl)
            socket = IO.socket(uri, opts).apply {
                on(Socket.EVENT_CONNECT) {
                    Log.i(TAG, "Socket.IO connected successfully to ${config.serverUrl}")
                    _connectionState.value = GeoReckonConnectionState.CONNECTED_IDLE
                }

                on(Socket.EVENT_DISCONNECT) { args ->
                    Log.w(TAG, "Socket.IO disconnected from ${config.serverUrl}: ${args.firstOrNull()}")
                    _connectionState.value = GeoReckonConnectionState.DISCONNECTED
                }

                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val err = args.firstOrNull()
                    val detailedMsg = when (err) {
                        is Throwable -> {
                            val cause = err.cause
                            if (cause != null && !cause.message.isNullOrEmpty()) {
                                "${err.message ?: err.javaClass.simpleName} (${cause.message})"
                            } else {
                                err.message ?: err.localizedMessage ?: err.javaClass.simpleName
                            }
                        }
                        else -> err?.toString() ?: "Connection rejected / unreachable"
                    }
                    Log.e(TAG, "Socket.IO connect error to ${config.serverUrl}: $detailedMsg", err as? Throwable)
                    _connectionState.value = GeoReckonConnectionState.ERROR
                    val errResp = ErrorResponse(
                        code = GeoReckonErrorCode.CONNECTION_LOST.name,
                        message = "Connection failed to ${config.serverUrl}: $detailedMsg"
                    )
                    _lastError.value = errResp
                    onError?.invoke(errResp)
                }

                on("calibrate_done") { args ->
                    try {
                        val jsonStr = args.firstOrNull()?.toString() ?: "{}"
                        val resp = GeoReckonJson.fromJson(jsonStr, CalibrateDoneResponse::class.java)
                        _lastCalibration.value = resp
                        _connectionState.value = GeoReckonConnectionState.CONNECTED_IDLE
                        onCalibrateDone?.invoke(resp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing calibrate_done", e)
                    }
                }

                on("position") { args ->
                    try {
                        val jsonStr = args.firstOrNull()?.toString() ?: "{}"
                        val resp = GeoReckonJson.fromJson(jsonStr, PositionResponse::class.java)
                        _lastPosition.value = resp
                        if (resp.mode.equals("DEAD_RECKONING", ignoreCase = true)) {
                            _connectionState.value = GeoReckonConnectionState.DEAD_RECKONING
                        } else if (resp.mode.equals("IDLE", ignoreCase = true)) {
                            _connectionState.value = GeoReckonConnectionState.GPS_RESTORED
                        }
                        onPosition?.invoke(resp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing position", e)
                    }
                }

                on("reset_done") { args ->
                    try {
                        val jsonStr = args.firstOrNull()?.toString() ?: "{}"
                        val resp = GeoReckonJson.fromJson(jsonStr, ResetDoneResponse::class.java)
                        _connectionState.value = GeoReckonConnectionState.CONNECTED_IDLE
                        resetSequence(1L)
                        onResetDone?.invoke(resp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing reset_done", e)
                    }
                }

                on("error") { args ->
                    try {
                        val jsonStr = args.firstOrNull()?.toString() ?: "{}"
                        val resp = GeoReckonJson.fromJson(jsonStr, ErrorResponse::class.java)
                        _lastError.value = resp
                        _connectionState.value = GeoReckonConnectionState.ERROR
                        onError?.invoke(resp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing error event", e)
                    }
                }

                on("pong") { args ->
                    try {
                        val jsonStr = args.firstOrNull()?.toString() ?: "{}"
                        val resp = GeoReckonJson.fromJson(jsonStr, PongPacket::class.java)
                        val rtt = (nowMonotonicMs() - resp.tMs).toDouble()
                        _lastRttMs.value = rtt
                        onPong?.invoke(resp, rtt)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing pong", e)
                    }
                }
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during socket initialization", e)
            _connectionState.value = GeoReckonConnectionState.ERROR
            val errResp = ErrorResponse(
                code = GeoReckonErrorCode.INTERNAL_ERROR.name,
                message = "Initialization error: ${e.message}"
            )
            _lastError.value = errResp
            onError?.invoke(errResp)
        }
    }

    /**
     * Emits `calibrate` event.
     */
    fun sendCalibrate(request: CalibrateRequest) {
        _connectionState.value = GeoReckonConnectionState.CALIBRATING
        val jsonString = GeoReckonJson.toJson(request)
        socket?.emit("calibrate", JSONObject(jsonString))
    }

    /**
     * Emits `sensor` event (10 Hz streaming packet).
     */
    fun sendSensor(packet: SensorPacket) {
        onSensorSent?.invoke(packet)
        val jsonString = GeoReckonJson.toJson(packet)
        val jsonObject = JSONObject(jsonString)
        val tEmitMs = nowMonotonicMs()
        onPacketEmitted?.invoke(packet.seq, tEmitMs)
        socket?.emit("sensor", jsonObject)
    }

    /**
     * Emits `gps_restored` event when GNSS is re-acquired.
     */
    fun sendGpsRestored(request: GpsRestoredRequest) {
        val jsonString = GeoReckonJson.toJson(request)
        socket?.emit("gps_restored", JSONObject(jsonString))
    }

    /**
     * Emits `reset` event.
     */
    fun sendReset(request: ResetRequest) {
        val jsonString = GeoReckonJson.toJson(request)
        socket?.emit("reset", JSONObject(jsonString))
    }

    /**
     * Emits `ping` event for heartbeat & RTT evaluation.
     */
    fun sendPing(tMs: Long = nowMonotonicMs()) {
        val packet = PingPacket(tMs = tMs)
        val jsonString = GeoReckonJson.toJson(packet)
        socket?.emit("ping", JSONObject(jsonString))
    }

    /**
     * Disconnects the Socket.IO connection and releases resources.
     */
    fun disconnect() {
        try {
            socket?.disconnect()
            socket?.off()
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting socket", e)
        } finally {
            _connectionState.value = GeoReckonConnectionState.DISCONNECTED
        }
    }

    /**
     * Probes and tests connectivity to the target server URL without affecting active session.
     * Invokes callback with (success: Boolean, rttMs: Double, message: String).
     */
    fun testConnection(
        targetConfig: GeoReckonConfig = config,
        timeoutMs: Long = 4000L,
        onResult: (Boolean, Double, String) -> Unit
    ) {
        Thread {
            var probeSocket: Socket? = null
            var callbackFired = false
            val lock = Any()

            fun finish(success: Boolean, rtt: Double, msg: String) {
                synchronized(lock) {
                    if (callbackFired) return
                    callbackFired = true
                }
                try {
                    probeSocket?.disconnect()
                    probeSocket?.off()
                    probeSocket?.close()
                } catch (_: Exception) {}
                onResult(success, rtt, msg)
            }

            try {
                val opts = IO.Options().apply {
                    transports = arrayOf("websocket", "polling")
                    timeout = timeoutMs
                    reconnection = false
                }
                val uri = URI.create(targetConfig.serverUrl)
                val tStart = nowMonotonicMs()
                probeSocket = IO.socket(uri, opts).apply {
                    on(Socket.EVENT_CONNECT) {
                        val connectRtt = (nowMonotonicMs() - tStart).toDouble()
                        finish(true, connectRtt, "Connected to ${targetConfig.serverUrl} (${connectRtt.toInt()}ms RTT)")
                    }
                    on(Socket.EVENT_CONNECT_ERROR) { args ->
                        val err = args.firstOrNull()
                        val msg = when (err) {
                            is Throwable -> err.message ?: err.localizedMessage ?: err.javaClass.simpleName
                            else -> err?.toString() ?: "Connect error"
                        }
                        finish(false, 0.0, "Failed: $msg")
                    }
                }
                probeSocket?.connect()

                // Safety timeout guard
                Thread.sleep(timeoutMs + 200L)
                finish(false, 0.0, "Timeout after ${timeoutMs}ms reaching ${targetConfig.serverUrl}")
            } catch (e: Exception) {
                finish(false, 0.0, "Initialization failed: ${e.message}")
            }
        }.start()
    }
}
