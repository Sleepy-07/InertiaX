package com.sleepingheads.sihpro.network

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
 * Built-in Backend Targets for GeoReckon Cloud & Local Setups.
 * Preconfigured out-of-the-box so users do NOT have to enter manual server details.
 */
enum class BackendEnvironment(
    val title: String,
    val subtitle: String,
    val host: String,
    val port: Int,
    val isSecure: Boolean
) {
    /**
     * Production AWS EC2 deployment:
     * Pure-Python Socket.IO daemon running on EC2 instance hosting KinoNet-R2 PyTorch model.
     */
    AWS_EC2_CLOUD(
        title = "AWS EC2 Cloud Server",
        subtitle = "Production KinoNet-R2 Cloud Hosting (WSS / Port 5000)",
        host = "10.0.2.2", // Default to emulator reach, configurable to AWS IP/FQDN
        port = 5000,
        isSecure = false
    ),

    /**
     * Local Dev Host for Android Emulator testing (10.0.2.2 maps to workstation localhost).
     */
    LOCAL_EMULATOR(
        title = "Local Emulator Loopback",
        subtitle = "Host Workstation http://10.0.2.2:5000",
        host = "10.0.2.2",
        port = 5000,
        isSecure = false
    ),

    /**
     * Local Dev Host for physical USB adb reverse tethering.
     */
    LOCAL_DEVICE_REVERSE(
        title = "Physical USB Reverse",
        subtitle = "Adb Port-Forwarded http://127.0.0.1:5000",
        host = "127.0.0.1",
        port = 5000,
        isSecure = false
    )
}

data class GeoReckonConfig(
    val environment: BackendEnvironment = BackendEnvironment.AWS_EC2_CLOUD,
    val customHost: String? = null,
    val customPort: Int? = null,
    val authToken: String? = null,
    val sampleRateHz: Double = 10.0,
    val connectTimeoutMs: Long = 8000L,
    val reconnectMaxRetries: Int = 5,
    val reconnectDelayMs: Long = 1000L
) {
    val host: String
        get() = customHost ?: environment.host

    val port: Int
        get() = customPort ?: environment.port

    val serverUrl: String
        get() = if (environment.isSecure) "https://$host:$port" else "http://$host:$port"
}
