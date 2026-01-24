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

import com.zilizero.app.network.PlayUrlResponse

import com.zilizero.app.network.NavInfo

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
            val responseBody = api.getNavInfo()
            val jsonString = responseBody.string()
            
            try {
                val type = object : TypeToken<BiliResponse<NavInfo>>() {}.type
                val response: BiliResponse<NavInfo> = Gson().fromJson(jsonString, type)
                
                val wbiImg = response.data?.wbiImg ?: throw Exception("Failed to get Wbi keys. Code: ${response.code}")
                val key = WbiUtil.getMixinKey(wbiImg.imgUrl, wbiImg.subUrl)
                cachedMixinKey = key
                key
            } catch (e: Exception) {
                throw Exception("Nav Parse Error: ${e.message}. Raw: ${jsonString.take(500)}")
            }
        }
    }

    suspend fun getRecommendFeed(): List<FeedItem> {
        return withContext(Dispatchers.IO) {
            val mixinKey = ensureMixinKey()
            val params = mapOf(
                "ps" to "10",
                "fresh_type" to "3"
            )
            val signedParams = WbiUtil.signParams(params, mixinKey)
            
            val wbiAuthParams = mapOf(
                "wts" to signedParams["wts"]!!,
                "w_rid" to signedParams["w_rid"]!!
            )
            
            val responseBody = api.getRecommendFeed(pageSize = 10, freshType = 3, signedParams = wbiAuthParams)
            val jsonString = responseBody.string()

            try {
                val type = object : TypeToken<BiliResponse<FeedResponse>>() {}.type
                val response: BiliResponse<FeedResponse> = Gson().fromJson(jsonString, type)
                
                if (response.code != 0) {
                    throw Exception("Api Error: ${response.message} (code ${response.code})")
                }
                response.data?.item ?: emptyList()
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
                "fnver" to "0",
                "fourk" to "1"
            )
            
            val signedParams = WbiUtil.signParams(params, mixinKey)
            
            val wbiAuthParams = mapOf(
                "wts" to signedParams["wts"]!!,
                "w_rid" to signedParams["w_rid"]!!
            )
            
            val response = api.getPlayUrl(bvid, cid, signedParams = wbiAuthParams)
            
            if (response.code != 0) {
                throw Exception("Bili Error: ${response.message} (code ${response.code})")
            }
            
            response.data?.dash ?: throw Exception("No DASH info found")
        }
    }

    suspend fun getDanmaku(cid: Long): com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply {
        return withContext(Dispatchers.IO) {
            // Fetch segment 1 (first 6 minutes) for demo
            val responseBody = api.getDanmakuSeg(cid = cid, index = 1)
            val bytes = responseBody.bytes()
            com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply.parseFrom(bytes)
        }
    }
}
