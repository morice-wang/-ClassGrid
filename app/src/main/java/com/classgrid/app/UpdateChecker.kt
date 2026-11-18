package com.classgrid.app

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新（第一阶段：只查、只报告，不下载）。
 *
 * 流程：
 * 1. 按 UpdateConfig.MANIFEST_URLS 顺序 GET 清单（Gitee 主、GitHub 备）；
 * 2. 解析成 UpdateInfo（坏清单直接跳过，不弹「有新版」）；
 * 3. 比 versionCode：只有严格大于当前版本才算更新；
 * 4. 记下这次检查时间，供 24h 节流使用。
 *
 * 线程约定：本对象全部是阻塞网络调用，**必须在后台线程调用**，不要放在主线程。
 * 失败静默：调用方拿到 error 后自行决定是否提示，自动检查应当完全静默。
 */
object UpdateChecker {

    /** 一次检查的结果。`update` 非空表示确实有新版本。 */
    data class Result(
        val update: UpdateInfo?,
        val error: String?
    ) {
        val hasUpdate: Boolean get() = update != null
        val ok: Boolean get() = error == null
    }

    /**
     * 检查更新。
     *
     * @param currentVersionCode 当前安装包版本，默认取 BuildConfig。
     * @param ignoreSkip 手动检查时为 true：用户主动点的，不看他之前选过的「跳过此版本」。
     */
    fun check(
        context: Context,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        ignoreSkip: Boolean = true
    ): Result {
        val info = fetchLatest(manifestUrls(context)) ?: return Result(null, "无法连接更新服务器")
        markChecked(context)
        if (!UpdateInfo.isNewer(info.versionCode, currentVersionCode)) {
            return Result(null, null)
        }
        if (!ignoreSkip && Prefs.updateSkippedVersion(context) == info.versionCode) {
            return Result(null, null)
        }
        return Result(info, null)
    }

    /**
     * 拉取最新清单：按顺序尝试各镜像，返回第一个解析成功的。
     * 网络/解析都失败返回 null。
     *
     * @param urls 清单位置，默认 UpdateConfig.MANIFEST_URLS；测试里可传本地服务器。
     */
    fun fetchLatest(urls: List<String> = UpdateConfig.MANIFEST_URLS): UpdateInfo? {
        for (url in urls) {
            val body = runCatching { get(url) }.getOrNull() ?: continue
            val parsed = UpdateInfo.parse(body, url, allowInsecure = BuildConfig.DEBUG) ?: continue
            return parsed
        }
        return null
    }

    /** 调试包可以用本地清单覆盖（长按设置页「检查更新」设置）；正式包永远是配置地址。 */
    private fun manifestUrls(context: Context): List<String> {
        if (BuildConfig.DEBUG) {
            Prefs.debugUpdateManifest(context)?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        }
        return UpdateConfig.MANIFEST_URLS
    }

    /** 自动检查失败后的重试间隔：离线时别每次回前台都打一次网络。 */
    const val RETRY_DELAY_MS = 2 * 60 * 60 * 1000L

    /**
     * 是否到了下一次自动检查的时间（纯函数，便于单测）。
     *
     * @param lastCheckMillis 上次检查时间，<=0 表示从未检查过。
     */
    fun isDue(
        lastCheckMillis: Long,
        nowMillis: Long,
        interval: Long = UpdateConfig.AUTO_CHECK_INTERVAL_MS
    ): Boolean = lastCheckMillis <= 0L || nowMillis - lastCheckMillis >= interval

    /** 自动检查是否到期（距上次检查超过 24h）。从未检查过视为到期。 */
    fun shouldAutoCheck(context: Context): Boolean =
        isDue(Prefs.updateLastCheck(context), System.currentTimeMillis())

    internal fun markChecked(context: Context) {
        Prefs.setUpdateLastCheck(context, System.currentTimeMillis())
    }

    /**
     * 自动检查收尾：成功按 24h 节流；失败则只推迟 [RETRY_DELAY_MS]（2h）后重试，
     * 避免用户断网时每次回到 App 都重新发一次请求。
     */
    internal fun markAutoAttempt(context: Context, success: Boolean) {
        val now = System.currentTimeMillis()
        val stamp = if (success) {
            now
        } else {
            now - UpdateConfig.AUTO_CHECK_INTERVAL_MS + RETRY_DELAY_MS
        }
        Prefs.setUpdateLastCheck(context, stamp)
    }

    /**
     * 极简 GET：8s 连接 / 10s 读取，最多读 MAX_MANIFEST_BYTES，
     * 只接受 200；不跟随跨协议跳转（HttpURLConnection 默认只跟同协议）。
     */
    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UpdateConfig.userAgent())
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code")
            }
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > UpdateConfig.MAX_MANIFEST_BYTES) {
                        throw IOException("清单过大")
                    }
                    out.write(buffer, 0, read)
                }
                return out.toString("UTF-8")
            }
        } finally {
            conn.disconnect()
        }
    }
}
