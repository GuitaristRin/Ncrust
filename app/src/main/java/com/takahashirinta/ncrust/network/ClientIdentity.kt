package com.takahashirinta.ncrust.network

/**
 * eapi 请求的**客户端身份 cookie**（写操作 / 取链共用）。
 *
 * 服务端按请求 Cookie 里的身份字段判定「这是不是一个真实客户端」：
 *  - **写操作**（歌单增删改）：只带 `MUSIC_U`/`__csrf` 的会话会被回 `403 illegal request!`；
 *  - **取链**：缺 `os`/`appver` 会被静默封顶音质（`code` 仍是 200，客户端看不出异常）。
 *
 * 本对象只碰 `os`/`appver`/`osver`/`deviceId` 这四个键，其余（`MUSIC_U`、`__csrf`、`NMTID` …）
 * 逐字节保留。两类字段的处置**不同**：
 *  - `os`/`appver`/`osver`：**覆盖值、保留原位置**。用户 cookie 里若带着一个空值的 `appver=`，
 *    「有同名键就跳过」会让我们**永久不再声明身份**，于是写操作被拒、音质静默降级 —— 这类
 *    「键名语义错配」不报错，是最难发现的一类缺陷。
 *  - `deviceId`：用户 cookie 里**已有就不动**（那个才是服务端认识的那个会话设备 id），
 *    没有才补本设备的持久 id；换掉已有的等于换了会话指纹。
 *
 * [withIdentity] 返回**合并后的完整 cookie 串**，直接作为 [RetrofitClient.eapiPost] 的
 * `cookieOverride` 传入。纯函数，JVM 单测直接断言字段与顺序。
 */
object ClientIdentity {
    /** 客户端类型。pc 身份实测可解锁写操作与 Hi-Res。 */
    const val OS = "pc"

    /** appver 取自 2026-09 ncm PC 官方客户端（实测有效）；服务端降权时随官方版本更新。 */
    const val APPVER = "3.0.6"

    /** Win10 版本号；只为客户端指纹自洽，实测非必需。 */
    const val OSVER = "10.0.19045"

    private const val COOKIE_DEVICE_ID = "deviceId"
    private val IDENTITY_FIELDS = listOf("os" to OS, "appver" to APPVER, "osver" to OSVER)

    /** 便捷重载：设备 id 取当前进程的持久 id（复用 xeapi 的 deviceId，一台设备一个）。 */
    fun withIdentity(userCookie: String?): String = withIdentity(userCookie, RetrofitClient.deviceId())

    /**
     * 把身份字段并入用户 cookie 串。顺序固定「用户字段在前、身份字段在后」；覆盖的
     * `os`/`appver`/`osver` 保留其**原有位置**，用户没有的补在末尾。
     */
    fun withIdentity(userCookie: String?, deviceId: String): String {
        val parts = userCookie?.trim().orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        val out = ArrayList<String>(parts.size + IDENTITY_FIELDS.size + 1)

        for (part in parts) {
            val key = part.substringBefore('=', "").trim()
            if (key.isEmpty()) continue
            val lower = key.lowercase()
            seen.add(lower)
            val value = identityValueFor(lower)
            // 身份字段：保留这个键原来的位置，只换值；其余原样保留。
            out.add(if (value != null) "$key=$value" else part)
        }
        for ((k, v) in IDENTITY_FIELDS) {
            if (k !in seen) out.add("$k=$v")
        }
        if (COOKIE_DEVICE_ID.lowercase() !in seen) out.add("$COOKIE_DEVICE_ID=$deviceId")
        return out.joinToString("; ")
    }

    private fun identityValueFor(lowerKey: String): String? = when (lowerKey) {
        "os" -> OS
        "appver" -> APPVER
        "osver" -> OSVER
        else -> null
    }
}
