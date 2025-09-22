package com.mcity.palm.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blankj.utilcode.util.ShellUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val respCmd = byteArrayOf(0x0B,0x00,0x00) +
                        ByteBuffer.allocate(8).putLong(869604080824047).array() +
                        byteArrayOf(0x00,0x02,0x01)
                val socket = DatagramSocket()
                val pkt = DatagramPacket(respCmd, respCmd.size,InetAddress.getByName(Constants.remoteHost),Constants.remotePort)
                socket.send(pkt)
            }
//            val script = "/data/local/tmp/boot_headless.sh"
//            val log = "/data/local/shared/boot_headless.log"
//            ShellUtils.execCmd("nohup $script >$log",true)
        }
    }
}
