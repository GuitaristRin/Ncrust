package com.takahashirinta.ncrust.network

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.account.SongPrivilege
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

// @Immutable：Compose 视 List<T> 为 unstable 参数，会强制每次重组重新比较；
// 显式标注后 Compose 在参数不变时可跳过整个 SongCard 子树的重组。
// 前提是所有字段全 val + 值语义——本 data class 已满足。
@Immutable
data class SongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val duration: Long?,
    // 歌曲详情带出的权益信息。无版权判定必须靠 privilege.st —— 取链响应的
    // freeTrialPrivilege 里没有这个信号（spec/api/entitlement.md §单曲播放结果分类）。
    // 详情接口把它放在与 songs[] 并列的 privileges[] 里，由 PlaylistApi 按 id 关联后填进来；
    // 拿不到就是 null，判定规则已考虑 privilege 缺省。
    @SerializedName("privilege") val privilege: SongPrivilege? = null
)

@Immutable
data class AlbumSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("artist") val artist: ArtistItem?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("size") val size: Int?,
    @SerializedName("company") val company: String?
)

@Immutable
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