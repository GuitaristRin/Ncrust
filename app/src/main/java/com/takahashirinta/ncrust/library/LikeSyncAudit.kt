package com.takahashirinta.ncrust.library

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** 留存最近 256 条事件；不写 Cookie、token、账号或设备标识。仅在 IO 写任务调用。 */
internal object LikeSyncAudit {
    @Synchronized
    fun record(context: Context, songId: Long, desired: Boolean, stage: String, matched: Boolean) {
        runCatching {
            val file = File(context.filesDir, "like-sync-audit.jsonl")
            val event = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("songId", songId)
                .put("desired", desired)
                .put("transport", "xeapi-v1")
                .put("stage", stage)
                .put("matched", matched)
                .toString()
            val lines = if (file.exists()) file.readLines().takeLast(255) else emptyList()
            file.writeText((lines + event).joinToString("\n", postfix = "\n"))
        }.onFailure { Log.w("LibraryManager", "like audit unavailable: ${it.javaClass.simpleName}") }
    }
}
