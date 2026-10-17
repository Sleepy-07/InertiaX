package com.sleepingheads.sihpro.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sleepingheads.sihpro.MainActivity
import com.sleepingheads.sihpro.session.AuthProviderType
import com.sleepingheads.sihpro.session.UserSessionManager
import com.sleepingheads.sihpro.ui.theme.SIHProTheme

class PhoneAuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SIHProTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B0E14))
                ) {
                    PhoneAuthDialog(
                        onDismiss = { finish() },
                        onSuccess = { phone ->
                            UserSessionManager.saveSession(
                                context = this@PhoneAuthActivity,
                                name = "Mobile User",
                                identifier = phone,
                                provider = AuthProviderType.PHONE
                            )
                            startActivity(Intent(this@PhoneAuthActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            finish()
                        }
                    )
                }
            }
        }
    }
}
