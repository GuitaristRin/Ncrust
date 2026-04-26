package com.takahashirinta.ncrust.network

import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.network.model.AlbumItem

data class SearchResponse(
    @SerializedName("result") val result: SearchResult?
)

data class SearchResult(
    @SerializedName("songs") val songs: List<SongItem>?,
    @SerializedName("albums") val albums: List<AlbumSearchItem>?,
    @SerializedName("artists") val artists: List<ArtistSearchItem>?
)

data class SongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val duration: Long?
)

data class AlbumSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("artist") val artist: ArtistItem?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("size") val size: Int?,
    @SerializedName("company") val company: String?
)

data class ArtistSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("picId") val picId: Long?,
    @SerializedName("albumSize") val albumSize: Int?,
    @SerializedName("musicSize") val musicSize: Int?,
    @SerializedName("alias") val alias: List<String>?,
    @SerializedName("trans") val trans: String?
)