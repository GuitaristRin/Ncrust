package com.takahashirinta.ncrust.network.model

import com.google.gson.annotations.SerializedName

data class SongUrlResponse(
    @SerializedName("data") val data: List<SongUrlData>
)

data class SongUrlData(
    @SerializedName("id") val id: Long,
    @SerializedName("url") val url: String?,
    @SerializedName("br") val bitrate: Long,
    @SerializedName("size") val size: Long,
    @SerializedName("type") val type: String,
    @SerializedName("level") val level: String?
)
