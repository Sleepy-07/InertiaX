package com.sleepingheads.sihpro.session

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth

enum class AuthProviderType {
    GUEST, GOOGLE, EMAIL, PHONE
}

data class UserProfile(
    val name: String,
    val identifier: String,
    val provider: AuthProviderType,
    val isLoggedIn: Boolean
)

object UserSessionManager {
    private const val PREF_NAME = "inertiax_auth_session"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_IDENTIFIER = "identifier"
    private const val KEY_PROVIDER = "provider"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(context: Context): Boolean {
        val fbUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (fbUser != null) return true
        return getPrefs(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun getCurrentUser(context: Context): UserProfile {
        val fbUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (fbUser != null) {
            val providerType = when {
                fbUser.providerData.any { it.providerId == "google.com" } -> AuthProviderType.GOOGLE
                fbUser.providerData.any { it.providerId == "phone" } -> AuthProviderType.PHONE
                fbUser.providerData.any { it.providerId == "password" } -> AuthProviderType.EMAIL
                else -> AuthProviderType.GUEST
            }
            val identifier = fbUser.email ?: fbUser.phoneNumber ?: "user@inertiax.com"
            val displayName = fbUser.displayName ?: if (fbUser.email != null) fbUser.email!!.substringBefore("@") else "Active User"
            return UserProfile(
                name = displayName,
                identifier = identifier,
                provider = providerType,
                isLoggedIn = true
            )
        }

        val prefs = getPrefs(context)
        val isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        val name = prefs.getString(KEY_USER_NAME, "Guest Explorer") ?: "Guest Explorer"
        val identifier = prefs.getString(KEY_IDENTIFIER, "guest@inertiax.local") ?: "guest@inertiax.local"
        val providerStr = prefs.getString(KEY_PROVIDER, AuthProviderType.GUEST.name) ?: AuthProviderType.GUEST.name
        val provider = try { AuthProviderType.valueOf(providerStr) } catch (_: Exception) { AuthProviderType.GUEST }
        return UserProfile(name = name, identifier = identifier, provider = provider, isLoggedIn = isLoggedIn)
    }

    fun saveSession(context: Context, name: String, identifier: String, provider: AuthProviderType) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_NAME, name)
            .putString(KEY_IDENTIFIER, identifier)
            .putString(KEY_PROVIDER, provider.name)
            .apply()
    }

    fun logout(context: Context) {
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}
        getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_USER_NAME)
            .remove(KEY_IDENTIFIER)
            .remove(KEY_PROVIDER)
            .apply()
    }
}
