package com.takahashirinta.ncrust.network.model

import com.google.gson.annotations.SerializedName

// ==================== 专辑详情 ====================
data class AlbumDetailResponse(
    @SerializedName("album") val album: AlbumDetail?,
    @SerializedName("songs") val songs: List<AlbumSongItem>?,
    @SerializedName("code") val code: Int = 200
)

data class AlbumDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("artist") val artist: ArtistItem?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("company") val company: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("size") val size: Int? = null
)

data class AlbumSongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val dt: Long = 0,
    @SerializedName("no") val trackNumber: Int? = null
) {
    fun getDurationMs(): Long = dt
}

// ==================== 艺人专辑列表 ====================
data class ArtistAlbumsResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("hotAlbums") val hotAlbums: List<ArtistAlbumItem>?,
    @SerializedName("albums") val albums: List<ArtistAlbumItem>?,
    @SerializedName("more") val more: Boolean? = null
)

data class ArtistAlbumItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("size") val size: Int?,
    @SerializedName("artist") val artist: ArtistItem? = null
)