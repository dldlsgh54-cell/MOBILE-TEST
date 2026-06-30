package com.example.shortsauto.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.shortsauto.MainActivity
import com.example.shortsauto.R
import com.example.shortsauto.accessibility.ChatGptAccessibilityService

class ChatGptAutomationService : Service() {
    companion object {
        const val ACTION_START = "com.example.shortsauto.START"
        const val ACTION_PAUSE = "com.example.shortsauto.PAUSE"
        const val ACTION_STOP = "com.example.shortsauto.STOP"
        const val BROADCAST_PROGRESS = "com.example.shortsauto.PROGRESS"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_PROMPTS = "extra_prompts"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_COMPLETE = "extra_complete"
        private const val CHANNEL_ID = "shorts_auto_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RETRY = 2
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var worker: Thread? = null
    private var projectName = "shorts_project"
    private val prompts = mutableListOf<String>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("대기 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> {
                paused = !paused
                postStatus(if (paused) "일시정지됨" else "다시 시작", progressFraction(), running)
            }
            ACTION_STOP -> stopAutomation("중지됨")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun start(intent: Intent) {
        projectName = PromptRepository.sanitizeProjectName(intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty())
        prompts.clear()
        prompts += intent.getStringArrayExtra(EXTRA_PROMPTS).orEmpty().map { it.trim() }.filter { it.isNotBlank() }.take(6)
        if (prompts.isEmpty()) {
            stopAutomation("실행할 프롬프트가 없습니다.")
            return
        }
        running = true
        paused = false
        acquireWakeLock()
        worker?.interrupt()
        worker = Thread(::runLoop, "shorts-auto-worker").also { it.start() }
    }

    private fun runLoop() {
        PromptRepository.appendLog(this, projectName, "자동화 시작: ${prompts.size}개 프롬프트")
        var successCount = 0
        for (index in prompts.indices) {
            if (!running) break
            val prompt = prompts[index]
            waitIfPaused()
            var success = false
            repeat(MAX_RETRY + 1) { attempt ->
                if (!running || success) return@repeat
                waitIfPaused()
                val attemptText = if (attempt == 0) "" else " 재시도 $attempt/$MAX_RETRY"
                postStatus("${index + 1}/${prompts.size} 입력 중$attemptText", index.toFloat() / prompts.size, true)
                PromptRepository.appendLog(this, projectName, "${index + 1}번 프롬프트 시작$attemptText")
                success = runSinglePrompt(index, prompt)
                if (!success) {
                    PromptRepository.appendLog(this, projectName, "${index + 1}번 실패")
                    sleep(3000)
                }
            }
            if (success) {
                successCount += 1
                PromptRepository.appendLog(this, projectName, "${index + 1}번 저장 완료")
                postStatus("${index + 1}/${prompts.size} 완료", (index + 1).toFloat() / prompts.size, true)
            } else {
                PromptRepository.appendLog(this, projectName, "${index + 1}번 최종 실패, 다음 프롬프트로 이동")
            }
        }
        if (running) completeAutomation(successCount)
    }

    private fun runSinglePrompt(index: Int, prompt: String): Boolean {
        if (!ChatGptAccessibilityService.isConnected()) {
            PromptRepository.appendLog(this, projectName, "접근성 서비스가 꺼져 있습니다.")
            return false
        }
        if (!ensureChatGptReady()) {
            PromptRepository.appendLog(this, projectName, "ChatGPT 화면 인식 실패")
            return false
        }
        if (!ChatGptAccessibilityService.setPromptText(prompt)) {
            launchChatGpt()
            sleep(1500)
        }
        if (!ChatGptAccessibilityService.setPromptText(prompt)) {
            PromptRepository.appendLog(this, projectName, "입력창 탐색 실패")
            return false
        }
        sleep(600)
        if (!ChatGptAccessibilityService.clickSend()) {
            PromptRepository.appendLog(this, projectName, "전송 버튼 탐색 실패, 좌표 보조 클릭 실행")
            ChatGptAccessibilityService.fallbackTapSend()
        }
        postStatus("${index + 1}/${prompts.size} 이미지 생성 대기", index.toFloat() / prompts.size, true)
        val ready = waitForImageReady()
        val bitmap = ChatGptAccessibilityService.captureGeneratedImage()
        return if (bitmap != null) {
            PromptRepository.saveImage(this, projectName, index, bitmap)
            true
        } else if (!ready) {
            PromptRepository.appendLog(this, projectName, "이미지 감지 실패")
            false
        } else {
            saveFallbackImage(index, prompt, "스크린샷 저장 실패")
        }
    }

    private fun waitForImageReady(): Boolean {
        val start = System.currentTimeMillis()
        while (running && System.currentTimeMillis() - start < 180_000) {
            waitIfPaused()
            if (!ChatGptAccessibilityService.isChatGptWindowActive()) {
                launchChatGpt()
                sleep(2500)
            }
            if (ChatGptAccessibilityService.hasGeneratedImage()) {
                sleep(1500)
                return true
            }
            sleep(2000)
        }
        return false
    }

    private fun saveFallbackImage(index: Int, prompt: String, reason: String): Boolean {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 27, 31))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
        }
        canvas.drawText("Fallback image", 48f, 96f, paint)
        paint.textSize = 24f
        canvas.drawText(reason, 48f, 150f, paint)
        prompt.chunked(34).take(20).forEachIndexed { line, value ->
            canvas.drawText(value, 48f, 230f + line * 36f, paint)
        }
        return runCatching {
            PromptRepository.saveImage(this, projectName, index, bitmap)
        }.isSuccess
    }

    private fun launchChatGpt() {
        val packageManager = packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(CHATGPT_PACKAGE)
        val intent = launchIntent ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        PromptRepository.appendLog(this, projectName, if (launchIntent != null) "ChatGPT 앱 실행" else "ChatGPT 웹 실행")
    }

    private fun ensureChatGptReady(): Boolean {
        repeat(4) { attempt ->
            if (ChatGptAccessibilityService.isChatGptWindowActive()) return true
            launchChatGpt()
            sleep(if (attempt == 0) 3500 else 2000)
        }
        return ChatGptAccessibilityService.isChatGptWindowActive()
    }

    private fun waitIfPaused() {
        while (running && paused) sleep(500)
    }

    private fun stopAutomation(status: String) {
        running = false
        paused = false
        PromptRepository.appendLog(this, projectName, status)
        postStatus(status, progressFraction(), false)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun completeAutomation(successCount: Int) {
        running = false
        paused = false
        val status = "작업 완료: 이미지 ${successCount}/${prompts.size}장 저장"
        PromptRepository.appendLog(this, projectName, status)
        postStatus(status, 1f, false, complete = true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status, ongoing = false, complete = true))
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShortsAuto:Automation").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        wakeLock = null
    }

    private fun progressFraction(): Float {
        if (prompts.isEmpty()) return 0f
        val log = PromptRepository.readLog(this, projectName)
        val completed = Regex("""(\d+)번 저장 완료""").findAll(log).count()
        return (completed.toFloat() / prompts.size).coerceIn(0f, 1f)
    }

    private fun postStatus(status: String, progress: Float, isRunning: Boolean, complete: Boolean = false) {
        val notificationText = status.take(80)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(notificationText, ongoing = isRunning, complete = complete))
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_RUNNING, isRunning)
            putExtra(EXTRA_COMPLETE, complete)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "쇼츠 이미지 자동생성", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, ongoing: Boolean = running, complete: Boolean = false): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_COMPLETE, complete)
                putExtra(EXTRA_STATUS, text)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("쇼츠 이미지 자동생성 v1.0")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(complete)
            .setOngoing(ongoing)
            .build()
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
