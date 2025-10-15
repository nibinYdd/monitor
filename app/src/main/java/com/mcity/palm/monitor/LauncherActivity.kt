package com.mcity.palm.monitor

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.blankj.utilcode.util.ServiceUtils

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("","...............LauncherActivity.....................")
        // 启动后台服务
        ServiceUtils.startService(HeadlessService::class.java)
        finish() // 马上关闭自己
    }
}
