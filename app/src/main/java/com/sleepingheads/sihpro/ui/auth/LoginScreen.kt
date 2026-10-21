package com.sleepingheads.sihpro.ui.auth

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import com.sleepingheads.sihpro.session.AuthProviderType
import com.sleepingheads.sihpro.session.UserSessionManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    var showEmailDialog by remember { mutableStateOf(false) }
    var showPhoneDialog by remember { mutableStateOf(false) }
    var showFirebaseConfigDialog by remember { mutableStateOf(false) }
    var configDialogMessage by remember { mutableStateOf<String?>(null) }
    var isGoogleLoading by remember { mutableStateOf(false) }

    // Google Web Client ID from generated resources if available
    val defaultWebClientIdRes = remember(context) {
        try { context.resources.getIdentifier("default_web_client_id", "string", context.packageName) } catch (_: Exception) { 0 }
    }
    val webClientId = if (defaultWebClientIdRes != 0) {
        androidx.compose.ui.res.stringResource(defaultWebClientIdRes)
    } else {
        "538390966047-uatr4ic64n7ec31mdi7m1g158ctik5cr.apps.googleusercontent.com"
    }

    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data == null) {
            isGoogleLoading = false
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            val gEmail = account?.email ?: "google@inertiax.com"
            val gName = account?.displayName ?: "Google User"

            if (!idToken.isNullOrEmpty()) {
                isGoogleLoading = true
                coroutineScope.launch {
                    kotlinx.coroutines.delay(10000)
                    if (isGoogleLoading) {
                        isGoogleLoading = false
                        Toast.makeText(context, "Sign-in timed out. Please check your network.", Toast.LENGTH_SHORT).show()
                    }
                }
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        isGoogleLoading = false
                        if (authTask.isSuccessful) {
                            val user = authTask.result?.user
                            val gPhoto = account?.photoUrl?.toString() ?: user?.photoUrl?.toString()
                            UserSessionManager.saveSession(
                                context = context,
                                name = user?.displayName ?: gName,
                                identifier = user?.email ?: gEmail,
                                provider = AuthProviderType.GOOGLE,
                                photoUrl = gPhoto
                            )
                            Toast.makeText(context, "Welcome, ${user?.displayName ?: gName}!", Toast.LENGTH_SHORT).show()
                            onLoginSuccess()
                        } else {
                            val err = authTask.exception?.localizedMessage ?: "Firebase Google sign-in failed."
                            configDialogMessage = "Firebase Google Sign-In Error:\n\n$err\n\nPlease ensure Google Provider is enabled in Firebase Console (Authentication > Sign-in method)."
                            showFirebaseConfigDialog = true
                        }
                    }
                    .addOnFailureListener { failEx ->
                        isGoogleLoading = false
                        configDialogMessage = "Firebase Sign-In Error:\n\n${failEx.localizedMessage}"
                        showFirebaseConfigDialog = true
                    }
            } else {
                isGoogleLoading = false
                configDialogMessage = "Google Sign-In completed without an ID Token.\n\nTo enable Google Sign-In in Firebase:\n1. Open Firebase Console -> Authentication -> Sign-in method -> Enable Google.\n2. Add the Debug SHA-1 to Project Settings.\n3. Download the updated google-services.json."
                showFirebaseConfigDialog = true
            }
        } catch (e: Exception) {
            isGoogleLoading = false
            if (e is ApiException) {
                if (e.statusCode != 12501 && e.statusCode != 16) {
                    configDialogMessage = when (e.statusCode) {
                        10 -> "DEVELOPER_ERROR (Code 10):\n\nGoogle Play Services cannot authenticate this app with Firebase.\n\nYou must add this debug SHA-1 to Firebase Console > Project Settings > Your apps:\n\n2A:1D:DF:27:A9:11:68:D9:EA:2C:66:1D:47:EE:95:39:9C:C3:5D:CB\n\nAnd enable Google Sign-In under Authentication > Sign-in method."
                        12500 -> "Configuration Error (12500):\n\nPlease ensure Google Sign-In is enabled in Firebase Console and a support email is selected."
                        7 -> "Network Error (7):\n\nPlease check your internet connection."
                        else -> "Google Sign-In Error (${e.statusCode}):\n\n${e.localizedMessage}"
                    }
                    showFirebaseConfigDialog = true
                }
            } else {
                Toast.makeText(context, "Google Sign-In error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Assets: google.png and mail.png from assets
    val googleBitmap = remember(context) {
        try {
            context.assets.open("google.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    val mailBitmap = remember(context) {
        try {
            context.assets.open("mail.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    // ── Hero Floating & Radar Pulse Animations ────────────────────────
    val heroTransition = rememberInfiniteTransition(label = "hero_anim")
    val floatOffset by heroTransition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hero_float"
    )
    val radarPulse1 by heroTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_1"
    )
    val radarPulse2 by heroTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, delayMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_2"
    )

    // ── Screen Entrance Staggered Spring Animation ────────────────────
    var isStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isStarted = true
    }

    val cardOffsetY by animateFloatAsState(
        targetValue = if (isStarted) 0f else 110f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f),
        label = "card_slide"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (isStarted) 1f else 0f,
        animationSpec = tween(380),
        label = "card_alpha"
    )

    // ── Primary Button Sweeping Metallic Shimmer ──────────────────────
    val shimmerTranslate by heroTransition.animateFloat(
        initialValue = -350f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, delayMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )

    // ── Tactile Press Scale Interactions ──────────────────────────────
    val phoneInteraction = remember { MutableInteractionSource() }
    val isPhonePressed by phoneInteraction.collectIsPressedAsState()
    val phoneScale by animateFloatAsState(
        targetValue = if (isPhonePressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "phone_scale"
    )

    val googleInteraction = remember { MutableInteractionSource() }
    val isGooglePressed by googleInteraction.collectIsPressedAsState()
    val googleScale by animateFloatAsState(
        targetValue = if (isGooglePressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "google_scale"
    )

    val emailInteraction = remember { MutableInteractionSource() }
    val isEmailPressed by emailInteraction.collectIsPressedAsState()
    val emailScale by animateFloatAsState(
        targetValue = if (isEmailPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "email_scale"
    )

    val guestInteraction = remember { MutableInteractionSource() }
    val isGuestPressed by guestInteraction.collectIsPressedAsState()
    val guestScale by animateFloatAsState(
        targetValue = if (isGuestPressed) 0.94f else 1f,
        label = "guest_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEBF0F5))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Top Hero: Animated Map GIF (mapp.gif) with Floating & Radar Waves ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Animated radar rings expanding outward from the location center
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.minDimension * 0.46f

                    val r1 = maxRadius * radarPulse1
                    val alpha1 = ((1f - radarPulse1) * 0.25f).coerceIn(0f, 1f)
                    drawCircle(
                        color = Color(0xFF4CAF50).copy(alpha = alpha1),
                        radius = r1,
                        center = center,
                        style = Stroke(width = 2.5f)
                    )

                    val r2 = maxRadius * radarPulse2
                    val alpha2 = ((1f - radarPulse2) * 0.25f).coerceIn(0f, 1f)
                    drawCircle(
                        color = Color(0xFF2E7D32).copy(alpha = alpha2),
                        radius = r2,
                        center = center,
                        style = Stroke(width = 2.5f)
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    val source = ImageDecoder.createSource(ctx.assets, "mapp.gif")
                                    val drawable = ImageDecoder.decodeDrawable(source)
                                    if (drawable is AnimatedImageDrawable) {
                                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                        drawable.start()
                                    }
                                    setImageDrawable(drawable)
                                } else {
                                    val stream = ctx.assets.open("mapp.gif")
                                    val bmp = BitmapFactory.decodeStream(stream)
                                    setImageBitmap(bmp)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = floatOffset
                        }
                )
            }

            // ── Bottom Sheet Card with Smooth Spring Entrance ─────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = cardOffsetY
                        alpha = cardAlpha
                    }
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                        clip = false
                    ),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(top = 24.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // App Brand: "GEOReckon"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "GEO",
                            color = Color(0xFF1E202C),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif
                        )
                        Text(
                            text = "Reckon",
                            color = Color(0xFF4CAF50),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Subtitle
                    Text(
                        text = "Intelligent real-time routing & live telematics",
                        color = Color(0xFF6B7280),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Primary Button: "Continue with Phone Number" with Tactile Bounce & Metallic Shimmer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .graphicsLayer {
                                scaleX = phoneScale
                                scaleY = phoneScale
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1E1E2C))
                            .clickable(
                                interactionSource = phoneInteraction,
                                indication = null,
                                onClick = { showPhoneDialog = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Moving metallic shimmer sweep
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.12f),
                                            Color.Transparent
                                        ),
                                        start = Offset(shimmerTranslate, 0f),
                                        end = Offset(shimmerTranslate + 180f, 100f)
                                    )
                                )
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Phone",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Continue with Phone Number",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Divider: "─── INSTANT ACCESS VIA ───"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            thickness = 1.dp,
                            color = Color(0xFFE5E7EB)
                        )
                        Text(
                            text = "INSTANT ACCESS VIA",
                            color = Color(0xFF9CA3AF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            thickness = 1.dp,
                            color = Color(0xFFE5E7EB)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Secondary Buttons: Google & Continue with Email
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Google Button (Firebase Google Sign-In) with Tactile Bounce
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .graphicsLayer {
                                    scaleX = googleScale
                                    scaleY = googleScale
                                }
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(16.dp))
                                .clickable(
                                    enabled = !isGoogleLoading,
                                    interactionSource = googleInteraction,
                                    indication = null
                                ) {
                                    isGoogleLoading = true
                                    try {
                                        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                            .requestEmail()
                                        if (webClientId.isNotEmpty()) {
                                            gsoBuilder.requestIdToken(webClientId)
                                        }
                                        val client = GoogleSignIn.getClient(context, gsoBuilder.build())
                                        googleSignInLauncher.launch(client.signInIntent)
                                    } catch (e: Exception) {
                                        isGoogleLoading = false
                                        Toast.makeText(context, "Could not launch Google Sign In: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                    // Watchdog: Auto-reset loading state if user cancels or takes >12s
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(12000)
                                        if (isGoogleLoading) {
                                            isGoogleLoading = false
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = isGoogleLoading,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(200)) togetherWith
                                            fadeOut(animationSpec = tween(150))
                                },
                                label = "google_crossfade"
                            ) { loading ->
                                if (loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color(0xFF4CAF50),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (googleBitmap != null) {
                                            Image(
                                                bitmap = googleBitmap,
                                                contentDescription = "Google",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.AccountCircle,
                                                contentDescription = "Google",
                                                tint = Color(0xFF4285F4),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Google",
                                            color = Color(0xFF1F2937),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Continue with Email Button with Tactile Bounce
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .graphicsLayer {
                                    scaleX = emailScale
                                    scaleY = emailScale
                                }
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(16.dp))
                                .clickable(
                                    interactionSource = emailInteraction,
                                    indication = null,
                                    onClick = { showEmailDialog = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                if (mailBitmap != null) {
                                    Image(
                                        bitmap = mailBitmap,
                                        contentDescription = "Email",
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.MailOutline,
                                        contentDescription = "Email",
                                        tint = Color(0xFF1F2937),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Continue with Email",
                                    color = Color(0xFF1F2937),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Guest Mode: "Explore as Guest ›" with Tactile Bounce
                    Text(
                        text = "Explore as Guest ›",
                        color = Color(0xFF1E202C),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = guestScale
                                scaleY = guestScale
                            }
                            .clickable(
                                interactionSource = guestInteraction,
                                indication = null
                            ) {
                                UserSessionManager.saveSession(
                                    context = context,
                                    name = "Guest Explorer",
                                    identifier = "guest@inertiax.local",
                                    provider = AuthProviderType.GUEST
                                )
                                onLoginSuccess()
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Terms and Privacy Policy Text
                    val termsAnnotated = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF6C757D), fontSize = 11.sp)) {
                            append("By signing in, you agree to our ")
                        }
                        withStyle(SpanStyle(color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)) {
                            append("Terms")
                        }
                        withStyle(SpanStyle(color = Color(0xFF6C757D), fontSize = 11.sp)) {
                            append(" and ")
                        }
                        withStyle(SpanStyle(color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)) {
                            append("Privacy Policy")
                        }
                    }
                    Text(
                        text = termsAnnotated,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // ── Email Auth Dialog (Firebase Email / Password) ────────────────────
        if (showEmailDialog) {
            EmailAuthDialog(
                onDismiss = { showEmailDialog = false },
                onSuccess = { name, email ->
                    UserSessionManager.saveSession(
                        context = context,
                        name = name,
                        identifier = email,
                        provider = AuthProviderType.EMAIL
                    )
                    showEmailDialog = false
                    onLoginSuccess()
                }
            )
        }

        // ── Phone Auth Dialog (Firebase Phone OTP) ───────────────────────────
        if (showPhoneDialog) {
            PhoneAuthDialog(
                onDismiss = { showPhoneDialog = false },
                onSuccess = { phone ->
                    val user = FirebaseAuth.getInstance().currentUser
                    UserSessionManager.saveSession(
                        context = context,
                        name = user?.displayName ?: "Mobile User",
                        identifier = user?.phoneNumber ?: phone,
                        provider = AuthProviderType.PHONE
                    )
                    showPhoneDialog = false
                    onLoginSuccess()
                }
            )
        }

        // ── Firebase Configuration & Diagnostic Info Dialog ──────────────────
        if (showFirebaseConfigDialog) {
            FirebaseConfigDialog(
                initialMessage = configDialogMessage,
                onDismiss = {
                    showFirebaseConfigDialog = false
                    configDialogMessage = null
                }
            )
        }
    }
}

// ── Email Auth Dialog (Firebase Email & Password) ───────────────────────────
@Composable
fun EmailAuthDialog(
    onDismiss: () -> Unit,
    onSuccess: (name: String, email: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isSignUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    var dialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dialogVisible = true }
    val dialogScale by animateFloatAsState(
        targetValue = if (dialogVisible) 1f else 0.88f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f),
        label = "email_dlg_scale"
    )
    val dialogAlpha by animateFloatAsState(
        targetValue = if (dialogVisible) 1f else 0f,
        animationSpec = tween(220),
        label = "email_dlg_alpha"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    scaleX = dialogScale
                    scaleY = dialogScale
                    alpha = dialogAlpha
                }
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSignUp) "Create Account" else "Welcome Back",
                        color = Color(0xFF1E202C),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9E9E9E))
                    }
                }

                Text(
                    text = if (isSignUp)
                        "Register a new user directly in Firebase Authentication"
                    else
                        "Sign in to your registered Firebase account",
                    color = Color(0xFF6B7280),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Segmented Tab: Sign In vs Create Account
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF1F5F9))
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (!isSignUp) Color.White else Color.Transparent)
                            .clickable {
                                isSignUp = false
                                errorMessage = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Sign In",
                            color = if (!isSignUp) Color(0xFF1E202C) else Color(0xFF64748B),
                            fontSize = 13.sp,
                            fontWeight = if (!isSignUp) FontWeight.Bold else FontWeight.Medium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSignUp) Color.White else Color.Transparent)
                            .clickable {
                                isSignUp = true
                                errorMessage = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Create Account",
                            color = if (isSignUp) Color(0xFF1E202C) else Color(0xFF64748B),
                            fontSize = 13.sp,
                            fontWeight = if (isSignUp) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isSignUp) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; errorMessage = null },
                        label = { Text("Full Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF4CAF50)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color(0xFF212121),
                            unfocusedTextColor = Color(0xFF212121),
                            focusedLabelColor = Color(0xFF4CAF50)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; errorMessage = null },
                    label = { Text("Email Address") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = Color(0xFF4CAF50)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4CAF50),
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color(0xFF212121),
                        unfocusedTextColor = Color(0xFF212121),
                        focusedLabelColor = Color(0xFF4CAF50)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF4CAF50)) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFF9E9E9E)
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4CAF50),
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color(0xFF212121),
                        unfocusedTextColor = Color(0xFF212121),
                        focusedLabelColor = Color(0xFF4CAF50)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFFEBEE))
                            .border(1.dp, Color(0xFFFFCDD2), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFC62828),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val trimmedEmail = email.trim()
                        val trimmedPass = password
                        val trimmedName = name.trim()

                        if (trimmedEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
                            errorMessage = "Please enter a valid email address."
                            return@Button
                        }
                        if (trimmedPass.length < 6) {
                            errorMessage = "Password must be at least 6 characters."
                            return@Button
                        }
                        if (isSignUp && trimmedName.isEmpty()) {
                            errorMessage = "Please enter your full name."
                            return@Button
                        }

                        isLoading = true
                        errorMessage = null

                        // 10s Watchdog: auto-cancel spinner if network stalls
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(10000)
                            if (isLoading) {
                                isLoading = false
                                errorMessage = "Authentication request timed out. Please check your network connection and retry."
                            }
                        }

                        val auth = FirebaseAuth.getInstance()
                        if (isSignUp) {
                            auth.createUserWithEmailAndPassword(trimmedEmail, trimmedPass)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val user = task.result?.user
                                        // Update profile displayName asynchronously without blocking login
                                        if (trimmedName.isNotEmpty()) {
                                            val update = UserProfileChangeRequest.Builder()
                                                .setDisplayName(trimmedName)
                                                .build()
                                            user?.updateProfile(update)
                                        }
                                        isLoading = false
                                        Toast.makeText(context, "Account created in Firebase!", Toast.LENGTH_SHORT).show()
                                        onSuccess(trimmedName.ifEmpty { trimmedEmail.substringBefore("@") }, trimmedEmail)
                                    } else {
                                        isLoading = false
                                        val exc = task.exception
                                        val msg = exc?.localizedMessage ?: "Failed to create account."
                                        errorMessage = when {
                                            msg.contains("disabled", ignoreCase = true) || msg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                                                "⚠️ Email/Password sign-in is DISABLED in your Firebase Console!\n\nPlease open Firebase Console (inertiax-7f6b7) -> Authentication -> Sign-in method -> Enable 'Email/Password'."
                                            exc is FirebaseAuthUserCollisionException ->
                                                "This email is already registered in Firebase. Switch to the 'Sign In' tab above to log in."
                                            exc is FirebaseAuthWeakPasswordException ->
                                                "Password is too weak. Please use at least 6 characters."
                                            else -> msg
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    isLoading = false
                                    errorMessage = e.localizedMessage ?: "Network connection failed. Please retry."
                                }
                        } else {
                            auth.signInWithEmailAndPassword(trimmedEmail, trimmedPass)
                                .addOnCompleteListener { task ->
                                    isLoading = false
                                    if (task.isSuccessful) {
                                        val user = task.result?.user
                                        val displayName = user?.displayName ?: trimmedEmail.substringBefore("@")
                                        Toast.makeText(context, "Welcome back, $displayName!", Toast.LENGTH_SHORT).show()
                                        onSuccess(displayName, trimmedEmail)
                                    } else {
                                        val exc = task.exception
                                        val msg = exc?.localizedMessage ?: "Sign-in failed."
                                        errorMessage = when {
                                            msg.contains("disabled", ignoreCase = true) || msg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                                                "⚠️ Email/Password sign-in is DISABLED in your Firebase Console!\n\nPlease open Firebase Console (inertiax-7f6b7) -> Authentication -> Sign-in method -> Enable 'Email/Password'."
                                            exc is FirebaseAuthInvalidUserException ->
                                                "No account exists for this email.\nTap 'Create Account' tab above to register this user in Firebase."
                                            exc is FirebaseAuthInvalidCredentialsException ->
                                                "Incorrect email or password. Please verify and retry."
                                            else -> msg
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    isLoading = false
                                    errorMessage = e.localizedMessage ?: "Network connection failed. Please retry."
                                }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2C)),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (isSignUp) "Create Firebase Account" else "Sign In",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        isSignUp = !isSignUp
                        errorMessage = null
                    }) {
                        Text(
                            text = if (isSignUp) "Have an account? Sign In" else "New here? Create Account",
                            color = Color(0xFF4CAF50),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!isSignUp) {
                        TextButton(onClick = {
                            val trimmedEmail = email.trim()
                            if (trimmedEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
                                errorMessage = "Enter your email above to reset password."
                            } else {
                                FirebaseAuth.getInstance().sendPasswordResetEmail(trimmedEmail)
                                    .addOnCompleteListener { resetTask ->
                                        if (resetTask.isSuccessful) {
                                            Toast.makeText(context, "Password reset email sent to $trimmedEmail", Toast.LENGTH_LONG).show()
                                        } else {
                                            errorMessage = resetTask.exception?.localizedMessage ?: "Failed to send reset email."
                                        }
                                    }
                            }
                        }) {
                            Text(
                                text = "Forgot?",
                                color = Color(0xFF757575),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Phone Auth Dialog (Firebase Phone OTP) ───────────────────────────────────
@Composable
fun PhoneAuthDialog(
    onDismiss: () -> Unit,
    onSuccess: (phone: String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    var phone by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var isOtpSent by remember { mutableStateOf(false) }
    var resendSeconds by remember { mutableIntStateOf(60) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    var storedVerificationId by remember { mutableStateOf<String?>(null) }
    var resendToken by remember { mutableStateOf<PhoneAuthProvider.ForceResendingToken?>(null) }

    val focusManager = LocalFocusManager.current

    var dialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dialogVisible = true }
    val dialogScale by animateFloatAsState(
        targetValue = if (dialogVisible) 1f else 0.88f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f),
        label = "phone_dlg_scale"
    )
    val dialogAlpha by animateFloatAsState(
        targetValue = if (dialogVisible) 1f else 0f,
        animationSpec = tween(220),
        label = "phone_dlg_alpha"
    )

    LaunchedEffect(isOtpSent) {
        if (isOtpSent) {
            resendSeconds = 60
            while (resendSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                resendSeconds--
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    scaleX = dialogScale
                    scaleY = dialogScale
                    alpha = dialogAlpha
                }
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (!isOtpSent) "Phone Sign In" else "Enter OTP",
                        color = Color(0xFF1E202C),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9E9E9E))
                    }
                }

                Text(
                    text = if (!isOtpSent)
                        "Enter your mobile number with country code (e.g. +91...)"
                    else
                        "Code sent via SMS to $phone",
                    color = Color(0xFF6B7280),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                if (!isOtpSent) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it; errorMessage = null },
                        label = { Text("Phone Number") },
                        placeholder = { Text("+91 98765 43210") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = Color(0xFF4CAF50)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color(0xFF212121),
                            unfocusedTextColor = Color(0xFF212121),
                            focusedLabelColor = Color(0xFF4CAF50)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFFEBEE))
                                .border(1.dp, Color(0xFFFFCDD2), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = Color(0xFFC62828),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            var trimmed = phone.trim()
                            if (trimmed.isEmpty()) {
                                errorMessage = "Please enter a phone number."
                                return@Button
                            }
                            if (!trimmed.startsWith("+")) {
                                trimmed = if (trimmed.length == 10) "+91$trimmed" else "+$trimmed"
                            }
                            phone = trimmed

                            isLoading = true
                            errorMessage = null

                            // 15s Watchdog: auto-cancel spinner if SMS verification stalls
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(15000)
                                if (isLoading && !isOtpSent) {
                                    isLoading = false
                                    errorMessage = "SMS verification request timed out. Please check your cellular connection or ensure Phone Auth is enabled in Firebase Console."
                                }
                            }

                            if (activity == null) {
                                isLoading = false
                                errorMessage = "Activity context is not available for phone authentication."
                                return@Button
                            }

                            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                                    FirebaseAuth.getInstance().signInWithCredential(credential)
                                        .addOnCompleteListener { task ->
                                            isLoading = false
                                            if (task.isSuccessful) {
                                                val user = task.result?.user
                                                Toast.makeText(context, "Phone verified in Firebase!", Toast.LENGTH_SHORT).show()
                                                onSuccess(user?.phoneNumber ?: trimmed)
                                            } else {
                                                errorMessage = task.exception?.localizedMessage ?: "Firebase sign-in failed."
                                            }
                                        }
                                }

                                override fun onVerificationFailed(e: FirebaseException) {
                                    isLoading = false
                                    val msg = e.localizedMessage ?: "Verification failed."
                                    errorMessage = when {
                                        msg.contains("disabled", ignoreCase = true) || msg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                                            "⚠️ Phone sign-in is DISABLED in your Firebase Console!\n\nOpen Firebase Console (inertiax-7f6b7) -> Authentication -> Sign-in method -> Enable 'Phone'."
                                        msg.contains("not authorized", ignoreCase = true) || msg.contains("App verification", ignoreCase = true) ->
                                            "⚠️ Firebase App Verification failed.\n\nAdd your SHA-256 fingerprint in Firebase Console, OR add a test phone number (e.g. +91 9999999999, code 123456) in Firebase Console under Authentication > Phone."
                                        msg.contains("quota", ignoreCase = true) ->
                                            "SMS quota exceeded. Please configure a test phone number in Firebase Console."
                                        else -> msg
                                    }
                                }

                                override fun onCodeSent(
                                    verificationId: String,
                                    token: PhoneAuthProvider.ForceResendingToken
                                ) {
                                    isLoading = false
                                    storedVerificationId = verificationId
                                    resendToken = token
                                    isOtpSent = true
                                    Toast.makeText(context, "Verification code sent via SMS!", Toast.LENGTH_SHORT).show()
                                }
                            }

                            try {
                                val options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                                    .setPhoneNumber(trimmed)
                                    .setTimeout(60L, TimeUnit.SECONDS)
                                    .setActivity(activity)
                                    .setCallbacks(callbacks)
                                    .build()

                                PhoneAuthProvider.verifyPhoneNumber(options)
                            } catch (e: Exception) {
                                isLoading = false
                                errorMessage = "Could not start phone verification: ${e.localizedMessage}"
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2C)),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = "Send Verification Code",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = {
                            if (it.length <= 6) {
                                otpCode = it
                                errorMessage = null
                            }
                        },
                        label = { Text("6-Digit OTP") },
                        placeholder = { Text("123456") },
                        leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, tint = Color(0xFF4CAF50)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color(0xFF212121),
                            unfocusedTextColor = Color(0xFF212121),
                            focusedLabelColor = Color(0xFF4CAF50)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFFEBEE))
                                .border(1.dp, Color(0xFFFFCDD2), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = Color(0xFFC62828),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (otpCode.length != 6) {
                                errorMessage = "Please enter the 6-digit code."
                                return@Button
                            }
                            val vId = storedVerificationId ?: run {
                                errorMessage = "Verification session expired. Request a new OTP."
                                return@Button
                            }

                            isLoading = true
                            errorMessage = null

                            // 10s Watchdog: auto-cancel spinner if OTP verification stalls
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(10000)
                                if (isLoading) {
                                    isLoading = false
                                    errorMessage = "Verification timed out. Please check your connection and retry."
                                }
                            }

                            try {
                                val credential = PhoneAuthProvider.getCredential(vId, otpCode)
                                FirebaseAuth.getInstance().signInWithCredential(credential)
                                    .addOnCompleteListener { task ->
                                        isLoading = false
                                        if (task.isSuccessful) {
                                            val user = task.result?.user
                                            Toast.makeText(context, "Phone verified in Firebase!", Toast.LENGTH_SHORT).show()
                                            onSuccess(user?.phoneNumber ?: phone)
                                        } else {
                                            errorMessage = task.exception?.localizedMessage ?: "Invalid verification code. Please check and retry."
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        isLoading = false
                                        errorMessage = e.localizedMessage ?: "Verification network failure. Please retry."
                                    }
                            } catch (e: Exception) {
                                isLoading = false
                                errorMessage = "Verification failed: ${e.localizedMessage}"
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2C)),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = "Verify & Sign In",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { isOtpSent = false },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Change Number", color = Color(0xFF757575), fontSize = 12.sp)
                        }

                        TextButton(
                            onClick = {
                                if (resendSeconds == 0 && activity != null) {
                                    isLoading = true
                                    val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                                        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                                            FirebaseAuth.getInstance().signInWithCredential(credential)
                                                .addOnCompleteListener { task ->
                                                    isLoading = false
                                                    if (task.isSuccessful) {
                                                        val user = task.result?.user
                                                        onSuccess(user?.phoneNumber ?: phone)
                                                    }
                                                }
                                        }

                                        override fun onVerificationFailed(e: FirebaseException) {
                                            isLoading = false
                                            errorMessage = e.localizedMessage ?: "Failed to resend OTP."
                                        }

                                        override fun onCodeSent(
                                            verificationId: String,
                                            token: PhoneAuthProvider.ForceResendingToken
                                        ) {
                                            isLoading = false
                                            storedVerificationId = verificationId
                                            resendToken = token
                                            resendSeconds = 60
                                            Toast.makeText(context, "New code sent via SMS!", Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                    val optionsBuilder = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                                        .setPhoneNumber(phone.trim())
                                        .setTimeout(60L, TimeUnit.SECONDS)
                                        .setActivity(activity)
                                        .setCallbacks(callbacks)
                                    if (resendToken != null) {
                                        optionsBuilder.setForceResendingToken(resendToken!!)
                                    }
                                    PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
                                }
                            },
                            enabled = resendSeconds == 0,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = if (resendSeconds > 0) "Resend OTP (${resendSeconds}s)" else "Resend OTP",
                                color = if (resendSeconds == 0) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Firebase Configuration & Diagnostic Info Dialog ─────────────────────────
@Composable
fun FirebaseConfigDialog(
    initialMessage: String? = null,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val sha1 = "2A:1D:DF:27:A9:11:68:D9:EA:2C:66:1D:47:EE:95:39:9C:C3:5D:CB"
    val sha256 = "F4:19:C5:AF:3B:48:AE:B2:88:F9:55:A9:27:DE:BC:A5:3E:3D:C0:1B:AB:CA:61:06:41:20:64:8A:C5:F4:7A:0C"
    val projectId = "inertiax-7f6b7"
    val pkgName = "com.sleepingheads.sihpro"

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Firebase Auth Status",
                            color = Color(0xFF1E202C),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9E9E9E))
                    }
                }

                if (!initialMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFFF3E0))
                            .border(1.dp, Color(0xFFFFB74D), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = initialMessage,
                            color = Color(0xFFE65100),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Project info card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text("PROJECT & APP INFO", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Project ID: $projectId", color = Color(0xFF1E293B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Package: $pkgName", color = Color(0xFF1E293B), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (currentUser != null)
                            "Firebase Auth: Signed in as ${currentUser.email ?: currentUser.phoneNumber ?: currentUser.uid}"
                        else
                            "Firebase Auth: No active user signed in",
                        color = if (currentUser != null) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // SHA-1 Card with Copy button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF1F5F9))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DEBUG SHA-1 FINGERPRINT", color = Color(0xFF475569), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(sha1))
                                Toast.makeText(context, "SHA-1 copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Copy", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(sha1, color = Color(0xFF0F172A), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // SHA-256 Card with Copy button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF1F5F9))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DEBUG SHA-256 FINGERPRINT", color = Color(0xFF475569), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(sha256))
                                Toast.makeText(context, "SHA-256 copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Copy", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(sha256, color = Color(0xFF0F172A), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "FIREBASE CONSOLE CHECKLIST:\n" +
                            "1. Open console.firebase.google.com -> inertiax-7f6b7\n" +
                            "2. Go to Authentication -> Sign-in method\n" +
                            "3. Enable 'Email/Password'\n" +
                            "4. Enable 'Google' (add SHA-1 to Project Settings)\n" +
                            "5. Enable 'Phone' (add a test phone number for instant test)",
                    color = Color(0xFF4B5563),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2C))
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
