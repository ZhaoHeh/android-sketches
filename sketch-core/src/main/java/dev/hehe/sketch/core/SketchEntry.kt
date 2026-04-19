package dev.hehe.sketch.core

data class SketchEntry(
    val title: String,
    val activityClassName: String,
    val activityPackageName: String,
    val summary: String?,
    val order: Int,
    val moduleName: String
)
