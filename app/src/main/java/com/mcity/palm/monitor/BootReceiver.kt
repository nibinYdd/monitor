package com.mcity.palm.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.FileIOUtils
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.ResourceUtils
import com.blankj.utilcode.util.ServiceUtils
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
            Log.d("BootReceiver", "Boot completed received")
            ServiceUtils.startService(HeadlessService::class.java)
//            ResourceUtils.copyFileFromAssets("boot_headless.sh", "${context.cacheDir}/boot_headless.sh")
//            val script = "/data/local/tmp/boot_headless.sh"
//            ShellUtils.execCmd("cp ${context.cacheDir}/boot_headless.sh $script",true)
//            ShellUtils.execCmd("chmod 777 $script",true)
//            ShellUtils.execCmd("nohup ./$script",true)
        }
    }
}
