package com.mcity.palm.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.nio.ByteBuffer

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val respCmd = byteArrayOf(0x0B) +
                        ByteBuffer.allocate(2).putInt(0).array() +
                        ByteBuffer.allocate(8).putLong(94353247925070122).array() +
                        byteArrayOf(0x03) +
                        byteArrayOf(0x01,0x00,0x01)
                val pkt = DatagramPacket(bytes, bytes.size)
                socket.send(pkt)
            }
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
