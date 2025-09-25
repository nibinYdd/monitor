package com.mcity.palm.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.blankj.utilcode.util.FileIOUtils
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.ShellUtils
import com.blankj.utilcode.util.TimeUtils
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

//adb shell am start-foreground-service com.mcity.palm.monitor/.HeadlessService
class HeadlessService : Service() {
    private val TAG = "HeadlessService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val localDir = "/data/local/shared"
    private lateinit var  sharedDir:File
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var udpSocket: DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        writeLog("service onCreate")
        sharedDir = cacheDir
        ShellUtils.execCmd("mkdir $localDir",true)
        ShellUtils.execCmd("mkdir ${localDir}/model",true)
        ShellUtils.execCmd("mkdir ${localDir}/ads",true)
        ShellUtils.execCmd("mkdir ${localDir}/pwd",true)
        ShellUtils.execCmd("mkdir ${localDir}/palm",true)
        ShellUtils.execCmd("mkdir ${localDir}/tmp",true)
        ShellUtils.execCmd("mkdir ${localDir}/bak",true)

        startForegroundCompat()
        startUdpSocketAndWorkers()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        writeLog("onStartCommand flags=$flags startId=$startId")
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
                                        val ok = downloadAndInstall(url,urlId)
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
                                    writeLog("cmd=0x0D update wifi password command received")
//                                    ShellUtils.execCmd("reboot",false)
                                }
                                0x0E->{
                                    //更改检测 APP 退出密码
                                    //0e007b014f35c771fa692a000831323334
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    val pwdHex = result.substring(26).trim()
                                    val pwd = pwdHex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                                    val file = File(
                                        sharedDir,
                                        "/pwd/password.txt"
                                    )
                                    val b = FileIOUtils.writeFileFromString(
                                        file, pwd
                                    )

                                    ShellUtils.execCmd("mv ${file.absolutePath} ${localDir}/pwd/password.txt",true)
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
                                    val file = File(
                                        sharedDir,
                                        "/pwd/heart.txt"
                                    )
                                    val b = FileIOUtils.writeFileFromString(
                                        file, period.toString()
                                    )
                                    ShellUtils.execCmd("mv ${file.absolutePath} ${localDir}/pwd/heart.txt",true)
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
                                        val ok = downloadAndInstall(url,urlId)
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
                                    scope.launch {
                                        str.split(",").forEachIndexed { index, url ->
                                            val name = padNumericFilename(url)

                                            val url =
                                                "https://api.guanglongdianzi.cn/$url"
                                            val fileName = "$expire-$name"
                                            val ok = downloadImage(url,fileName)
                                            writeLog("downloadImage result for $url : $ok")
                                        }
                                    }
                                }
                                0x13->{
                                    //清空广告目录
                                    val serialNo = result.substring(2, 6).toInt(16)
                                    val imei = result.substring(6, 22).toLong(16)
                                    val length = result.substring(22, 26).toInt(16)
                                    writeLog("cmd=0x13 serialNo=$serialNo imei=$imei length=$length")
                                    FileUtils.deleteFilesInDir(sharedDir)
                                    ShellUtils.execCmd("rm -rf ${localDir}/bak/*",true)
                                    ShellUtils.execCmd("rm -rf ${localDir}/tmp/*",true)
                                    ShellUtils.execCmd("rm -rf ${localDir}/palm/*",true)
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
                        val imei = ByteBuffer.allocate(8).putLong(getIMEI()).array()
                        val len = byteArrayOf(0x00,0x15)
                        val diskUsageRate = byteArrayOf(getDiskPercent().toByte())
                        val cpuUsageRate = byteArrayOf(getCPUUsage().toInt().toByte())
                        val cpuTemp = ByteBuffer.allocate(2).putShort((getCPUTemp() * 10).toInt().toShort()).array()
                        val wifi = byteArrayOf(getNetworkSignal().toByte())
                        val mobileNetwork = byteArrayOf(getNetworkSignal().toByte())
                        val arrays = BuildConfig.VERSION_NAME.split(".")
                            .map { ByteBuffer.allocate(2).putShort(it.toShort()).array() }.map { it }
                        var version = byteArrayOf()
                        arrays.forEach { version = version+it }
                        val bytes = cmd + serialNo + imei + len + diskUsageRate + cpuUsageRate + cpuTemp + wifi + mobileNetwork + version
                        val pkt = DatagramPacket(bytes, bytes.size)
                        socket.send(pkt)
                        writeLog("udp sent: ${bytesToHex(bytes)} -> ${remoteAddr.hostAddress}:${Constants.remotePort}")
                    } catch (e: Throwable) {
                        writeLog("udp send error: ${e.localizedMessage}")
                        e.printStackTrace()
                    }
                    delay(60_000L)
                }
                writeLog("udp sender exiting")
            }
        }
    }

    fun extractAndConcat(input: String): String {
        val regex = "'([^']*)'".toRegex()
        return regex.findAll(input)
            .map { it.groupValues[1] }
            .joinToString("")
            .replace(".","")
    }

    fun getIMEI(): Long{
        val commandResult =
            ShellUtils.execCmd("service call iphonesubinfo 4 i32 0 s16 com.android.shell", true)
        val imei =  extractAndConcat(commandResult.successMsg).trim().toLong()
        writeLog("IMEI=$imei")
        return imei
    }
    fun getCPUTemp(): Double{
        val result = ShellUtils.execCmd("cat /sys/class/thermal/thermal_zone0/temp", true)
        val tempStr = result.successMsg.trim()
        val temp =  tempStr.toIntOrNull()?.div(1000.0) ?: 0.0
        writeLog("CPUTemp=$temp,${result.successMsg}")
        return temp
    }

    fun getNetworkSignal(): Int{
        val result =
            ShellUtils.execCmd("dumpsys telephony.registry | grep -i signalstrength -A2", true)
        val index = result.successMsg.indexOf("CellSignalStrengthLte: rssi=")
        val signal = if (index>0){
            result.successMsg.substring(index+28,index+31).toInt()
        }else{
            0
        }
        writeLog("NetworkSignal=$signal,${result.successMsg}")
        return signal
    }

    fun getDiskPercent(): Int{
        val result = ShellUtils.execCmd("df -h /mnt/user/0/emulated/ | grep /fuse", true)
        val regex = Regex("""\s(\d+)%\s""")
        val match = regex.find(result.successMsg)
        val disk =  match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        writeLog("DiskUsage=$disk,${result.successMsg}")
        return disk
    }

    fun getCPUUsage(): Double{
        val result = ShellUtils.execCmd("dumpsys cpuinfo | grep TOTAL", true)
        val regex = Regex("""^([\d.]+)%\s+TOTAL""")
        val match = regex.find(result.successMsg)
        val usage =  match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        writeLog("CPUUsage=$usage,${result.successMsg}")
        return usage
    }

    fun padNumericFilename(path: String, width: Int = 3): String {
        val filename = path.substringAfterLast('/')   // 取最后的文件名
        val dotIndex = filename.lastIndexOf('.')
        val name = filename.substring(0, dotIndex)    // 数字部分
        val ext = filename.substring(dotIndex)        // 扩展名

        val padded = name.toInt().toString().padStart(width, '0')
        return padded + ext
    }

    suspend fun downloadAndSave(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            writeLog("begin download: $url")
            FileUtils.deleteFilesInDir(File(sharedDir, "/tmp"))
            val out = File(sharedDir, "/tmp/downloaded_${System.currentTimeMillis()}")
            val ok = downloadFile(url, out)
            if (!ok) {
                writeLog("download failed: $url")
                return@withContext false
            }
            val file = File(sharedDir, "/model/palm.so")
            val copy = FileUtils.copy(out, file)
            if (copy) {
                ShellUtils.execCmd(
                    "mv ${localDir}/model/palm.so ${localDir}/bak/${TimeUtils.getNowString(TimeUtils.getSafeDateFormat("yyyyMMddHHmmss"))}.palm.so.bak",
                    true
                )
                ShellUtils.execCmd("mv ${file.absolutePath} ${localDir}/model/palm.so",true)
            }
            return@withContext copy
        }
    }

    suspend fun downloadImage(url: String,fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            writeLog("begin download: $url")
            val out = File(sharedDir, "/ads/$fileName")
            val ok = downloadFile(url, out)
            if (!ok) {
                writeLog("download failed: $url")
                return@withContext false
            }
            return@withContext true
        }
    }

    // 下载并安装：返回是否成功
    suspend fun downloadAndInstall(url: String,urlId: Int): Boolean {
        return withContext(Dispatchers.IO) {
            writeLog("begin download: $url")
            FileUtils.deleteFilesInDir(File(sharedDir, "/tmp"))
            val out = File(sharedDir, "/tmp/downloaded_${urlId}.apk")
            val ok = downloadFile(url, out)
            if (!ok) {
                writeLog("download failed: $url")
                return@withContext false
            }
            ShellUtils.execCmd(
                "mv ${localDir}/tmp/downloaded_${urlId}.apk ${localDir}/bak/${TimeUtils.getNowString(TimeUtils.getSafeDateFormat("yyyyMMddHHmmss"))}_${urlId}.apk.bak",
                true
            )
            ShellUtils.execCmd("mv ${out.absolutePath} ${localDir}/tmp/downloaded_${urlId}.apk",true)
            // 安装
            val installed = installApkSilently("${localDir}/tmp/downloaded_${urlId}.apk")
            writeLog("install result: $installed for ${localDir}/tmp/downloaded_${urlId}.apk")
            return@withContext installed
        }
    }

    // 下载实现（OkHttp）
    private fun downloadFile(url: String, outFile: File): Boolean {
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
            val execCmd = ShellUtils.execCmd("pm install $apkPath", true)
            writeLog("installApkSilently result=${execCmd.result} out=${execCmd.successMsg} err=${execCmd.errorMsg}")
            execCmd.result >= 0 && execCmd.successMsg.contains("Success", ignoreCase = true)
        } catch (e: Exception) {
            writeLog("install error: ${e.localizedMessage}")
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
