package com.takahashirinta.ncrust.network
import com.takahashirinta.ncrust.network.model.SongUrlResponse
import com.takahashirinta.ncrust.network.model.*
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface NcmApi {
    @FormUrlEncoded
    @POST("api/cloudsearch/pc")
    suspend fun search(
        @Field("s") keyword: String,
        @Field("type") type: Int = 1,
        @Field("limit") limit: Int = 30
    ): SearchResponse

    @FormUrlEncoded
    @POST("api/cloudsearch/pc")
    suspend fun searchAlbum(
        @Field("s") keyword: String,
        @Field("type") type: Int = 10,
        @Field("limit") limit: Int = 30
    ): SearchResponse

    @FormUrlEncoded
    @POST("api/cloudsearch/pc")
    suspend fun searchArtist(
        @Field("s") keyword: String,
        @Field("type") type: Int = 100,
        @Field("limit") limit: Int = 30
    ): SearchResponse

    @FormUrlEncoded
    @POST("api/v3/song/detail")
    suspend fun getSongDetail(
        @Field("c") c: String
    ): SongDetailResponse

    @FormUrlEncoded
    @POST("api/song/lyric")
    suspend fun getLyric(
        @Field("id") id: Long,
        @Field("cp") cp: String = "false",
        @Field("tv") tv: String = "0",
        @Field("lv") lv: String = "0",
        @Field("rv") rv: String = "0",
        @Field("kv") kv: String = "0",
        @Field("yv") yv: String = "0",
        @Field("ytv") ytv: String = "0",
        @Field("yrv") yrv: String = "0"
    ): LyricResponse

    @FormUrlEncoded
    @POST("api/song/enhance/player/url/v1")
    suspend fun getSongUrl(
        @Field("ids") ids: String,
        @Field("level") level: String,
        @Field("encodeType") encodeType: String = "flac"
    ): SongUrlResponse

    // ====== 修复：专辑详情是 GET 请求 ======
    @GET("api/v1/album/{id}")
    suspend fun getAlbumDetail(
        @Path("id") id: Long
    ): AlbumDetailResponse

    // ====== 艺人详情：尝试 GET 请求 ======
    @GET("api/artist/detail/{id}")
    suspend fun getArtistDetail(
        @Path("id") id: Long
    ): ArtistDetailResponse

    // ====== 艺人专辑列表：尝试 GET 请求 ======
    @GET("api/artist/albums/{id}")
    suspend fun getArtistAlbums(
        @Path("id") id: Long,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): ArtistAlbumsResponse
}