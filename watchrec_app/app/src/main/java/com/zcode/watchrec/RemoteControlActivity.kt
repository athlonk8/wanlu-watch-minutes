package com.zcode.watchrec

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * 电脑遥控入口(无界面):adb shell am start
 *   -n com.zcode.watchrec/.RemoteControlActivity --es action start
 *   -n com.zcode.watchrec/.RemoteControlActivity --es action stop
 */
class RemoteControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent?.getStringExtra("action") == "stop") {
                startService(
                    Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
                )
            } else {
                ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java))
            }
        } catch (_: Exception) {
        }
        finish()
    }
}
