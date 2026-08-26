package eu.kanade.tachiyomi.data.panel

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Downloads the panel detection model and the LiteRT native libraries at runtime.
 *
 * The panel-by-panel navigation feature is disabled by default, so the model and
 * native libraries are not bundled in the APK. They are fetched on first use and
 * stored under [MODEL_ROOT_DIR] in app files.
 */
class PanelModelDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val MODEL_ROOT_DIR = "panel_detector"

        private const val LITERT_VERSION = "2.1.6"
        private const val LITERT_AAR_URL =
            "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/$LITERT_VERSION/litert-$LITERT_VERSION.aar"
        private const val LITERT_API_AAR_URL =
            "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-api/$LITERT_VERSION/litert-api-$LITERT_VERSION.aar"
        private const val MODEL_URL =
            "https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/resolve/main/manga_panel_detector_int8.tflite"

        private const val LITE_RT_SO = "libLiteRt.so"
        private const val LITERT_JNI_SO = "liblitert_jni.so"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isDownloaded: Boolean
        get() = supportedAbi()?.let { abi ->
            nativesReady(abi) && modelFile().isFile && modelFile().length() > 0L
        } ?: false

    private fun root(): File = File(context.filesDir, MODEL_ROOT_DIR)

    private fun abiDir(abi: String): File = File(root(), "lib/$abi")

    private fun nativesReady(abi: String): Boolean =
        File(abiDir(abi), LITE_RT_SO).isFile && File(abiDir(abi), LITERT_JNI_SO).isFile

    private fun modelFile(): File = File(root(), "model.tflite")

    private fun supportedAbi(): String? = when {
        "arm64-v8a" in Build.SUPPORTED_ABIS -> "arm64-v8a"
        "armeabi-v7a" in Build.SUPPORTED_ABIS -> "armeabi-v7a"
        "x86_64" in Build.SUPPORTED_ABIS -> "x86_64"
        else -> null
    }

    fun triggerDownload() {
        if (isDownloaded) return
        scope.launch {
            context.notify(
                Notifications.ID_PANEL_PROGRESS,
                Notifications.CHANNEL_PANEL_MODEL_DOWNLOAD,
            ) {
                setSmallIcon(android.R.drawable.stat_sys_download)
                setContentTitle("Downloading panel detection model")
                setContentText("Downloading panel detection runtime...")
                setOngoing(true)
                setOnlyAlertOnce(true)
            }
            val result = downloadAndExtract()
            context.cancelNotification(Notifications.ID_PANEL_PROGRESS)
            if (result.isSuccess) {
                context.notify(
                    Notifications.ID_PANEL_PROGRESS,
                    Notifications.CHANNEL_PANEL_MODEL_DOWNLOAD,
                ) {
                    setSmallIcon(android.R.drawable.stat_sys_download_done)
                    setContentTitle("Panel detection ready")
                    setContentText("Panel detection model downloaded successfully")
                    setAutoCancel(true)
                    setOngoing(false)
                }
            } else {
                context.notify(
                    Notifications.ID_PANEL_PROGRESS,
                    Notifications.CHANNEL_PANEL_MODEL_DOWNLOAD,
                ) {
                    setSmallIcon(android.R.drawable.stat_sys_warning)
                    setContentTitle("Panel detection model download failed")
                    setContentText(result.exceptionOrNull()?.message ?: "Unknown error")
                    setAutoCancel(true)
                    setOngoing(false)
                }
            }
        }
    }

    suspend fun downloadAndExtract(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val abi = supportedAbi()
                ?: return@withContext Result.failure(RuntimeException("Unsupported device architecture"))
            val targetAbiDir = abiDir(abi)

            downloadTo(targetAbiDir, LITE_RT_SO, LITERT_AAR_URL, "jni/$abi/libLiteRt.so")
            downloadTo(targetAbiDir, LITERT_JNI_SO, LITERT_API_AAR_URL, "jni/$abi/liblitert_jni.so")

            val model = modelFile()
            model.parentFile?.mkdirs()
            downloadFile(MODEL_URL, model)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads [aarUrl], opens it as a zip and extracts [entryName] into [targetDir]/[fileName].
     */
    private fun downloadTo(
        targetDir: File,
        fileName: String,
        aarUrl: String,
        entryName: String,
    ) {
        val target = File(targetDir, fileName)
        if (target.isFile && target.length() > 0L) return

        target.parentFile?.mkdirs()

        val request = Request.Builder().url(aarUrl).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code} for $aarUrl" }
            val body = response.body ?: checkNotNull(null) { "Empty response body for $aarUrl" }
            body.byteStream().use { input ->
                extractEntry(input, entryName, target)
            }
        }
    }

    private fun downloadFile(url: String, target: File) {
        if (target.isFile && target.length() > 0L) return

        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code} for $url" }
            val body = response.body ?: checkNotNull(null) { "Empty response body for $url" }
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun extractEntry(input: InputStream, entryName: String, target: File) {
        val targetCanonical = target.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == entryName && !entry.isDirectory) {
                    val dir = targetCanonical.parentFile
                    dir?.mkdirs()
                    FileOutputStream(targetCanonical).use { out -> zip.copyTo(out) }
                    return
                }
                zip.closeEntry()
            }
        }
        error("Entry not found in archive: $entryName")
    }
}

private fun Context.cancelNotification(id: Int) {
    val manager = androidx.core.app.NotificationManagerCompat.from(this)
    manager.cancel(id)
}