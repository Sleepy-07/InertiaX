package com.sleepingheads.sihpro.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepingheads.sihpro.network.GeoReckonConnectionState
import com.sleepingheads.sihpro.session.AuthProviderType
import com.sleepingheads.sihpro.session.UserProfile
import com.sleepingheads.sihpro.session.UserSessionManager
import com.sleepingheads.sihpro.ui.navigation.NavigationViewModel
import com.sleepingheads.sihpro.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// In-memory cache for remote avatars (e.g. Google profile pictures)
private object AvatarMemoryCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 16
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun get(url: String): Bitmap? = memoryCache.get(url)
    fun put(url: String, bitmap: Bitmap) {
        if (get(url) == null) {
            memoryCache.put(url, bitmap)
        }
    }
}

@Composable
fun AsyncAvatar(
    sourceUrl: String?,
    localPath: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 100
) {
    var bitmap by remember(sourceUrl, localPath) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(sourceUrl, localPath) { mutableStateOf(false) }

    LaunchedEffect(sourceUrl, localPath) {
        if (!localPath.isNullOrEmpty()) {
            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    val file = File(localPath)
                    if (file.exists()) {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                        withContext(Dispatchers.Main) {
                            bitmap = decoded
                        }
                    } else {
                        withContext(Dispatchers.Main) { bitmap = null }
                    }
                } catch (e: Exception) {
                    Log.e("AsyncAvatar", "Failed to load local avatar: ${e.message}")
                    withContext(Dispatchers.Main) { bitmap = null }
                } finally {
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            }
        } else if (!sourceUrl.isNullOrEmpty()) {
            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    val cached = AvatarMemoryCache.get(sourceUrl)
                    if (cached != null) {
                        withContext(Dispatchers.Main) {
                            bitmap = cached
                            isLoading = false
                        }
                        return@withContext
                    }
                    val url = URL(sourceUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 7000
                    conn.readTimeout = 7000
                    conn.doInput = true
                    conn.connect()
                    val stream = conn.inputStream
                    val decoded = BitmapFactory.decodeStream(stream)
                    stream.close()
                    if (decoded != null) {
                        AvatarMemoryCache.put(sourceUrl, decoded)
                        withContext(Dispatchers.Main) {
                            bitmap = decoded
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AsyncAvatar", "Failed to fetch remote avatar: ${e.message}")
                } finally {
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            }
        } else {
            bitmap = null
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size((size / 3).dp),
                    color = Color(0xFF00E676),
                    strokeWidth = 2.dp
                )
            }
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Avatar of $name",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            else -> {
                val initials = name.trim().split(" ")
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .map { it.first().uppercaseChar() }
                    .joinToString("")
                    .ifEmpty { "U" }

                Text(
                    text = initials,
                    color = Color(0xFF00E676),
                    fontSize = (size * 0.38f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun ProfileScreen(
    onNavigateToLogin: () -> Unit,
    onLogout: () -> Unit,
    viewModel: NavigationViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var userProfile by remember { mutableStateOf(UserSessionManager.getCurrentUser(context)) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // Launcher for selecting a profile picture (Email & Phone users)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    UserSessionManager.copyUriToInternalAvatar(context, uri)
                }
                if (savedPath != null) {
                    userProfile = UserSessionManager.getCurrentUser(context)
                    Toast.makeText(context, "Profile photo updated successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to save profile photo. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val isDark = ThemeManager.isDarkTheme.value
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val screenBg = if (isDark) Color(0xFF0B0E14) else Color(0xFFF8FAFC)
    val cardBg = if (isDark) Color(0xFF12161F) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color(0xFF1E2838) else Color(0xFFE2E8F0)
    val textPrimary = if (isDark) Color.White else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF90A4AE) else Color(0xFF64748B)
    val textMuted = if (isDark) Color(0xFF546E7A) else Color(0xFF94A3B8)
    val dividerColor = if (isDark) Color(0xFF1C2433) else Color(0xFFE2E8F0)

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Top Bar / Header ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Operator Profile",
                    color = textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "InertiaX Telematics & Dead-Reckoning",
                    color = textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Session Security Badge
            Surface(
                color = when (userProfile.provider) {
                    AuthProviderType.GOOGLE -> Color(0x224285F4)
                    AuthProviderType.EMAIL -> Color(0x2200E5FF)
                    AuthProviderType.PHONE -> Color(0x229C27B0)
                    AuthProviderType.GUEST -> Color(0x22FF9100)
                },
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (userProfile.provider) {
                        AuthProviderType.GOOGLE -> Color(0xFF4285F4)
                        AuthProviderType.EMAIL -> Color(0xFF00E5FF)
                        AuthProviderType.PHONE -> Color(0xFFBA68C8)
                        AuthProviderType.GUEST -> Color(0xFFFF9100)
                    }.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                when (userProfile.provider) {
                                    AuthProviderType.GOOGLE -> Color(0xFF4285F4)
                                    AuthProviderType.EMAIL -> Color(0xFF00E5FF)
                                    AuthProviderType.PHONE -> Color(0xFFBA68C8)
                                    AuthProviderType.GUEST -> Color(0xFFFF9100)
                                },
                                CircleShape
                            )
                    )
                    Text(
                        text = when (userProfile.provider) {
                            AuthProviderType.GOOGLE -> "Google Auth"
                            AuthProviderType.EMAIL -> "Verified Email"
                            AuthProviderType.PHONE -> "Phone OTP"
                            AuthProviderType.GUEST -> "Guest Session"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }


        // ── Avatar & Identity Card ─────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isDark) {
                    Brush.linearGradient(listOf(Color(0xFF263345), Color(0xFF161E2C)))
                } else {
                    Brush.linearGradient(listOf(Color(0xFFE2E8F0), Color(0xFFCBD5E1)))
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Avatar with border & indicator
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(108.dp)
                            .border(
                                width = 3.dp,
                                brush = Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF00E676),
                                        Color(0xFF00E5FF),
                                        Color(0xFF4285F4),
                                        Color(0xFF00E676)
                                    )
                                ),
                                shape = CircleShape
                            )
                            .padding(4.dp)
                    ) {
                        AsyncAvatar(
                            sourceUrl = if (userProfile.provider == AuthProviderType.GOOGLE) userProfile.photoUrl else null,
                            localPath = if (userProfile.provider != AuthProviderType.GOOGLE) userProfile.customAvatarPath else null,
                            name = userProfile.name,
                            size = 100
                        )
                    }

                    // Bottom-Right Badge depending on provider
                    when (userProfile.provider) {
                        AuthProviderType.GOOGLE -> {
                            // Google Verified Badge
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF))
                                    .border(2.dp, Color(0xFF4285F4), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "G",
                                    color = Color(0xFF4285F4),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 15.sp
                                )
                            }
                        }
                        AuthProviderType.EMAIL, AuthProviderType.PHONE -> {
                            // Camera action button to upload/change image
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                                    .border(2.dp, if (isDark) Color(0xFF0B0E14) else Color(0xFFFFFFFF), CircleShape)
                                    .clickable { photoPickerLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Upload or Change Profile Photo",
                                    tint = Color(0xFF0B0E14),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        AuthProviderType.GUEST -> {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) Color(0xFF263345) else Color(0xFFFFF3E0))
                                    .border(2.dp, Color(0xFFFF9100), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonOutline,
                                    contentDescription = "Guest",
                                    tint = Color(0xFFFF9100),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Name and Identifier Text
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = userProfile.name,
                        color = textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = userProfile.identifier,
                        color = textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Sub-Actions according to Auth Type:
                when (userProfile.provider) {
                    AuthProviderType.GOOGLE -> {
                        // Google account notice
                        Surface(
                            color = Color(0x154285F4),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x334285F4))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4285F4),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Google Account Profile Photo Synced",
                                    color = Color(0xFF90CAF9),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    AuthProviderType.EMAIL, AuthProviderType.PHONE -> {
                        // Options to Add, Change, or Remove Profile Image
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { photoPickerLauncher.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                                    contentColor = if (isDark) Color(0xFF00E676) else Color(0xFF059669)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isDark) Color(0xFF00E676).copy(alpha = 0.4f) else Color(0xFF059669).copy(alpha = 0.4f)
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddAPhoto,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (userProfile.customAvatarPath != null) "Change Photo" else "Add Profile Photo",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (userProfile.customAvatarPath != null) {
                                OutlinedButton(
                                    onClick = {
                                        UserSessionManager.removeCustomAvatar(context)
                                        userProfile = UserSessionManager.getCurrentUser(context)
                                        Toast.makeText(context, "Profile photo removed", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFFFF5252)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FF5252)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "Remove", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    AuthProviderType.GUEST -> {
                        // Prominent Guest Alert Banner inside card
                        Surface(
                            color = Color(0x1AFF9100),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FF9100))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFFF9100),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Temporary Session • Cloud Sync Disabled",
                                    color = if (isDark) Color(0xFFFFCC80) else Color(0xFFD97706),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Guest Callout / Login Prompt (When User is Guest) ─────────────
        if (userProfile.provider == AuthProviderType.GUEST || !userProfile.isLoggedIn) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF131D24) else Color(0xFFFFFFFF)
                ),
                shape = RoundedCornerShape(20.dp),
                border = if (isDark) {
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(Color(0xFF00E676), Color(0xFF00E5FF))
                        )
                    )
                } else {
                    androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                }
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x3300E676)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Sign In or Create Account",
                                color = textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Unlock full InertiaX telematics cloud syncing",
                                color = textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Button(
                        onClick = onNavigateToLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E676),
                            contentColor = Color(0xFF0B0E14)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Login / Sign Up Now",
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // ── Display & Theme Settings ──────────────────────────────────────
        Text(
            text = "DISPLAY & THEME",
            color = textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThemeOptionTile(
                        title = "Dark Theme",
                        subtitle = "Deep OLED contrast",
                        icon = Icons.Default.DarkMode,
                        isSelected = isDark,
                        activeColor = Color(0xFF00E5FF),
                        isDarkSystem = isDark,
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            ThemeManager.setDarkTheme(context, true)
                        },
                        modifier = Modifier.weight(1f)
                    )

                    ThemeOptionTile(
                        title = "White Theme",
                        subtitle = "Crisp daytime clarity",
                        icon = Icons.Default.LightMode,
                        isSelected = !isDark,
                        activeColor = Color(0xFF00A86B),
                        isDarkSystem = isDark,
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            ThemeManager.setDarkTheme(context, false)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Telematics & Engine Specs Grid ────────────────────────────────
        Text(
            text = "TELEMATICS CREDENTIALS",
            color = textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileStatCard(
                title = "Edge Engine",
                value = "KinoNet-R2",
                subtitle = "ONNX / TFLite IMU",
                icon = Icons.Default.Sensors,
                accentColor = Color(0xFF00E676),
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
            ProfileStatCard(
                title = "Security",
                value = "AES-256",
                subtitle = "Encrypted Sensor Bus",
                icon = Icons.Default.Security,
                accentColor = Color(0xFF00E5FF),
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileStatCard(
                title = "Architecture",
                value = "SIH26168",
                subtitle = "Dead Reckoning MVP",
                icon = Icons.Default.Speed,
                accentColor = Color(0xFFFF9100),
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
            ProfileStatCard(
                title = "Telemetry",
                value = if (userProfile.provider == AuthProviderType.GUEST) "Local Only" else "Hybrid Ready",
                subtitle = "Dual Pipe Pipeline",
                icon = Icons.Default.CloudQueue,
                accentColor = Color(0xFFBA68C8),
                isDark = isDark,
                modifier = Modifier.weight(1f)
            )
        }

        // ── Account Actions Card ──────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Account Provider Info Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = when (userProfile.provider) {
                                AuthProviderType.GOOGLE -> Icons.Default.AccountCircle
                                AuthProviderType.EMAIL -> Icons.Default.Email
                                AuthProviderType.PHONE -> Icons.Default.Phone
                                AuthProviderType.GUEST -> Icons.Default.PersonOutline
                            },
                            contentDescription = null,
                            tint = textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Authentication Method",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = userProfile.provider.name,
                        color = Color(0xFF00E676),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = dividerColor)

                // App Build Info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "System Build",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "InertiaX v1.0.0-PROD",
                        color = textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }

        // ── Sign Out / Switch User Button ─────────────────────────────────
        OutlinedButton(
            onClick = { showLogoutDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFFF5252)
            ),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x55FF5252))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (userProfile.provider == AuthProviderType.GUEST) "Exit Guest Session" else "Sign Out of Account",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(36.dp))
    }

    // ── Logout Confirmation Dialog ────────────────────────────────────────
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = cardBg,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = if (userProfile.provider == AuthProviderType.GUEST) "Exit Guest Session?" else "Sign Out?",
                    color = textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = if (userProfile.provider == AuthProviderType.GUEST) {
                        "Are you sure you want to end your guest session? Any unsaved local telematics will be reset."
                    } else {
                        "Are you sure you want to sign out from ${userProfile.name}? You will need to log back in to access your profile."
                    },
                    color = textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        UserSessionManager.logout(context)
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Confirm Sign Out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutDialog = false }
                ) {
                    Text("Cancel", color = textSecondary)
                }
            }
        )
    }
}

@Composable
private fun ThemeOptionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    activeColor: Color,
    isDarkSystem: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileBg = if (isSelected) {
        if (isDarkSystem) activeColor.copy(alpha = 0.15f) else activeColor.copy(alpha = 0.10f)
    } else {
        if (isDarkSystem) Color(0xFF0D1117) else Color(0xFFF1F5F9)
    }

    val tileBorder = if (isSelected) {
        activeColor
    } else {
        if (isDarkSystem) Color(0xFF1E2838) else Color(0xFFCBD5E1)
    }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(containerColor = tileBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(if (isSelected) 2.dp else 1.dp, tileBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) activeColor else (if (isDarkSystem) Color(0xFF1B2230) else Color(0xFFE2E8F0))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) {
                            if (isDarkSystem) Color(0xFF0B0E14) else Color.White
                        } else {
                            if (isDarkSystem) Color(0xFF90A4AE) else Color(0xFF64748B)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(activeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = if (isDarkSystem) Color(0xFF0B0E14) else Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Text(
                text = title,
                color = if (isSelected) {
                    if (isDarkSystem) Color.White else Color(0xFF0F172A)
                } else {
                    if (isDarkSystem) Color(0xFFB0BEC5) else Color(0xFF334155)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = subtitle,
                color = if (isDarkSystem) Color(0xFF78909C) else Color(0xFF64748B),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun ProfileStatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isDark: Boolean = ThemeManager.isDarkTheme.value,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF12161F) else Color(0xFFFFFFFF)
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDark) Color(0xFF1C2433) else Color(0xFFE2E8F0)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(accentColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                color = if (isDark) Color.White else Color(0xFF0F172A),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Column {
                Text(
                    text = title,
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = if (isDark) Color(0xFF78909C) else Color(0xFF64748B),
                    fontSize = 10.sp
                )
            }
        }
    }
}
