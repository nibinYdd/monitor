package com.mcity.palm.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // 启动 root 脚本，使其脱离父进程（nohup &）
            val script = "/data/local/tmp/boot_headless.sh"
            val log = "/data/local/shared/boot_headless.log"
            val cmd = arrayOf("su", "-c", "nohup $script >$log 2>&1 & echo \$!")
            try {
                val p = Runtime.getRuntime().exec(cmd)
                val pid = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                // 可写入pid文件用于后续调试
                Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $pid > /data/local/tmp/boot_headless.pid"))
            } catch (e: IOException) {
                Log.e("BootReceiver", "IOException when executing boot script", e)
            }
        }
    }
}
