package com.sleepingheads.sihpro.network

import com.sleepingheads.sihpro.BuildConfig

/**
 * GeoReckon Client State, Target Endpoints, and Error Definitions
 */

enum class GeoReckonConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_IDLE,
    CALIBRATING,
    DEAD_RECKONING,
    GPS_RESTORED,
    ERROR
}

enum class GeoReckonErrorCode(val description: String) {
    NOT_CALIBRATED("Sensor stream sent before calibration completed"),
    INVALID_SCHEMA("Packet schema is invalid or missing required fields"),
    NAN_OR_INF("Sensor payload contains NaN or non-finite values"),
    OUT_OF_ORDER_SEQ("Sequence number received out of order"),
    INVALID_RATE("Sensor stream rate is outside required 8-12 Hz window"),
    HISTORY_TOO_SHORT("Calibration IMU history < 50 or GPS history < 3 points"),
    HISTORY_TOO_LONG("Calibration IMU history contains > 600 samples"),
    ALIGNMENT_HEALTH_LOW("PCA variance ratio too low (< 0.25)"),
    BEARING_INVALID("Vehicle speed < 2.0 m/s with no moving historical bearing"),
    INTERNAL_ERROR("Server-side internal engine error"),
    AUTH_FAILED("WebSocket handshake authentication failed"),
    CONNECTION_LOST("Transport connection dropped"),
    TIMEOUT("Operation timed out"),
    UNKNOWN("Unknown error occurred");

    companion object {
        fun fromCode(code: String): GeoReckonErrorCode {
            return entries.firstOrNull { it.name.equals(code, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * Endpoint provider resolving configured AWS Cloud URL from local.properties via BuildConfig.
 */
object GeoReckonEndpoints {
    val AWS_DEFAULT_URL: String = BuildConfig.AWS_SERVER_URL.ifBlank { "http://3.230.8.186" }
}

/**
 * Built-in Backend Targets for GeoReckon Cloud & Local Setups.
 */
enum class BackendEnvironment(
    val title: String,
    val subtitle: String,
    val host: String,
    val port: Int,
    val isSecure: Boolean
) {
    AWS_EC2_CLOUD(
        title = "AWS EC2 Cloud",
        subtitle = "Live Remote KinoNet-R2 Daemon",
        host = "3.230.8.186",
        port = 80,
        isSecure = false
    ),
    LOCAL_DEVICE_REVERSE(
        title = "USB Reverse (Physical)",
        subtitle = "Adb Port-Forwarded (127.0.0.1:5000)",
        host = "127.0.0.1",
        port = 5000,
        isSecure = false
    ),
    LOCAL_WIFI_LAN(
        title = "Wi-Fi LAN Host",
        subtitle = "Phone & Laptop on same Wi-Fi (192.168.x.x:5000)",
        host = "192.168.1.100",
        port = 5000,
        isSecure = false
    ),
    LOCAL_EMULATOR(
        title = "Emulator Loopback",
        subtitle = "Android Studio Emulator (10.0.2.2:5000)",
        host = "10.0.2.2",
        port = 5000,
        isSecure = false
    )
}

data class GeoReckonConfig(
    val environment: BackendEnvironment = BackendEnvironment.AWS_EC2_CLOUD,
    val customHost: String? = null,
    val customPort: Int? = null,
    val customUrl: String? = null,
    val isSecure: Boolean = false,
    val authToken: String? = null,
    val sampleRateHz: Double = 10.0,
    val connectTimeoutMs: Long = 6000L,
    val reconnectMaxRetries: Int = 5,
    val reconnectDelayMs: Long = 1000L
) {
    val host: String
        get() {
            val ch = customHost?.trim()
            if (!ch.isNullOrEmpty()) return ch
            return environment.host
        }

    val port: Int
        get() = customPort ?: environment.port

    /**
     * Normalized Socket.IO connection URL.
     * Sources from local.properties (BuildConfig.AWS_SERVER_URL) for AWS EC2 Cloud,
     * or custom/configured parameters.
     */
    val serverUrl: String
        get() {
            var raw = when {
                !customUrl.isNullOrBlank() -> customUrl
                !customHost.isNullOrBlank() -> customHost
                environment == BackendEnvironment.AWS_EC2_CLOUD -> GeoReckonEndpoints.AWS_DEFAULT_URL
                else -> host
            }.trim()

            // 1. Detect and strip protocol scheme
            var useSsl = isSecure || environment.isSecure
            if (raw.startsWith("https://", ignoreCase = true)) {
                useSsl = true
                raw = raw.substring(8)
            } else if (raw.startsWith("http://", ignoreCase = true)) {
                useSsl = false
                raw = raw.substring(7)
            } else if (raw.startsWith("wss://", ignoreCase = true)) {
                useSsl = true
                raw = raw.substring(6)
            } else if (raw.startsWith("ws://", ignoreCase = true)) {
                useSsl = false
                raw = raw.substring(5)
            }

            // 2. Strip query parameters (e.g. ?EIO=4&transport=polling)
            if (raw.contains("?")) {
                raw = raw.substringBefore("?")
            }

            // 3. Strip paths (e.g. /socket.io, /socket.io/)
            if (raw.contains("/")) {
                raw = raw.substringBefore("/")
            }

            raw = raw.trim()

            // 4. Extract embedded port if present in host (e.g. 3.230.8.186:80 or localhost:5000)
            val effectiveHost: String
            val effectivePort: Int
            if (raw.contains(":")) {
                val parts = raw.split(":")
                effectiveHost = parts[0]
                effectivePort = parts.getOrNull(1)?.toIntOrNull() ?: port
            } else {
                effectiveHost = raw
                effectivePort = port
            }

            val scheme = if (useSsl) "https" else "http"
            val defaultPort = if (useSsl) 443 else 80
            return if (effectivePort == defaultPort) {
                "$scheme://$effectiveHost"
            } else {
                "$scheme://$effectiveHost:$effectivePort"
            }
        }
}
