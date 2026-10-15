package com.takahashirinta.ncrust.walkman

import android.app.Application
import com.takahashirinta.ncrust.walkman.net.Tls

/**
 * 进程入口。这里只做一件必须最早发生的事：把 Conscrypt 插到 JCA provider 第 1 位。
 *
 * 时机很关键——`SSLContext.getDefault()` 会缓存实例，任何一次 HTTPS 之前插入才有效。
 * 放在 Application.onCreate 是唯一稳妥的位置（MainActivity 之前的任何静态初始化都可能先握手）。
 */
class WalkmanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tls.installConscrypt()
    }
}
