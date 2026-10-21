package com.sleepingheads.sihpro.session

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.firebase.auth.FirebaseAuth
import java.io.File

enum class AuthProviderType {
    GUEST, GOOGLE, EMAIL, PHONE
}

data class UserProfile(
    val name: String,
    val identifier: String,
    val provider: AuthProviderType,
    val isLoggedIn: Boolean,
    val photoUrl: String? = null,
    val customAvatarPath: String? = null
)

object UserSessionManager {
    private const val PREF_NAME = "inertiax_auth_session"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_IDENTIFIER = "identifier"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_PHOTO_URL = "photo_url"
    private const val KEY_CUSTOM_AVATAR_PATH = "custom_avatar_path"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(context: Context): Boolean {
        val fbUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (fbUser != null) return true
        return getPrefs(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun getCurrentUser(context: Context): UserProfile {
        val prefs = getPrefs(context)
        val customAvatarPath = prefs.getString(KEY_CUSTOM_AVATAR_PATH, null)
        val googleAccount = try { GoogleSignIn.getLastSignedInAccount(context) } catch (_: Exception) { null }

        val fbUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (fbUser != null) {
            val providerType = when {
                fbUser.providerData.any { it.providerId == "google.com" } -> AuthProviderType.GOOGLE
                fbUser.providerData.any { it.providerId == "phone" } -> AuthProviderType.PHONE
                fbUser.providerData.any { it.providerId == "password" } -> AuthProviderType.EMAIL
                googleAccount != null -> AuthProviderType.GOOGLE
                else -> {
                    val savedProv = prefs.getString(KEY_PROVIDER, null)
                    if (savedProv != null) {
                        try { AuthProviderType.valueOf(savedProv) } catch (_: Exception) { AuthProviderType.GUEST }
                    } else AuthProviderType.GUEST
                }
            }
            val identifier = fbUser.email 
                ?: fbUser.phoneNumber 
                ?: googleAccount?.email
                ?: prefs.getString(KEY_IDENTIFIER, "user@inertiax.com") 
                ?: "user@inertiax.com"

            val displayName = fbUser.displayName 
                ?: googleAccount?.displayName
                ?: if (fbUser.email != null) fbUser.email!!.substringBefore("@") 
                else prefs.getString(KEY_USER_NAME, "Active User") 
                ?: "Active User"

            val googlePhoto = googleAccount?.photoUrl?.toString() 
                ?: fbUser.photoUrl?.toString() 
                ?: fbUser.providerData.firstNotNullOfOrNull { it.photoUrl?.toString() }
                ?: prefs.getString(KEY_PHOTO_URL, null)

            return UserProfile(
                name = displayName,
                identifier = identifier,
                provider = providerType,
                isLoggedIn = true,
                photoUrl = googlePhoto,
                customAvatarPath = customAvatarPath
            )
        }

        // Check if there is an active Google Account logged in locally
        if (googleAccount != null) {
            return UserProfile(
                name = googleAccount.displayName ?: "Google User",
                identifier = googleAccount.email ?: "google@inertiax.com",
                provider = AuthProviderType.GOOGLE,
                isLoggedIn = true,
                photoUrl = googleAccount.photoUrl?.toString() ?: prefs.getString(KEY_PHOTO_URL, null),
                customAvatarPath = customAvatarPath
            )
        }

        val isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        val name = prefs.getString(KEY_USER_NAME, "Guest Explorer") ?: "Guest Explorer"
        val identifier = prefs.getString(KEY_IDENTIFIER, "guest@inertiax.local") ?: "guest@inertiax.local"
        val providerStr = prefs.getString(KEY_PROVIDER, AuthProviderType.GUEST.name) ?: AuthProviderType.GUEST.name
        val provider = try { AuthProviderType.valueOf(providerStr) } catch (_: Exception) { AuthProviderType.GUEST }
        val photoUrl = prefs.getString(KEY_PHOTO_URL, null)

        return UserProfile(
            name = name,
            identifier = identifier,
            provider = provider,
            isLoggedIn = isLoggedIn,
            photoUrl = photoUrl,
            customAvatarPath = customAvatarPath
        )
    }

    fun saveSession(
        context: Context,
        name: String,
        identifier: String,
        provider: AuthProviderType,
        photoUrl: String? = null
    ) {
        val editor = getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_NAME, name)
            .putString(KEY_IDENTIFIER, identifier)
            .putString(KEY_PROVIDER, provider.name)

        if (photoUrl != null) {
            editor.putString(KEY_PHOTO_URL, photoUrl)
        } else {
            editor.remove(KEY_PHOTO_URL)
        }
        editor.apply()
    }

    fun copyUriToInternalAvatar(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val avatarFile = File(context.filesDir, "user_avatar_${System.currentTimeMillis()}.jpg")
            // Clean up any old avatar file
            context.filesDir.listFiles { _, name -> name.startsWith("user_avatar_") }?.forEach { oldFile ->
                try { oldFile.delete() } catch (_: Exception) {}
            }
            avatarFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            val path = avatarFile.absolutePath
            getPrefs(context).edit().putString(KEY_CUSTOM_AVATAR_PATH, path).apply()
            path
        } catch (e: Exception) {
            android.util.Log.e("UserSessionManager", "Error saving custom avatar: ${e.message}", e)
            null
        }
    }

    fun removeCustomAvatar(context: Context) {
        val prefs = getPrefs(context)
        val oldPath = prefs.getString(KEY_CUSTOM_AVATAR_PATH, null)
        if (oldPath != null) {
            try { File(oldPath).delete() } catch (_: Exception) {}
        }
        prefs.edit().remove(KEY_CUSTOM_AVATAR_PATH).apply()
    }

    fun logout(context: Context) {
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}

        try {
            val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
            ).build()
            GoogleSignIn.getClient(context, gso).signOut()
        } catch (_: Exception) {}

        removeCustomAvatar(context)

        getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_USER_NAME)
            .remove(KEY_IDENTIFIER)
            .remove(KEY_PROVIDER)
            .remove(KEY_PHOTO_URL)
            .remove(KEY_CUSTOM_AVATAR_PATH)
            .apply()
    }
}
