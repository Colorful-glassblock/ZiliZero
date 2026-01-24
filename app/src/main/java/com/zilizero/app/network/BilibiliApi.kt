package com.zilizero.app.network

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface BilibiliApi {

    // Get User Info and Wbi Keys
    @GET("x/web-interface/nav")
    suspend fun getNavInfo(): BiliResponse<NavInfo>

    // Real Recommendation Feed (Wbi signed)
    @GET("x/web-interface/wbi/index/top/feed/rcmd")
    suspend fun getRecommendFeed(
        @QueryMap signedParams: Map<String, String>
    ): BiliResponse<FeedResponse>

    // Get Play URL (DASH)
    @GET("x/player/wbi/playurl")
    suspend fun getPlayUrl(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long,
        @Query("qn") qn: Int = 80, // 1080P
        @Query("fnval") fnval: Int = 4048, // Request DASH + HDR + 4K
        @Query("fnver") fnver: Int = 0,
        @Query("fourk") fourk: Int = 1,
        @QueryMap signedParams: Map<String, String>
    ): BiliResponse<PlayUrlResponse>

    // Get Danmaku Segment (Protobuf)
    @GET("x/v2/dm/web/seg.so")
    suspend fun getDanmakuSeg(
        @Query("type") type: Int = 1,
        @Query("oid") cid: Long, // oid is cid
        @Query("segment_index") index: Int = 1
    ): com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply

    // Example search
    @GET("x/web-interface/wbi/search/all/v2")
    suspend fun search(
        @Query("keyword") keyword: String,
        @QueryMap signedParams: Map<String, String>
    ): BiliResponse<Any>
}
