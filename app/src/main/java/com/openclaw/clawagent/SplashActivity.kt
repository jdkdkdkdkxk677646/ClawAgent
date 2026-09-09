package com.openclaw.clawagent

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash screen activity shown on app launch.
 * Displays the Claw logo and transitions to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    private val splashTimeout: Long = 1500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, splashTimeout)
    }
}
