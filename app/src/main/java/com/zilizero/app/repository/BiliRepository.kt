package com.zilizero.app.repository

import com.zilizero.app.network.BilibiliApi
import com.zilizero.app.network.DashInfo
import com.zilizero.app.network.FeedItem
import com.zilizero.app.network.NetworkClient
import com.zilizero.app.network.WbiUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            val mixinKey = ensureMixinKey()
            
            val params = mapOf(
                "ps" to "20",
                "fresh_type" to "3"
            )
            
            val signedParams = WbiUtil.signParams(params, mixinKey)
            val response = api.getRecommendFeed(signedParams)
            
            if (response.code != 0) {
                throw Exception("Bili Error: ${response.message}")
            }
            
            response.data?.item ?: emptyList()
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
