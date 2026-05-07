package com.takahashirinta.ncrust.network.model

import com.google.gson.annotations.SerializedName

// ==================== 用户详情 (GET api/v1/user/detail/{uid}) ====================

data class UserDetailResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("profile") val profile: UserDetailProfile?
)

data class UserDetailProfile(
    @SerializedName("userId") val userId: Long,
    @SerializedName("nickname") val nickname: String?,
    @SerializedName("avatarUrl") val avatarUrl: String?,
    @SerializedName("signature") val signature: String? = null,
    @SerializedName("followeds") val followeds: Int = 0,
    @SerializedName("follows") val follows: Int = 0
)

// ==================== 推荐歌单 (GET api/personalized) ====================

data class PersonalizedResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("result") val result: List<PersonalizedItem>?
)

data class PersonalizedItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("playCount") val playCount: Long = 0,
    @SerializedName("trackCount") val trackCount: Int = 0
)

// ==================== 新碟上架 (GET api/album/new) ====================

data class NewAlbumsResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("albums") val albums: List<NewAlbumItem>?
)

data class NewAlbumItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("artist") val artist: ArtistItem?
)
