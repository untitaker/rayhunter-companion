package com.rayhunter.companion.data

import com.google.gson.annotations.SerializedName

data class ManifestResponse(
    val entries: List<ManifestEntry>,
    @SerializedName("current_entry")
    val currentEntry: ManifestEntry?
)

data class ManifestEntry(
    val name: String,
    @SerializedName("qmdl_size_bytes")
    val qmdlSizeBytes: Long,
    @SerializedName("analysis_size_bytes")
    val analysisSizeBytes: Long,
    @SerializedName("created_at")
    val createdAt: String
)

data class AnalysisResult(
    val warnings: List<AnalysisWarning>?,
    val timestamp: String?,
    val metadata: AnalysisMetadata?
)

data class AnalysisWarning(
    val type: String,
    val message: String,
    val severity: String?
)

data class AnalysisMetadata(
    val analyzers: Map<String, Boolean>
)