package com.sleepingheads.sihpro.ui.navigation.components

import android.content.Context
import android.graphics.*
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * Generates custom, crisp vector-rendered BitmapDescriptors for Google Maps navigation:
 * 1. GeoReckon AI Car Cursor (top-down sports car aligned with heading)
 * 2. Naive INS Drift Marker (red warning drift ghost marker)
 * 3. True GNSS Ground-Truth Target Marker (green satellite lock target)
 */
object MapMarkerHelper {

    private var carNormalDescriptor: BitmapDescriptor? = null
    private var carBlackoutDescriptor: BitmapDescriptor? = null
    private var naiveDescriptor: BitmapDescriptor? = null
    private var trueGpsDescriptor: BitmapDescriptor? = null
    private var isInitialized = false

    private fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            try {
                MapsInitializer.initialize(context.applicationContext)
                isInitialized = true
            } catch (_: Throwable) {}
        }
    }

    fun getCarMarker(context: Context, isBlackout: Boolean): BitmapDescriptor {
        ensureInitialized(context)
        return try {
            if (isBlackout) {
                carBlackoutDescriptor ?: BitmapDescriptorFactory.fromBitmap(
                    createCarBitmap(context, isBlackout = true)
                ).also { carBlackoutDescriptor = it }
            } else {
                carNormalDescriptor ?: BitmapDescriptorFactory.fromBitmap(
                    createCarBitmap(context, isBlackout = false)
                ).also { carNormalDescriptor = it }
            }
        } catch (_: Throwable) {
            BitmapDescriptorFactory.defaultMarker(
                if (isBlackout) BitmapDescriptorFactory.HUE_ORANGE else BitmapDescriptorFactory.HUE_CYAN
            )
        }
    }

    fun getNaiveMarker(context: Context): BitmapDescriptor {
        ensureInitialized(context)
        return try {
            naiveDescriptor ?: BitmapDescriptorFactory.fromBitmap(
                createNaiveMarkerBitmap(context)
            ).also { naiveDescriptor = it }
        } catch (_: Throwable) {
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
        }
    }

    fun getTrueGpsMarker(context: Context): BitmapDescriptor {
        ensureInitialized(context)
        return try {
            trueGpsDescriptor ?: BitmapDescriptorFactory.fromBitmap(
                createTrueGpsMarkerBitmap(context)
            ).also { trueGpsDescriptor = it }
        } catch (_: Throwable) {
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
        }
    }

    /**
     * Renders a high-detail, top-down navigation car.
     * Dimensions: 44dp x 82dp (scaled with device density).
     * Orientation: Car points UP (North / 0° heading).
     */
    private fun createCarBitmap(context: Context, isBlackout: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (44 * density).toInt()
        val h = (82 * density).toInt()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val accentColor = if (isBlackout) Color.rgb(255, 145, 0) else Color.rgb(0, 229, 255)
        val accentGlow = if (isBlackout) Color.argb(80, 255, 145, 0) else Color.argb(80, 0, 229, 255)

        val sX = w / 44f
        val sY = h / 82f

        // Helper function to scale coordinates
        fun px(x: Float): Float = x * sX
        fun py(y: Float): Float = y * sY

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Headlight Light Beams projecting forward (towards top Y=0)
        val beamPathLeft = Path().apply {
            moveTo(px(14f), py(16f))
            lineTo(px(2f), py(0f))
            lineTo(px(18f), py(0f))
            close()
        }
        val beamPathRight = Path().apply {
            moveTo(px(30f), py(16f))
            lineTo(px(26f), py(0f))
            lineTo(px(42f), py(0f))
            close()
        }
        paint.shader = LinearGradient(
            0f, py(16f), 0f, 0f,
            Color.argb(120, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(beamPathLeft, paint)
        canvas.drawPath(beamPathRight, paint)
        paint.shader = null

        // 2. Wheels / Tires (4 corners)
        paint.color = Color.rgb(20, 24, 30)
        paint.style = Paint.Style.FILL
        val wheelW = px(5.5f)
        val wheelH = py(13f)
        val wheelR = px(2.5f)

        // Front-left & Front-right
        canvas.drawRoundRect(RectF(px(4f), py(22f), px(4f) + wheelW, py(22f) + wheelH), wheelR, wheelR, paint)
        canvas.drawRoundRect(RectF(px(34.5f), py(22f), px(34.5f) + wheelW, py(22f) + wheelH), wheelR, wheelR, paint)
        // Rear-left & Rear-right
        canvas.drawRoundRect(RectF(px(3.5f), py(54f), px(3.5f) + wheelW, py(54f) + wheelH), wheelR, wheelR, paint)
        canvas.drawRoundRect(RectF(px(35f), py(54f), px(35f) + wheelW, py(54f) + wheelH), wheelR, wheelR, paint)

        // Wheel rim highlights
        paint.color = Color.rgb(80, 95, 115)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(1f)
        canvas.drawRoundRect(RectF(px(4f), py(22f), px(4f) + wheelW, py(22f) + wheelH), wheelR, wheelR, paint)
        canvas.drawRoundRect(RectF(px(34.5f), py(22f), px(34.5f) + wheelW, py(22f) + wheelH), wheelR, wheelR, paint)
        canvas.drawRoundRect(RectF(px(3.5f), py(54f), px(3.5f) + wheelW, py(54f) + wheelH), wheelR, wheelR, paint)
        canvas.drawRoundRect(RectF(px(35f), py(54f), px(35f) + wheelW, py(54f) + wheelH), wheelR, wheelR, paint)

        // 3. Drop Shadow & Ambient Neon Aura behind chassis
        paint.style = Paint.Style.FILL
        paint.color = accentGlow
        canvas.drawRoundRect(RectF(px(6f), py(14f), px(38f), py(76f)), px(12f), px(12f), paint)

        // 4. Car Chassis Body Shape
        val chassisPath = Path().apply {
            moveTo(px(16f), py(15f))
            // Front bumper curve
            quadTo(px(22f), py(13f), px(28f), py(15f))
            // Right front fender
            lineTo(px(35f), py(20f))
            lineTo(px(35f), py(36f))
            // Waist curve
            lineTo(px(34f), py(48f))
            // Right rear muscular haunch
            lineTo(px(37f), py(56f))
            lineTo(px(36f), py(72f))
            // Rear bumper & spoiler
            lineTo(px(28f), py(74f))
            lineTo(px(16f), py(74f))
            // Left rear haunch
            lineTo(px(8f), py(72f))
            lineTo(px(7f), py(56f))
            // Left waist
            lineTo(px(10f), py(48f))
            // Left front fender
            lineTo(px(9f), py(36f))
            lineTo(px(9f), py(20f))
            close()
        }

        // Fill chassis with metallic gradient
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, py(14f), 0f, py(74f),
            intArrayOf(Color.rgb(38, 48, 64), Color.rgb(22, 28, 38), Color.rgb(15, 19, 26)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(chassisPath, paint)
        paint.shader = null

        // Outer Accent Neon Trim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(1.6f)
        paint.color = accentColor
        canvas.drawPath(chassisPath, paint)

        // 5. Front Hood Detail & Intake Lines
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(1f)
        paint.color = Color.argb(120, 255, 255, 255)
        canvas.drawLine(px(16f), py(20f), px(18f), py(30f), paint)
        canvas.drawLine(px(28f), py(20f), px(26f), py(30f), paint)

        // 6. Cockpit Glass / Windshield (Front & Rear)
        val frontGlass = Path().apply {
            moveTo(px(14f), py(32f))
            quadTo(px(22f), py(30f), px(30f), py(32f))
            lineTo(px(32f), py(42f))
            lineTo(px(12f), py(42f))
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(12, 22, 32)
        canvas.drawPath(frontGlass, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(1.2f)
        paint.color = accentColor
        canvas.drawPath(frontGlass, paint)

        // Roof panel
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(18, 24, 34)
        canvas.drawRoundRect(RectF(px(13f), py(43f), px(31f), py(58f)), px(3f), px(3f), paint)

        // Glowing GeoReckon Chevron on Roof
        val chevron = Path().apply {
            moveTo(px(22f), py(46f))
            lineTo(px(27f), py(54f))
            lineTo(px(22f), py(51f))
            lineTo(px(17f), py(54f))
            close()
        }
        paint.color = accentColor
        canvas.drawPath(chevron, paint)

        // Rear Windshield Glass
        val rearGlass = Path().apply {
            moveTo(px(13f), py(60f))
            lineTo(px(31f), py(60f))
            lineTo(px(29f), py(66f))
            lineTo(px(15f), py(66f))
            close()
        }
        paint.color = Color.rgb(10, 18, 26)
        canvas.drawPath(rearGlass, paint)

        // 7. Headlights (Bright Dual LEDs)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(px(13f), py(17f), px(2.2f), paint)
        canvas.drawCircle(px(31f), py(17f), px(2.2f), paint)

        paint.color = accentColor
        canvas.drawCircle(px(13f), py(17f), px(3.2f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(1f)
            color = accentColor
        })
        canvas.drawCircle(px(31f), py(17f), px(3.2f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(1f)
            color = accentColor
        })

        // 8. Rear Tail-Light LED Bar (Neon Red)
        paint.color = Color.rgb(255, 30, 60)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(2f)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(px(15f), py(73f), px(29f), py(73f), paint)

        return bitmap
    }

    /**
     * Renders a custom Naive INS Drift Marker:
     * Red warning ghost radar beacon indicating uncorrected dead-reckoning drift.
     */
    private fun createNaiveMarkerBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (46 * density).toInt()

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Soft Red Pulsing Halo
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(45, 239, 68, 68)
        canvas.drawCircle(c, c, c - 2 * density, paint)

        // 2. Outer Warning Dashed Ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        paint.color = Color.rgb(239, 68, 68)
        paint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        canvas.drawCircle(c, c, c - 7 * density, paint)
        paint.pathEffect = null

        // 3. Center Solid Core Badge
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(30, 8, 8)
        canvas.drawCircle(c, c, 11 * density, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.rgb(239, 68, 68)
        canvas.drawCircle(c, c, 11 * density, paint)

        // 4. Center Warning Exclamation Triangle (⚠️)
        val triPath = Path().apply {
            moveTo(c, c - 6.5f * density)
            lineTo(c + 6.5f * density, c + 5f * density)
            lineTo(c - 6.5f * density, c + 5f * density)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(239, 68, 68)
        canvas.drawPath(triPath, paint)

        // Exclamation point inside triangle
        paint.color = Color.WHITE
        paint.strokeWidth = 1.4f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(c, c - 2.5f * density, c, c + 1f * density, paint)
        canvas.drawCircle(c, c + 3.2f * density, 0.8f * density, paint)

        return bitmap
    }

    /**
     * Renders a True GNSS Satellite Ground-Truth Target Marker:
     * Bright neon-green target crosshair beacon.
     */
    private fun createTrueGpsMarkerBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (38 * density).toInt()

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Neon Green Glow Halo
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(40, 0, 230, 118)
        canvas.drawCircle(c, c, c - 2 * density, paint)

        // 2. Outer Ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.rgb(0, 230, 118)
        canvas.drawCircle(c, c, c - 7 * density, paint)

        // 3. Crosshair ticks (Top, Bottom, Left, Right)
        paint.strokeWidth = 1.8f * density
        val rOut = c - 3 * density
        val rIn = c - 11 * density
        canvas.drawLine(c, c - rOut, c, c - rIn, paint)
        canvas.drawLine(c, c + rIn, c, c + rOut, paint)
        canvas.drawLine(c - rOut, c, c - rIn, c, paint)
        canvas.drawLine(c + rIn, c, c + rOut, c, paint)

        // 4. Center satellite dot
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 230, 118)
        canvas.drawCircle(c, c, 4.5f * density, paint)

        paint.color = Color.WHITE
        canvas.drawCircle(c, c, 2f * density, paint)

        return bitmap
    }
}
