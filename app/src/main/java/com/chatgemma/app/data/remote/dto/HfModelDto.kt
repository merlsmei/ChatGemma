package com.chatgemma.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class HfModelDto(
    @SerializedName("modelId") val modelId: String = "",
    @SerializedName("lastModified") val lastModified: String? = null,
    @SerializedName("tags") val tags: List<String> = emptyList(),
    @SerializedName("pipeline_tag") val pipelineTag: String? = null,
    @SerializedName("safetensors") val safetensors: SafetensorsInfo? = null,
    @SerializedName("siblings") val siblings: List<HfSibling> = emptyList(),
    @SerializedName("downloads") val downloads: Int = 0,
    @SerializedName("likes") val likes: Int = 0
)

data class HfSibling(
    @SerializedName("rfilename") val rfilename: String = ""
)

data class SafetensorsInfo(
    @SerializedName("total") val total: Long = 0L
)
