package dev.hehe.sketch.feat.gemma

import com.google.gson.JsonParser

internal data class ModelArtifactMetadata(
    val sizeBytes: Long,
    val sha256: String
)

internal object HuggingFaceMetadata {
    fun parse(json: String, fileName: String): ModelArtifactMetadata {
        val siblings = JsonParser.parseString(json)
            .asJsonObject
            .getAsJsonArray("siblings")
            ?: error("模型元数据缺少 siblings")
        val entry = siblings.firstOrNull { element ->
            element.asJsonObject.get("rfilename")?.asString == fileName
        }?.asJsonObject ?: error("模型仓库中找不到 $fileName")
        val lfs = entry.getAsJsonObject("lfs") ?: error("模型元数据缺少 LFS 校验信息")
        val size = lfs.get("size")?.asLong
            ?: entry.get("size")?.asLong
            ?: error("模型元数据缺少文件大小")
        val sha256 = lfs.get("sha256")?.asString?.lowercase()
            ?: error("模型元数据缺少 SHA-256")
        require(SHA_256.matches(sha256)) { "模型 SHA-256 格式无效" }
        return ModelArtifactMetadata(sizeBytes = size, sha256 = sha256)
    }

    private val SHA_256 = Regex("[0-9a-f]{64}")
}
