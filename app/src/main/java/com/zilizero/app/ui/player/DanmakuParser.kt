package com.zilizero.app.ui.player

import android.graphics.Color
import com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.parser.IDataSource

class DanmakuParser : BaseDanmakuParser() {

    override fun parse(): IDanmakus {
        val danmakus = Danmakus()
        val dataSource = mDataSource
        
        if (dataSource is DmDataSource) {
            val reply = dataSource.data()
            android.util.Log.e("ZiliZero_Danmaku", "PARSING START! Items: ${reply.elemsList.size}")
            
            // TEST: Inject a fake danmaku to verify UI rendering
            val testItem = mContext.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL)
            if (testItem != null) {
                testItem.text = "=== TEST DANMAKU DEBUG ==="
                testItem.time = 5000 // Show at 5th second
                testItem.textSize = 40f
                testItem.textColor = Color.RED or -0x1000000
                testItem.textShadowColor = Color.WHITE
                danmakus.addItem(testItem)
            }

            reply.elemsList.forEach { elem ->
                val type = when (elem.mode) {
                    1, 2, 3 -> BaseDanmaku.TYPE_SCROLL_RL
                    4 -> BaseDanmaku.TYPE_FIX_BOTTOM
                    5 -> BaseDanmaku.TYPE_FIX_TOP
                    else -> BaseDanmaku.TYPE_SCROLL_RL
                }
                
                val item = mContext.mDanmakuFactory.createDanmaku(type)
                if (item != null) {
                    item.text = elem.content
                    item.time = elem.progress.toLong()
                    item.textSize = 25f * (mContext.displayer.density - 0.6f)
                    item.textColor = elem.color.toInt() or -0x1000000 // Alpha 255
                    item.textShadowColor = Color.BLACK // Standard stroke
                    
                    danmakus.addItem(item)
                }
            }
        }
        return danmakus
    }
}

class DmDataSource(private val reply: DmSegMobileReply) : IDataSource<DmSegMobileReply> {
    override fun data(): DmSegMobileReply = reply
    override fun release() {}
}
