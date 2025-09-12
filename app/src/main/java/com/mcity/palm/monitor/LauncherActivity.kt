package com.mcity.palm.monitor

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动后台服务
        val intent = Intent(this, HeadlessService::class.java)
        startForegroundService(intent)
        finish() // 马上关闭自己
    }
}
