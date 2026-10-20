package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.os.Build
import chimahon.ocr.PADDLE_MODELS_RELEASE_TAG
import chimahon.ocr.PADDLE_MODELS_VERSION
import chimahon.ocr.PaddleModelSelection
import chimahon.ocr.paddleComponentBundles
import eu.kanade.tachiyomi.data.model.ModelBundleInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

/** Components are independent, immutable and shared across recognition selections. */
class PaddleModelDownloader(private val context: Context, private val client: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = mutableStatus.asStateFlow()
    private val releaseUrl = "https://github.com/arrow-poppa/chimahon/releases/download/$PADDLE_MODELS_RELEASE_TAG"

    fun required(bundle: String): List<String> {
        require(bundle in paddleComponentBundles)
        return when {
            bundle == "paddle_runtime" -> {
                val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a") }
                    ?: error("Unsupported device architecture")
                listOf("lib/$abi/libpaddle_ocr.so", "lib/$abi/libomp.so", "lib/$abi/libc++_shared.so", "MODEL_INFO.json")
            }
            bundle.startsWith("paddle_rec_") -> listOf("model.param", "model.bin", "dict.txt", "MODEL_INFO.json")
            else -> listOf("model.param", "model.bin", "MODEL_INFO.json")
        }
    }

    fun installed(bundle: String): Boolean {
        ModelBundleInstaller.recover(context.filesDir, bundle)
        return runCatching {
            required(bundle).all { File(context.filesDir, "$bundle/$it").let { f -> f.isFile && f.length() > 0 } } &&
                Json.parseToJsonElement(File(context.filesDir, "$bundle/MODEL_INFO.json").readText()).jsonObject.getValue("version").jsonPrimitive.content == PADDLE_MODELS_VERSION
        }.getOrDefault(false)
    }

    private fun hasLegacyRuntime(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a") } ?: return false
        return listOf("libpaddle_ocr.so", "libomp.so", "libc++_shared.so").all {
            File(context.filesDir, "paddle_ocr/lib/$abi/$it").let { file -> file.isFile && file.length() > 0L }
        }
    }

    suspend fun ensure(selection: PaddleModelSelection) {
        ensureComponents(selection.bundles + if (hasLegacyRuntime()) emptyList() else listOf("paddle_runtime"))
    }

    fun triggerSelection(selection: PaddleModelSelection) {
        scope.launch {
            try {
                ensure(selection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableStatus.update { it + (selection.recognizer.bundle to "Download failed: ${e.message}") }
            }
        }
    }

    fun trigger(bundle: String) {
        scope.launch {
            try {
                ensureComponents(listOf(bundle))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableStatus.update { it + (bundle to "Download failed: ${e.message}") }
            }
        }
    }

    private suspend fun ensureComponents(bundles: List<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val missing = bundles.distinct().filterNot(::installed)
            if (missing.isEmpty()) return@withLock
            val manifest = client.newCall(Request.Builder().url("$releaseUrl/manifest.json").build()).execute().use { response ->
                check(response.isSuccessful) { "Model manifest download failed (HTTP ${response.code})" }
                Json.parseToJsonElement(checkNotNull(response.body).string()).jsonObject
            }
            check(manifest.getValue("version").jsonPrimitive.content == PADDLE_MODELS_VERSION)
            for (bundle in missing) {
                require(bundle in paddleComponentBundles)
                mutableStatus.update { it + (bundle to "Downloading…") }
                val archive = File.createTempFile("ocr-model-", ".zip", context.cacheDir)
                try {
                    val info = manifest.getValue("components").jsonObject.getValue(bundle).jsonObject
                    val hashes = info.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
                    client.newCall(Request.Builder().url("$releaseUrl/$bundle.zip").build()).execute().use { response ->
                        check(response.isSuccessful) { "Model download failed (HTTP ${response.code})" }
                        val body = checkNotNull(response.body)
                        check(body.contentLength() <= 100L * 1024 * 1024) { "Model archive is too large" }
                        body.byteStream().use { input ->
                            archive.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= 100L * 1024 * 1024) { "Model archive is too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    archive.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == info.getValue("sha256").jsonPrimitive.content) { "Model archive checksum mismatch" }
                    archive.inputStream().use { ModelBundleInstaller.install(it, context.filesDir, bundle, required(bundle), hashes) }
                    mutableStatus.update { it - bundle }
                } catch (e: CancellationException) {
                    mutableStatus.update { it - bundle }
                    throw e
                } catch (e: Exception) {
                    mutableStatus.update { it + (bundle to "Download failed: ${e.message}") }
                    throw e
                } finally {
                    archive.delete()
                }
            }
        }
    }

    suspend fun delete(bundle: String): Boolean = withContext(Dispatchers.IO) {
        require(bundle in paddleComponentBundles)
        mutex.withLock {
            Injekt.get<PaddleOcrBridge>().invalidateModels()
            synchronized(ModelBundleInstaller) {
                ModelBundleInstaller.recover(context.filesDir, bundle)
                File(context.filesDir, bundle).deleteRecursively()
            }
        }
    }
}
