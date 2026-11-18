package com.classgrid.app

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * 一次「新版本」的完整描述，来自远端 update.json。
 *
 * 设计约定（改协议前先读这里）：
 * 1. 版本比较**只比 versionCode**（整数，单调递增）。versionName 只用于展示，
 *    不能拿来比大小——"1.9" < "1.10" 的字符串比较是错的。
 * 2. `sha256` 必须存在且是 64 位十六进制：下载完成后校验，没有校验的自动更新等于后门。
 * 3. 下载地址是列表：主通道（Gitee）+ 备用镜像（GitHub / 代理）。按顺序尝试，全部失败才算失败。
 * 4. `force` 与 `minVersionCode` 二选一或同时用：前者是「这个版本必须升」，后者是
 *    「低于这个版本号的客户端已经不兼容，必须升」。
 *
 * 纯数据 + 纯解析，不碰网络与 Android API，方便 JVM 单测。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val size: Long,
    val sha256: String,
    val urls: List<String>,
    val changelog: String,
    val force: Boolean,
    val publishedAt: String,
    val manifestUrl: String
) {

    /** 当前客户端是否必须更新（手动 force，或版本已低于最低兼容版本）。 */
    fun requiresForce(currentVersionCode: Int): Boolean =
        force || currentVersionCode < minVersionCode

    /** 主下载地址（第一个可用镜像），列表为空时返回空串。 */
    val primaryUrl: String get() = urls.firstOrNull().orEmpty()

    /** 人类可读的文件大小，如 "25.4 MB"；未知大小返回空串。 */
    fun sizeLabel(): String = formatSize(size)

    companion object {
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * 解析清单。任何关键字段不合法都返回 null（上层当作「这次没查到」，
         * 绝不能把坏清单当成「有新版」弹给用户）。
         *
         * 关键字段：versionCode > 0、versionName 非空、sha256 64 位、
         * 至少一个 https 下载地址。
         */
        fun parse(json: String, manifestUrl: String = "", allowInsecure: Boolean = false): UpdateInfo? {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null

            val versionCode = obj.optInt("versionCode", 0)
            if (versionCode <= 0) return null

            val versionName = obj.optString("versionName").trim()
            if (versionName.isEmpty()) return null

            val sha = obj.optString("sha256").trim()
            if (!SHA256.matches(sha)) return null

            val urls = buildList {
                addUrl(obj.optString("url"), allowInsecure)
                val mirrors = obj.optJSONArray("mirrors")
                if (mirrors != null) {
                    for (i in 0 until mirrors.length()) addUrl(mirrors.optString(i), allowInsecure)
                }
            }.distinct()
            if (urls.isEmpty()) return null

            val minVersionCode = obj.optInt("minVersionCode", 0).coerceAtLeast(0)
            val size = obj.optLong("size", 0L).coerceAtLeast(0L)
            val publishedAt = obj.optString("publishedAt").trim()

            return UpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                minVersionCode = minVersionCode,
                size = size,
                sha256 = sha.lowercase(Locale.US),
                urls = urls,
                changelog = obj.optString("changelog").trim(),
                force = obj.optBoolean("force", false),
                publishedAt = publishedAt,
                manifestUrl = manifestUrl
            )
        }

        /** 只接受 https 的 http(s) 地址；http 一律拒绝，避免明文被替换。 */
        private fun MutableList<String>.addUrl(raw: String, allowInsecure: Boolean) {
            val value = raw.trim()
            if (value.isEmpty()) return
            val uri = runCatching { URI(value) }.getOrNull() ?: return
            val scheme = uri.scheme?.lowercase(Locale.US)
            // 调试包允许 http（模拟器访问 10.0.2.2 本地服务）；正式包只认 https
            if (scheme != "https" && !(allowInsecure && scheme == "http")) return
            if (uri.host.isNullOrEmpty()) return
            add(value)
        }

        fun formatSize(bytes: Long): String {
            if (bytes <= 0L) return ""
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.1f MB", mb)
        }

        /** 是否有 s 比当前版本新。versionCode 无效（<=0）时返回 false。 */
        fun isNewer(remote: Int, current: Int): Boolean = remote > current
    }
}
