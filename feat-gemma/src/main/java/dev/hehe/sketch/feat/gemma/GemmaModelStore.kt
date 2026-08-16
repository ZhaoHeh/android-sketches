package dev.hehe.sketch.feat.gemma

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val phase: Phase
) {
    enum class Phase { DOWNLOADING, VERIFYING }
}

class GemmaModelStore(context: Context) {
    private val modelDir = File(context.noBackupFilesDir, "models")
    val modelFile = File(modelDir, GemmaModelSpec.FILE_NAME)
    private val partialFile = File(modelDir, "${GemmaModelSpec.FILE_NAME}.partial")

    fun isModelReady(): Boolean =
        modelFile.isFile && modelFile.length() == GemmaModelSpec.SIZE_BYTES

    internal fun downloadedBytes(): Long = when {
        isModelReady() -> GemmaModelSpec.SIZE_BYTES
        partialFile.isFile -> partialFile.length().coerceAtMost(GemmaModelSpec.SIZE_BYTES)
        else -> 0L
    }

    internal suspend fun download(
        onProgress: (ModelDownloadProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        modelDir.mkdirsOrThrow()
        if (isModelReady()) return@withContext modelFile

        val metadata = fetchMetadata()
        require(metadata.sizeBytes == GemmaModelSpec.SIZE_BYTES) {
            "官方模型大小已变化：${metadata.sizeBytes}，预期 ${GemmaModelSpec.SIZE_BYTES}"
        }
        if (modelFile.exists()) modelFile.deleteOrThrow()
        if (partialFile.length() > metadata.sizeBytes) partialFile.deleteOrThrow()

        val offset = partialFile.length()
        ensureFreeSpace(metadata.sizeBytes - offset)
        if (offset < metadata.sizeBytes) {
            downloadFromOffset(offset, metadata.sizeBytes, onProgress)
        }
        require(partialFile.length() == metadata.sizeBytes) {
            "下载大小不正确：${partialFile.length()} / ${metadata.sizeBytes}"
        }

        onProgress(
            ModelDownloadProgress(
                downloadedBytes = metadata.sizeBytes,
                totalBytes = metadata.sizeBytes,
                phase = ModelDownloadProgress.Phase.VERIFYING
            )
        )
        val actualSha256 = sha256(partialFile)
        if (!actualSha256.equals(metadata.sha256, ignoreCase = true)) {
            partialFile.deleteOrThrow()
            error("SHA-256 校验失败，请重新下载")
        }
        moveAtomically(partialFile, modelFile)
        modelFile
    }

    internal fun deleteModel() {
        if (modelFile.exists()) modelFile.deleteOrThrow()
        if (partialFile.exists()) partialFile.deleteOrThrow()
    }

    private fun fetchMetadata(): ModelArtifactMetadata {
        val connection = openConnection(GemmaModelSpec.METADATA_URL)
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "读取模型元数据失败：HTTP ${connection.responseCode}"
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            HuggingFaceMetadata.parse(json, GemmaModelSpec.FILE_NAME)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadFromOffset(
        initialOffset: Long,
        totalBytes: Long,
        onProgress: (ModelDownloadProgress) -> Unit
    ) {
        var offset = initialOffset
        var connection = openDownloadConnection(offset)
        try {
            val response = connection.responseCode
            val append = offset > 0L && response == HttpURLConnection.HTTP_PARTIAL
            if (offset > 0L && response == HttpURLConnection.HTTP_OK) {
                offset = 0L
            } else {
                require(response == HttpURLConnection.HTTP_OK || response == HttpURLConnection.HTTP_PARTIAL) {
                    "模型下载失败：HTTP $response"
                }
            }

            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(partialFile, append), BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = offset
                    var lastProgressAt = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                            onProgress(
                                ModelDownloadProgress(
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes,
                                    phase = ModelDownloadProgress.Phase.DOWNLOADING
                                )
                            )
                            lastProgressAt = now
                        }
                    }
                    output.flush()
                    onProgress(
                        ModelDownloadProgress(
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                            phase = ModelDownloadProgress.Phase.DOWNLOADING
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openDownloadConnection(offset: Long): HttpURLConnection {
        var currentUrl = GemmaModelSpec.DOWNLOAD_URL
        repeat(MAX_REDIRECTS) {
            val connection = openConnection(currentUrl).apply {
                instanceFollowRedirects = false
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
                ?: error("模型下载重定向缺少 Location")
            currentUrl = URL(URL(currentUrl), location).toString()
            connection.disconnect()
        }
        error("模型下载重定向次数过多")
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "android-sketches-feat-gemma/1")
        }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureFreeSpace(remainingBytes: Long) {
        val available = StatFs(modelDir.absolutePath).availableBytes
        require(available >= remainingBytes + FREE_SPACE_RESERVE_BYTES) {
            "存储空间不足，还需要至少 ${formatGiB(remainingBytes + FREE_SPACE_RESERVE_BYTES)} GiB"
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            check(source.renameTo(target)) { "模型文件重命名失败" }
        }
    }

    private fun File.mkdirsOrThrow() {
        check(isDirectory || mkdirs()) { "无法创建模型目录：$absolutePath" }
    }

    private fun File.deleteOrThrow() {
        check(delete()) { "无法删除文件：$absolutePath" }
    }

    private fun formatGiB(bytes: Long): String = "%.2f".format(bytes / GIB.toDouble())

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_REDIRECTS = 8
        const val GIB = 1024L * 1024L * 1024L
        const val FREE_SPACE_RESERVE_BYTES = 512L * 1024L * 1024L
    }
}
