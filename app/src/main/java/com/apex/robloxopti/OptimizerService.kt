package com.apex.robloxopti

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.InvocationTargetException

class OptimizerService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private val robloxVNG = "com.roblox.client.vnggames"
    private val robloxQT = "com.roblox.client"
    private val CHANNEL_ID = "rbx_opti_ch"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val n = buildNotif("Đang khởi tạo...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            try {
                emit(10, "Đang phân tích Roblox Engine...")
                delay(500)

                val pkgVNG = isInstalled(robloxVNG)
                val pkgQT = isInstalled(robloxQT)

                if (!pkgVNG && !pkgQT) {
                    emit(100, "Không tìm thấy Roblox!")
                    delay(2000); ProgressState.finish(); stopSelf(); return@launch
                }

                if (pkgVNG) optimizeRoblox(robloxVNG, "VNG", 30)
                if (pkgQT) optimizeRoblox(robloxQT, "QT", 60)

                emit(90, "Force-stop game để áp dụng...")
                if (pkgVNG) sh("am force-stop $robloxVNG")
                if (pkgQT) sh("am force-stop $robloxQT")
                delay(800)

                emit(100, "Tối ưu xong! Mở Roblox trải nghiệm.")
                ProgressState.finish(); delay(2500); stopSelf()

            } catch (e: Exception) {
                e.printStackTrace(); ProgressState.reset(); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun optimizeRoblox(pkg: String, name: String, startProgress: Int) {
        // Hiểu Roblox Engine: Chỉ xóa cache http (tự tải lại), KHÔNG xóa lib
        val dataDir = "/sdcard/Android/data/$pkg/files"
        
        // BƯỚC 1: DỌN RÁC AN TOÀN
        emit(startProgress, "Dọn Cache Roblox $name...")
        sh("rm -rf $dataDir/http/* 2>/dev/null")
        sh("rm -rf $dataDir/logs/* 2>/dev/null")
        sh("rm -rf $dataDir/cache/* 2>/dev/null")
        sh("rm -rf $dataDir/sounds/* 2>/dev/null")
        sh("rm -rf $dataDir/content/* 2>/dev/null")
        
        emit(startProgress + 15, "Cắt sạch hiệu ứng đồ họa...")
        
        // BƯỚC 2: ÉP FFLAGS (CẮT GIẢM TỐI ĐA)
        val fflags = """
            {
                "DFIntTaskSchedulerTargetFps": 60,
                "FFlagDisablePostFx": true,
                "FFlagDisableShadows": true,
                "FFlagDisableGrass": true,
                "FFlagDisableBloom": true,
                "FFlagDisableWaterReflections": true,
                "FFlagDisableAntiAliasing": true,
                "FFlagDebugSkyGray": true,
                "FIntRenderShadowIntensity": 0,
                "FIntDebugForceMSAASamples": 0,
                "FIntFRMMinGrassDistance": 0,
                "FIntFRMMaxGrassDistance": 0,
                "FIntRenderGrassDetailStrands": 0,
                "FIntRenderGrassHeightScaler": 0,
                "FIntRenderLocalLightUpdatesMax": 1,
                "FIntRenderLocalLightUpdatesMin": 1,
                "DFIntDebugFRMQualityLevelOverride": 1,
                "DFFlagTextureQualityOverrideEnabled": true,
                "DFIntTextureQualityOverride": 1,
                "FIntTerrainArraySliceSize": 0,
                "DFIntCSGLevelOfDetailSwitchingDistance": 0,
                "DFIntCSGLevelOfDetailSwitchingDistanceL12": 0,
                "DFIntCSGLevelOfDetailSwitchingDistanceL23": 0,
                "DFIntCSGLevelOfDetailSwitchingDistanceL34": 0
            }
        """.trimIndent()

        val b64 = android.util.Base64.encodeToString(fflags.toByteArray(), android.util.Base64.NO_WRAP)
        sh("echo '$b64' | base64 -d > $dataDir/ClientAppSettings.json")
        sh("echo '$b64' | base64 -d > /data/local/tmp/ClientAppSettings.json 2>/dev/null")
    }

    private fun isInstalled(pkg: String): Boolean {
        return out("pm path $pkg").contains("package:")
    }

    private fun emit(p: Int, msg: String) {
        ProgressState.update(p, msg); updateNotif(msg, p)
    }

    private fun sh(cmd: String) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val c = Class.forName("rikka.shizuku.Shizuku")
                val m = c.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                m.isAccessible = true
                (m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess).waitFor()
            }
        } catch (_: InvocationTargetException) { } catch (_: Exception) { }
    }

    private fun out(cmd: String): String {
        return try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val c = Class.forName("rikka.shizuku.Shizuku")
                val m = c.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                m.isAccessible = true
                val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess
                val sb = StringBuilder()
                val t = Thread { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } }
                t.start(); p.waitFor(); t.join(500); sb.toString().trim()
            } else ""
        } catch (_: Exception) { "" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "RBX Optimizer", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(t: String, p: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Roblox Optimizer")
            .setContentText(t)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setProgress(100, p, false)
            .setOngoing(true)
            .build()

    private fun updateNotif(t: String, p: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotif(t, p))
    }

    override fun onDestroy() { job.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
