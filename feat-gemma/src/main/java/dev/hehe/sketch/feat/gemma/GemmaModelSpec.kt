package dev.hehe.sketch.feat.gemma

internal object GemmaModelSpec {
    const val DISPLAY_NAME = "Gemma 4 E2B IT"
    const val REPOSITORY = "litert-community/gemma-4-E2B-it-litert-lm"
    const val REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val SIZE_BYTES = 2_583_085_056L

    const val METADATA_URL =
        "https://huggingface.co/api/models/$REPOSITORY/revision/$REVISION?blobs=true"
    const val DOWNLOAD_URL =
        "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILE_NAME?download=true"
}
