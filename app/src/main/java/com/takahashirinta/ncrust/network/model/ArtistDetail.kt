package com.takahashirinta.ncrust.network.model

import com.google.gson.annotations.SerializedName

// ==================== 艺人详情 ====================
data class ArtistDetailResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("data") val data: ArtistDetailData?,
    @SerializedName("artist") val artist: ArtistDetail?,
    @SerializedName("hotSongs") val hotSongs: List<ArtistSongItem>?
)

data class ArtistDetailData(
    @SerializedName("artist") val artist: ArtistDetail?,
    @SerializedName("hotSongs") val hotSongs: List<ArtistSongItem>?
)

data class ArtistDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("cover") val cover: String? = null,
    @SerializedName("albumSize") val albumSize: Int?,
    @SerializedName("musicSize") val musicSize: Int?
)

data class ArtistSongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val dt: Long = 0,
    @SerializedName("duration") val duration: Long = 0
) {
    fun getDurationMs(): Long = if (dt > 0) dt else duration
}