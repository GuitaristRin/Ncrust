package com.takahashirinta.ncrust.network.model

import com.google.gson.annotations.SerializedName

data class ArtistItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String
)

data class AlbumItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String?,
    @SerializedName("picUrl") val picUrl: String?
)

data class SongDetailResponse(
    @SerializedName("songs") val songs: List<SongDetail>
)

data class SongDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>,
    @SerializedName("al") val album: AlbumItem,
    @SerializedName("dt") val duration: Long,
    @SerializedName("no") val trackNumber: Int?,
    @SerializedName("mv") val mvId: Long?
)

data class LyricResponse(
    @SerializedName("lrc") val lrc: LyricContent?,
    @SerializedName("tlyric") val tlyric: LyricContent?
)

data class LyricContent(
    @SerializedName("lyric") val lyric: String?
)