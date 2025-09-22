package com.mcity.palm.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.blankj.utilcode.util.FileIOUtils
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.ShellUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.TimeUnit

fun main(){
    val result = "0e007b014f35c771fa692a000831323334"
    val serialNo = result.substring(2, 6).toInt(16)
    val imei = result.substring(6, 22).toLong(16)
    val length = result.substring(22, 26).toInt(16)
    val pwdHex = result.substring(26).trim()
    val pwd = pwdHex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    println("serialNo=$serialNo imei=$imei length=$length pwd=$pwd")
}

fun extractAndConcat(input: String): String {
    val regex = "'([^']*)'".toRegex()
    return regex.findAll(input)
        .map { it.groupValues[1] }
        .joinToString("")
        .replace(".","")
}

class HeadlessService : Service() {
    private val TAG = "HeadlessService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var  sharedDir:File //File("/data/local/shared")
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // UDP socket will be created in onCreate and closed in onDestroy
    @Volatile
    private var udpSocket: DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        sharedDir = cacheDir
//        try {
//            if (!sharedDir.exists()) sharedDir.mkdirs()
//            rotateLogIfNeeded()
//            writeLog("service onCreate")
//        } catch (t: Throwable) {
//            Log.e(TAG, "init error", t)
//        }
        startForegroundCompat()
        startUdpSocketAndWorkers()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        writeLog("onStartCommand flags=$flags startId=$startId")
        val commandResult =
            ShellUtils.execCmd("service call iphonesubinfo 4 i32 0 s16 com.android.shell", true)
        val extractAndConcat = extractAndConcat(commandResult.successMsg)
        writeLog("service call result: ${extractAndConcat}")
        return START_STICKY
    }

    override fun onDestroy() {
        writeLog("onDestroy - shutting down")
        try {
            udpSocket?.close()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "headless_service_channel"
        val ch = NotificationChannel(channelId, "Monitor Service", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val nb =
            Notification.Builder(this, channelId)
        val notification: Notification = nb
            .setContentTitle("Monitor Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(1001, notification)
    }

    private fun startUdpSocketAndWorkers() {
        scope.launch {
            try {
                udpSocket = DatagramSocket().apply {
                    soTimeout = 0
                    connect(InetAddress.getByName(Constants.remoteHost),Constants.remotePort)
                }
                writeLog("udp socket created, bound to port ${Constants.remotePort}. remote=${Constants.remoteHost}:$${Constants.remotePort}")
            } catch (e: Throwable) {
                writeLog("failed to create udp socket: ${e.localizedMessage}")
                return@launch
            }

            // Launch receiver coroutine
            scope.launch {
                val socket = udpSocket
                if (socket == null) {
                    writeLog("udp receiver: socket is null; exiting receiver")
                    return@launch
                }
                val buf = ByteArray(64 * 1024)
                writeLog("udp receiver started")
                while (isActive) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        val len = pkt.length
                        val data = pkt.data.copyOf(len)
                        val result = String(data)
                        writeLog("udp recv saved: ${data.contentToString()},${String(data)} size=$len from ${pkt.address.hostAddress}:${pkt.port}")
                        //0a007b014f35c771fa692a00019
                        if (result.length>=26){
                            val cmd = result.substring(0, 2).toInt(16)
                            when(cmd){
                                0x0A->{
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    val urlId = result.substring(26).trim().toInt(16)
                                    writeLog("cmd=0x0A serialNo=$serialNo imei=$imei length=$length urlId=$urlId")
                                    scope.launch {
                                        val url = "https://api.guanglongdianzi.cn/prod-api/device/app/upgrade?imei=${imei}&id=$urlId"
                                        val ok = downloadAndInstall(url)
                                        writeLog("downloadAndInstall result for $url : $ok")
                                        if (ok) {
                                            val respCmd = byteArrayOf(0x0A) +
                                                    ByteBuffer.allocate(2).putShort(serialNo.toShort()).array() +
                                                    ByteBuffer.allocate(8).putLong(imei).array() +
                                                    byteArrayOf(0x03) +
                                                    byteArrayOf(0x01,0x00,0x01)
                                            val pkt = DatagramPacket(respCmd, respCmd.size)
                                            socket.send(pkt)
                                        }
                                    }
                                }
                                0x0B->{
                                    writeLog("cmd=0x0B reboot command received")
                                    ShellUtils.execCmd("reboot",false)
                                }
                                0x0C->{
                                    //定时开关机
                                    //{"type":"day", "start":"10:10","stop":"22:11"}
                                    //{"type":"range", [{"start":"202508261010","stop":"202508262213"}]}
                                    writeLog("cmd=0x0C reboot schedule command received")
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)

                                    val respCmd = byteArrayOf(0x0C) +
                                            ByteBuffer.allocate(2).putShort(serialNo.toShort()).array() +
                                            ByteBuffer.allocate(8).putLong(imei).array() +
                                            byteArrayOf(0x00,0x02,0x01)
                                    val pkt = DatagramPacket(respCmd, respCmd.size)
                                    socket.send(pkt)

                                    ShellUtils.execCmd("echo +3600 > /sys/class/rtc/rtc0/wakealarm",true)
                                    ShellUtils.execCmd("reboot -p",true)

                                }
                                0x0D->{
                                    //wifi密码
                                    writeLog("cmd=0x0B reboot command received")
                                    ShellUtils.execCmd("reboot",false)
                                }
                                0x0E->{
                                    //更改检测 APP 退出密码
                                    //0e007b014f35c771fa692a000831323334
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    val pwdHex = result.substring(26).trim()
                                    val pwd = pwdHex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                                    val b = FileIOUtils.writeFileFromString(
                                        File(
                                            sharedDir,
                                            "/pwd/password.txt"
                                        ), pwd
                                    )
                                    writeLog("cmd=0x0E serialNo=$serialNo imei=$imei length=$length pwd=$pwd")
                                    val respCmd = byteArrayOf(0x0E) +
                                            ByteBuffer.allocate(2).putShort(serialNo.toShort()).array() +
                                            ByteBuffer.allocate(8).putLong(imei).array() +
                                            byteArrayOf(0x00,0x02) +
                                            byteArrayOf(if (b) 0x01 else 0x00)
                                    val pkt = DatagramPacket(respCmd, respCmd.size)
                                    socket.send(pkt)
                                }
                                0x0F->{
                                    //修改心跳时间间隔
                                    //0f007b014f35c771fa692a00021e
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    val period = result.substring(26).trim().toInt(16)
                                    val b = FileIOUtils.writeFileFromString(
                                        File(
                                            sharedDir,
                                            "/pwd/heart.txt"
                                        ), period.toString()
                                    )
                                    writeLog("cmd=0x0F serialNo=$serialNo imei=$imei length=$length period=$period")

                                    val respCmd = byteArrayOf(0x0F) +
                                            ByteBuffer.allocate(2).putShort(serialNo.toShort()).array() +
                                            ByteBuffer.allocate(8).putLong(imei).array() +
                                            byteArrayOf(0x00,0x02) +
                                            byteArrayOf(if (b) 0x01 else 0x00)
                                    val pkt = DatagramPacket(respCmd, respCmd.size)
                                    socket.send(pkt)
                                }
                                0x10->{
                                    //更新模型
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    val urlId = result.substring(26).trim().toInt(16)
                                    writeLog("cmd=0x10 serialNo=$serialNo imei=$imei length=$length urlId=$urlId")
                                    scope.launch {
                                        val url = "https://api.guanglongdianzi.cn/prod-api/device/app/upgrade?imei=${imei}&id=$urlId"
                                        val ok = downloadAndSave(url)
                                        writeLog("downloadAndSave result for $url : $ok")
                                        val respCmd = byteArrayOf(0x10) +
                                                ByteBuffer.allocate(2).putShort(serialNo.toShort()).array() +
                                                ByteBuffer.allocate(8).putLong(imei).array() +
                                                byteArrayOf(0x00,0x02,if (ok)0x01 else 0x00)
                                        val pkt = DatagramPacket(respCmd, respCmd.size)
                                        socket.send(pkt)
                                    }
                                }
                                0x11->{
                                    //更新监控 APP
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    val urlId = result.substring(26).trim().toInt(16)
                                    writeLog("cmd=0x11 serialNo=$serialNo imei=$imei length=$length urlId=$urlId")
                                    scope.launch {
                                        val url = "https://api.guanglongdianzi.cn/prod-api/device/app/upgrade?imei=${imei}&id=$urlId"
                                        val ok = downloadAndInstall(url)
                                        writeLog("downloadAndInstall result for $url : $ok")
                                        if (ok) {
                                            val respCmd = byteArrayOf(0x11) +
                                                    ByteBuffer.allocate(2).putShort(serialNo.toShort()).array() +
                                                    ByteBuffer.allocate(8).putLong(imei).array() +
                                                    byteArrayOf(0x03) +
                                                    byteArrayOf(0x01,0x00,0x01)
                                            val pkt = DatagramPacket(respCmd, respCmd.size)
                                            socket.send(pkt)
                                        }
                                    }
                                }
                                0x12->{
                                    //发送待机广告
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    val expire = result.substring(26, 36).toLong(16)
                                    val strHex = result.substring(36).trim()
                                    val str = strHex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                                    writeLog("cmd=0x12 serialNo=$serialNo imei=$imei length=$length expire=$expire str=$str")

                                }
                                0x13->{
                                    //清空广告目录
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    writeLog("cmd=0x13 serialNo=$serialNo imei=$imei length=$length")

                                }
                            }

                        }
                    } catch (e: Throwable) {
                        // 当 socket 被 close() 时，会抛异常跳出
                        writeLog("udp receive exception: ${e.localizedMessage}")
                        // 若不是因为关闭，稍微延时再继续
                        delay(200)
                    }
                }
                writeLog("udp receiver exiting")
            }

            // Launch periodic sender coroutine (every 60s)
            scope.launch {
                val socket = udpSocket
                if (socket == null) {
                    writeLog("udp sender: socket is null; exiting sender")
                    return@launch
                }
                val remoteAddr = try {
                    InetAddress.getByName(Constants.remoteHost)
                } catch (e: Exception) {
                    writeLog("udp sender: invalid remote host ${Constants.remoteHost}")
                    return@launch
                }
                writeLog("udp sender started -> sending to ${remoteAddr.hostAddress}:${Constants.remotePort} every 60s")
                while (isActive) {
                    try {
                        //01 00 00 01 4F 35 C7 71 FA 69 2A 00 15 32 50 01 F9 14 46 00 6F 00 85 00 A4
                        val cmd = byteArrayOf(0x01)
                        val serialNo = byteArrayOf(0x00,0x00)
                        val imei = ByteBuffer.allocate(8).putLong(94353247925070122).array()
                        val len = byteArrayOf(0x00,0x15)
                        val diskUsageRate = byteArrayOf(0x32)
                        val cpuUsageRate = byteArrayOf(0x50)
                        val cpuTemp = byteArrayOf(0x01, 0xF9.toByte())
                        val wifi = byteArrayOf(0x14)
                        val mobileNetwork = byteArrayOf(0x46)
                        val version = byteArrayOf(0x00,0x6F,0x00, 0x85.toByte(),0x00, 0xA4.toByte())


                        val bytes = cmd + serialNo + imei + len + diskUsageRate + cpuUsageRate + cpuTemp + wifi + mobileNetwork + version
                        val pkt = DatagramPacket(bytes, bytes.size)
                        socket.send(pkt)
                        writeLog("udp sent: ${bytesToHex(bytes)} -> ${remoteAddr.hostAddress}:${Constants.remotePort}")
                    } catch (e: Throwable) {
                        writeLog("udp send error: ${e.localizedMessage}")
                    }
                    delay(60_000L)
                }
                writeLog("udp sender exiting")
            }
        }
    }

    suspend fun downloadAndSave(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            writeLog("begin download: $url")
            val out = File(sharedDir, "/tmp/downloaded_${System.currentTimeMillis()}")
            val ok = downloadFile(url, out)
            if (!ok) {
                writeLog("download failed: $url")
                return@withContext false
            }
            val copy = FileUtils.copy(out, File(sharedDir, "/model/palm.so"))
            return@withContext copy
        }
    }

    // 下载并安装：返回是否成功
    suspend fun downloadAndInstall(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            writeLog("begin download: $url")
            val out = File(sharedDir, "/tmp/downloaded_${System.currentTimeMillis()}.apk")
            val ok = downloadFile(url, out)
            if (!ok) {
                writeLog("download failed: $url")
                return@withContext false
            }
            // 安装
            val installed = installApkSilently(out.absolutePath)
            writeLog("install result: $installed for ${out.absolutePath}")
            return@withContext installed
        }
    }

    // 下载实现（OkHttp）
    private fun downloadFile(url: String, outFile: File): Boolean {
        //可以先下载到/data/data..目录后通过shell拷贝到local下面
        return try {
            val req = Request.Builder().url(url).build()
            okClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    writeLog("http failed code=${resp.code}")
                    return false
                }
                outFile.parentFile?.mkdirs()
                resp.body?.byteStream()?.use { ins ->
                    FileOutputStream(outFile).use { fos ->
                        ins.copyTo(fos)
                    }
                }
                true
            }
        } catch (e: Exception) {
            writeLog("downloadFile exception: ${e.localizedMessage}")
            false
        }
    }


    // 静默安装 apk（依赖 root）
    private fun installApkSilently(apkPath: String): Boolean {
        return try {
            val cmd = arrayOf("su", "-c", "pm install  \"$apkPath\"")
            val p = Runtime.getRuntime().exec(cmd)
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            writeLog("pm install output: $out")
            out.contains("Success", ignoreCase = true)
        } catch (e: Exception) {
            writeLog("install error: ${e.localizedMessage}")
            false
        }
    }

    // WiFi connect 使用 wpa_cli via su（线程阻塞）
    fun connectWifi(ssid: String, psk: String): Boolean {
        return try {
            writeLog("connectWifi ssid=$ssid")
            Runtime.getRuntime().exec(arrayOf("su", "-c", "svc wifi enable")).waitFor()
            val iface = "wlan0" // TODO: 根据设备调整
            val addProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "wpa_cli -i $iface add_network"))
            val netId = addProc.inputStream.bufferedReader().readText().trim().lines().lastOrNull() ?: return false
            Runtime.getRuntime().exec(arrayOf("su", "-c", "wpa_cli -i $iface set_network $netId ssid '\"$ssid\"'")).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", "wpa_cli -i $iface set_network $netId psk '\"$psk\"'")).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", "wpa_cli -i $iface enable_network $netId")).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", "wpa_cli -i $iface save_config")).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", "wpa_cli -i $iface reconfigure")).waitFor()
            writeLog("connectWifi command sequence done")
            true
        } catch (e: Exception) {
            writeLog("connectWifi error: ${e.localizedMessage}")
            false
        }
    }

    private fun writeLog(msg: String) {
        Log.i(TAG, msg)
//        try {
//            rotateLogIfNeeded()
//            val line = "${System.currentTimeMillis()} $msg\n"
//            FileOutputStream(logFile, true).use { it.write(line.toByteArray()) }
//        } catch (e: Exception) {
//            Log.e(TAG, "writeLog error", e)
//        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
