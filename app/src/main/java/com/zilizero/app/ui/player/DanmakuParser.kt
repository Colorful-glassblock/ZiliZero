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
            android.util.Log.e("ZiliZero_Danmaku", "PARSING START! Items: ${reply.elemsList.size}, Density: ${mContext.displayer.density}")
            
            // TEST: Inject an IMMEDIATE danmaku to verify rendering
            val testItem = mContext.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL)
            if (testItem != null) {
                testItem.flags = mContext.mGlobalFlagValues
                testItem.text = "=== 立即显示测试 (SCROLL) ==="
                testItem.time = 100 // Show almost immediately
                testItem.textSize = 50f // Very big
                testItem.textColor = Color.CYAN or -0x1000000 // Cyan, full alpha
                testItem.textShadowColor = Color.BLACK
                testItem.priority = 1 // High priority
                danmakus.addItem(testItem)
            }

            // TEST 2: Fixed Top Danmaku
            val testItem2 = mContext.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_FIX_TOP)
            if (testItem2 != null) {
                testItem2.flags = mContext.mGlobalFlagValues
                testItem2.text = "=== 顶部固定测试 (FIX TOP) ==="
                testItem2.time = 200 
                testItem2.textSize = 50f
                testItem2.textColor = Color.GREEN or -0x1000000
                testItem2.textShadowColor = Color.BLACK
                testItem2.priority = 1
                danmakus.addItem(testItem2)
            }

            reply.elemsList.forEachIndexed { index, elem ->
                if (index == 0) {
                     android.util.Log.e("ZiliZero_Danmaku", "First Item: content='${elem.content}', time=${elem.progress}, mode=${elem.mode}")
                }
                val type = when (elem.mode) {
                    1, 2, 3 -> BaseDanmaku.TYPE_SCROLL_RL
                    4 -> BaseDanmaku.TYPE_FIX_BOTTOM
                    5 -> BaseDanmaku.TYPE_FIX_TOP
                    else -> BaseDanmaku.TYPE_SCROLL_RL
                }
                
                val item = mContext.mDanmakuFactory.createDanmaku(type)
                if (item != null) {
                    item.flags = mContext.mGlobalFlagValues
                    item.text = elem.content
                    item.time = elem.progress.toLong()
                    
                    // BBLL-style scaling: Use a safe baseline (25dp -> pixels)
                    item.textSize = 30f * (mContext.displayer.density) 
                    
                    item.textColor = elem.color.toInt() or -0x1000000 // Alpha 255
                    item.textShadowColor = Color.BLACK
                    
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
