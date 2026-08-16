package dev.hehe.sketch.feat.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceMetadataTest {
    @Test
    fun parsesLfsSizeAndSha256ForRequestedFile() {
        val metadata = HuggingFaceMetadata.parse(
            json = """
                {
                  "siblings": [
                    {"rfilename":"README.md","size":42},
                    {
                      "rfilename":"gemma-4-E2B-it.litertlm",
                      "size":123,
                      "lfs": {
                        "size":2583085056,
                        "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                      }
                    }
                  ]
                }
            """.trimIndent(),
            fileName = GemmaModelSpec.FILE_NAME
        )

        assertEquals(2_583_085_056L, metadata.sizeBytes)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            metadata.sha256
        )
    }

    @Test
    fun rejectsMetadataWithoutTrustedLfsHash() {
        val failure = runCatching {
            HuggingFaceMetadata.parse(
                json = """{"siblings":[{"rfilename":"${GemmaModelSpec.FILE_NAME}","size":1}]}""",
                fileName = GemmaModelSpec.FILE_NAME
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }
}
