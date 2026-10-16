package com.sleepingheads.sihpro.engine

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Phase 3 — KinoNet-R2 Speed Prediction via On-Device ML.
 *
 * Ports [georeckon_pipeline.py] Phase 3 [KinoNetR2.predict_with_uncertainty].
 *
 * Runs natively on the Android phone using:
 *  1. ONNX Runtime Android (Primary, loads [kinonet_r2.onnx])
 *  2. TensorFlow Lite (Secondary, loads [kinonet_r2.tflite] if present)
 *  3. Pure-Kotlin Physics fallback (if no weight file is present)
 *
 * Input:  float32 tensor [1, 6, 20]  — 6 IMU channels × 20 timesteps (2 s @ 10 Hz)
 * Output: float32 tensor [1, 2]      — [μ (speed m/s), log_σ² (log variance)]
 *
 * The 20-sample sliding window matches Python [Config.WINDOW_SIZE = 20].
 * Channel order: [AccX, AccY, AccZ, GyroX, GyroY, GyroZ] (vehicle frame, bias-removed).
 */
class KinoNetInference(context: Context) {

    companion object {
        private const val TAG = "KinoNetInference"
        const val WINDOW_SIZE = 20       // 2 seconds @ 10 Hz
        const val INPUT_CHANNELS = 6     // AccX, AccY, AccZ, GyroX, GyroY, GyroZ
        private const val ONNX_MODEL_ASSET = "kinonet_r2.onnx"
        private const val TFLITE_MODEL_ASSET = "kinonet_r2.tflite"
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tfliteInterpreter: Interpreter? = null
    var activeEngine: String = "FALLBACK"
        private set

    // Sliding window buffer: stores last WINDOW_SIZE samples as [6] each
    private val windowBuffer = ArrayDeque<FloatArray>(WINDOW_SIZE + 1)

    init {
        // Attempt 1: Load ONNX Runtime model
        try {
            val modelBytes = context.assets.open(ONNX_MODEL_ASSET).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val session = env.createSession(modelBytes, sessionOpts)
            ortEnv = env
            ortSession = session
            activeEngine = "ONNX_RUNTIME"
            Log.i(TAG, "Successfully loaded $ONNX_MODEL_ASSET via ONNX Runtime Android")
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX Runtime asset loading failed: ${t.message}. Trying TFLite...")
        }

        // Attempt 2: If ONNX is not available, try TFLite
        if (activeEngine == "FALLBACK") {
            try {
                val tfliteModel = loadTfliteModelFromAssets(context)
                val options = Interpreter.Options().apply {
                    numThreads = 2
                }
                tfliteInterpreter = Interpreter(tfliteModel, options)
                activeEngine = "TFLITE"
                Log.i(TAG, "Successfully loaded $TFLITE_MODEL_ASSET via TensorFlow Lite")
            } catch (t: Throwable) {
                Log.w(TAG, "TFLite asset loading failed: ${t.message}. Using pure-Kotlin fallback.")
                activeEngine = "FALLBACK"
            }
        }
    }

    /**
     * Feed one IMU sample (in vehicle frame, bias-corrected) into the rolling window.
     *
     * @param accelVehicle [ax, ay, az] m/s² (vehicle frame, gravity-removed)
     * @param gyroVehicle  [gx, gy, gz] rad/s (vehicle frame)
     */
    fun feedSample(accelVehicle: DoubleArray, gyroVehicle: DoubleArray) {
        val row = FloatArray(INPUT_CHANNELS) {
            when (it) {
                0 -> accelVehicle[0].toFloat()
                1 -> accelVehicle[1].toFloat()
                2 -> accelVehicle[2].toFloat()
                3 -> gyroVehicle[0].toFloat()
                4 -> gyroVehicle[1].toFloat()
                5 -> gyroVehicle[2].toFloat()
                else -> 0f
            }
        }
        windowBuffer.addLast(row)
        if (windowBuffer.size > WINDOW_SIZE) windowBuffer.removeFirst()
    }

    /**
     * Run inference if the window is full (≥ [WINDOW_SIZE] samples).
     *
     * @return [Pair<Double, Double>] of (speed_mps, variance) or null if not ready.
     */
    fun predict(): Pair<Double, Double>? {
        if (windowBuffer.size < WINDOW_SIZE) return null

        return when (activeEngine) {
            "ONNX_RUNTIME" -> predictOnnx() ?: predictFallback()
            "TFLITE"       -> predictTFLite() ?: predictFallback()
            else           -> predictFallback()
        }
    }

    val isReady: Boolean get() = windowBuffer.size >= WINDOW_SIZE

    // -----------------------------------------------------------------------
    // ONNX Runtime inference
    // -----------------------------------------------------------------------

    private fun predictOnnx(): Pair<Double, Double>? {
        val env = ortEnv ?: return null
        val session = ortSession ?: return null

        val floatBuffer = FloatBuffer.allocate(1 * INPUT_CHANNELS * WINDOW_SIZE)
        val samples = windowBuffer.toList()

        // Fill channel-first: for each channel c, for each timestep t
        for (c in 0 until INPUT_CHANNELS) {
            for (t in 0 until WINDOW_SIZE) {
                floatBuffer.put(samples[t][c])
            }
        }
        floatBuffer.rewind()

        val tensorShape = longArrayOf(1L, INPUT_CHANNELS.toLong(), WINDOW_SIZE.toLong())
        var tensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        return try {
            tensor = OnnxTensor.createTensor(env, floatBuffer, tensorShape)
            results = session.run(mapOf("imu_window" to tensor))

            @Suppress("UNCHECKED_CAST")
            val outputArray = results[0].value as Array<FloatArray>
            val mu = outputArray[0][0].toDouble().coerceAtLeast(0.0)
            val logVar = outputArray[0][1].toDouble().coerceIn(-10.0, 10.0)
            val variance = Math.exp(logVar)

            Pair(mu, variance)
        } catch (t: Throwable) {
            Log.e(TAG, "ONNX prediction failed: ${t.message}")
            null
        } finally {
            tensor?.close()
            results?.close()
        }
    }

    // -----------------------------------------------------------------------
    // TFLite inference
    // -----------------------------------------------------------------------

    private fun predictTFLite(): Pair<Double, Double>? {
        val interp = tfliteInterpreter ?: return null

        val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_CHANNELS * WINDOW_SIZE * 4)
            .order(ByteOrder.nativeOrder())

        val samples = windowBuffer.toList()
        for (c in 0 until INPUT_CHANNELS) {
            for (t in 0 until WINDOW_SIZE) {
                inputBuffer.putFloat(samples[t][c])
            }
        }
        inputBuffer.rewind()

        val outputBuffer = Array(1) { FloatArray(2) }
        return try {
            interp.run(inputBuffer, outputBuffer)
            val mu = outputBuffer[0][0].toDouble().coerceAtLeast(0.0)
            val logVar = outputBuffer[0][1].toDouble().coerceIn(-10.0, 10.0)
            val variance = Math.exp(logVar)
            Pair(mu, variance)
        } catch (t: Throwable) {
            Log.e(TAG, "TFLite prediction failed: ${t.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // Pure-Kotlin fallback
    // -----------------------------------------------------------------------

    private fun predictFallback(): Pair<Double, Double> {
        val samples = windowBuffer.toList()
        val forwardAccels = samples.map { it[0].toDouble() }
        val speedEstimate = maxOf(0.0, forwardAccels.takeLast(10).average() * 2.0)
        val variance = 4.0
        return Pair(speedEstimate, variance)
    }

    private fun loadTfliteModelFromAssets(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(TFLITE_MODEL_ASSET)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() {
        try {
            ortSession?.close()
        } catch (_: Exception) {}
        ortSession = null

        try {
            ortEnv?.close()
        } catch (_: Exception) {}
        ortEnv = null

        try {
            tfliteInterpreter?.close()
        } catch (_: Exception) {}
        tfliteInterpreter = null
    }
}
