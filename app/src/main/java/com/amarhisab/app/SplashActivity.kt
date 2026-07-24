package com.amarhisab.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * First screen shown on app launch.
 * Displays branding briefly, then hands off to MainActivity (the WebView host).
 */
class SplashActivity : AppCompatActivity() {

    private val splashDelayMs = 1200L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, splashDelayMs)
    }
}
