package com.zilizero.app.network

import com.google.gson.annotations.SerializedName

// Response wrapper
data class BiliResponse<T>(
    val code: Int,
    val message: String,
    val ttl: Int,
    val data: T?
)

// Nav data structure
data class NavInfo(
    @SerializedName("wbi_img")
    val wbiImg: WbiImgInfo
)

data class WbiImgInfo(
    @SerializedName("img_url")
    val imgUrl: String,
    @SerializedName("sub_url")
    val subUrl: String
)

// Feed / Recommendation models
data class FeedResponse(
    val item: List<FeedItem>?, // For Feed API
    val list: List<FeedItem>?  // For Ranking API
)

data class FeedItem(
    val id: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val pic: String,
    val owner: Owner,
    val stat: Stat
)

data class Owner(
    val name: String,
    val face: String
)

data class Stat(
    val view: Int,
    val danmaku: Int
)

// PlayURL / DASH models
data class PlayUrlResponse(
    val dash: DashInfo?
)

data class DashInfo(
    val duration: Int,
    val video: List<DashItem>,
    val audio: List<DashItem>
)

data class DashItem(
    val id: Int,
    @SerializedName("base_url")
    val baseUrl: String,
    val bandwidth: Int,
    @SerializedName("mime_type")
    val mimeType: String,
    val codecs: String
)
