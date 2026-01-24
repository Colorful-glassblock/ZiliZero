package com.zilizero.app.repository

import com.zilizero.app.network.BilibiliApi
import com.zilizero.app.network.DashInfo
import com.zilizero.app.network.FeedItem
import com.zilizero.app.network.NetworkClient
import com.zilizero.app.network.WbiUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zilizero.app.network.BiliResponse
import com.zilizero.app.network.FeedResponse

class BiliRepository(
    private val api: BilibiliApi = NetworkClient.api
) {
    private var cachedMixinKey: String? = null

    /**
     * Ensures we have a valid Mixin Key.
     */
    private suspend fun ensureMixinKey(): String {
        cachedMixinKey?.let { return it }
        
        return withContext(Dispatchers.IO) {
            val response = api.getNavInfo()
            val wbiImg = response.data?.wbiImg ?: throw Exception("Failed to get Wbi keys")
            val key = WbiUtil.getMixinKey(wbiImg.imgUrl, wbiImg.subUrl)
            cachedMixinKey = key
            key
        }
    }

    suspend fun getRecommendFeed(): List<FeedItem> {
        return withContext(Dispatchers.IO) {
            // Passing default params explicitly to avoid compiler issues with Retrofit defaults
            val responseBody = api.getRecommendFeed(pageSize = 10, freshType = 3, signedParams = emptyMap())
            val jsonString = responseBody.string()

            try {
                val type = object : TypeToken<BiliResponse<FeedResponse>>() {}.type
                val response: BiliResponse<FeedResponse> = Gson().fromJson(jsonString, type)
                
                if (response.code != 0) {
                    throw Exception("Api Error: ${response.message} (code ${response.code})")
                }
                // Try 'item' (Feed) first, then 'list' (Ranking)
                response.data?.item ?: response.data?.list ?: emptyList()
            } catch (e: Exception) {
                throw Exception("Parse Error: ${e.message}. Raw: ${jsonString.take(500)}")
            }
        }
    }

    suspend fun getPlayUrl(bvid: String, cid: Long): DashInfo {
        return withContext(Dispatchers.IO) {
            val mixinKey = ensureMixinKey()
            
            val params = mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to "80",
                "fnval" to "4048",
                "fourk" to "1"
            )
            
            val signedParams = WbiUtil.signParams(params, mixinKey)
            val response = api.getPlayUrl(bvid, cid, signedParams = signedParams)
            
            if (response.code != 0) {
                throw Exception("Bili Error: ${response.message}")
            }
            
            response.data?.dash ?: throw Exception("No DASH info found")
        }
    }

    suspend fun getDanmaku(cid: Long): com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply {
        return withContext(Dispatchers.IO) {
            // Fetch segment 1 (first 6 minutes) for demo
            api.getDanmakuSeg(cid = cid, index = 1)
        }
    }
}
