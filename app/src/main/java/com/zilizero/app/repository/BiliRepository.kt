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
                "ps" to "10",
                "fresh_type" to "3"
            )
            val signedParams = WbiUtil.signParams(params, mixinKey)
            
            // Passing default params explicitly to avoid compiler issues with Retrofit defaults
            // Note: signedParams already contains 'wts' and 'w_rid', and we don't need to pass ps/fresh_type again in Query args if we put them in QueryMap, 
            // BUT BilibiliApi definition has fixed params.
            // Actually, we should pass the signedParams map which CONTAINS w_rid and wts.
            // The fixed params (ps, fresh_type) will be added by Retrofit as Query params.
            // We need to make sure we don't duplicate them or miss them in signature.
            // WbiUtil.signParams returns a map with ALL params (including ps, fresh_type, wts, w_rid).
            // So we should just pass the EXTRA params (wts, w_rid) to the QueryMap, OR change API to use QueryMap for everything.
            
            // Current API: getRecommendFeed(ps, fresh_type, signedParams)
            // If we pass 'ps' and 'fresh_type' as arguments, Retrofit adds them.
            // signedParams map SHOULD ONLY contain 'wts' and 'w_rid' then? 
            // NO. Wbi signature requires ALL params to be sorted and hashed.
            // But when sending request, if we send 'ps' twice (once from arg, once from map), it might be an issue.
            
            // Strategy: Extract only wts and w_rid from the signed map to pass to the QueryMap.
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
            
            val responseBody = api.getPlayUrl(bvid, cid, signedParams = wbiAuthParams)
            val jsonString = responseBody.string()
            
            try {
                val type = object : TypeToken<BiliResponse<PlayUrlResponse>>() {}.type
                val response: BiliResponse<PlayUrlResponse> = Gson().fromJson(jsonString, type)
                
                if (response.code != 0) {
                    throw Exception("Bili Error: ${response.message} (code ${response.code})")
                }
                
                response.data?.dash ?: throw Exception("No DASH info found")
            } catch (e: Exception) {
                throw Exception("PlayUrl Parse Error: ${e.message}. Raw: ${jsonString.take(500)}")
            }
        }
    }

    suspend fun getDanmaku(cid: Long): com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply {
        return withContext(Dispatchers.IO) {
            // Fetch segment 1 (first 6 minutes) for demo
            api.getDanmakuSeg(cid = cid, index = 1)
        }
    }
}
