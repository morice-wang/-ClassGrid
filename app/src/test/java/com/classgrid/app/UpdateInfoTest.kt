package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新清单解析与版本判定（纯 JVM，不联网）。
 *
 * 这些用例守的是「坏清单绝不能变成一次更新提示」：
 * 缺 versionCode、缺签名、http 地址、字段类型错，都必须返回 null。
 */
class UpdateInfoTest {

    private val sha = "9e7815596c371d86cf12672744184d8a965defa0d1fd31352f902c2920b9522f"

    private fun manifest(
        versionCode: Int = 73,
        versionName: String = "1.47.4",
        sha256: String = sha,
        url: String = "https://gitee.com/classgrid/releases/download/v1.47.4/app.apk",
        mirrors: String = "[\"https://github.com/classgrid/releases/download/v1.47.4/app.apk\"]",
        extra: String = ""
    ) = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "minVersionCode": 55,
          "size": 26614998,
          "sha256": "$sha256",
          "url": "$url",
          "mirrors": $mirrors,
          "changelog": "修复小组件变扁",
          "force": false,
          "publishedAt": "2026-11-13T20:00:00+08:00"$extra
        }
    """.trimIndent()

    @Test
    fun parseReadsEveryField() {
        val info = UpdateInfo.parse(manifest(), "https://gitee.com/x/update.json")
        assertNotNull(info)
        info!!
        assertEquals(73, info.versionCode)
        assertEquals("1.47.4", info.versionName)
        assertEquals(55, info.minVersionCode)
        assertEquals(26614998L, info.size)
        assertEquals(sha, info.sha256)
        assertEquals("修复小组件变扁", info.changelog)
        assertEquals("https://gitee.com/x/update.json", info.manifestUrl)
        assertEquals(2, info.urls.size)
        assertEquals("https://gitee.com/classgrid/releases/download/v1.47.4/app.apk", info.primaryUrl)
    }

    @Test
    fun urlsPutPrimaryFirstAndDropDuplicates() {
        val info = UpdateInfo.parse(
            manifest(mirrors = "[\"https://gitee.com/classgrid/releases/download/v1.47.4/app.apk\",\"https://gh-proxy.com/x\"]")
        )
        assertNotNull(info)
        assertEquals(2, info!!.urls.size)
        assertEquals("https://gh-proxy.com/x", info.urls[1])
    }

    @Test
    fun rejectsMissingVersionCode() {
        assertNull(UpdateInfo.parse(manifest(versionCode = 0)))
    }

    @Test
    fun rejectsBlankVersionName() {
        assertNull(UpdateInfo.parse(manifest(versionName = " ")))
    }

    @Test
    fun rejectsMalformedSha() {
        assertNull(UpdateInfo.parse(manifest(sha256 = "abc123")))
        assertNull(UpdateInfo.parse(manifest(sha256 = sha.dropLast(1))))
    }

    @Test
    fun rejectsNonHttpsDownloadUrl() {
        val info = UpdateInfo.parse(
            manifest(
                url = "http://example.com/app.apk",
                mirrors = "[]"
            )
        )
        assertNull(info)
    }

    @Test
    fun acceptsUppercaseShaAndNormalizes() {
        val info = UpdateInfo.parse(manifest(sha256 = sha.uppercase()))
        assertNotNull(info)
        assertEquals(sha, info!!.sha256)
    }

    @Test
    fun brokenJsonReturnsNull() {
        assertNull(UpdateInfo.parse("not json at all"))
        assertNull(UpdateInfo.parse(""))
    }

    @Test
    fun forceIsHonoredAndMinVersionCodeToo() {
        val info = UpdateInfo.parse(manifest())!!
        assertFalse(info.requiresForce(currentVersionCode = 73))
        assertTrue(info.requiresForce(currentVersionCode = 54))
        assertFalse(info.requiresForce(currentVersionCode = 55))
    }

    @Test
    fun explicitForceOverridesCurrentVersion() {
        // org.json 对重复 key 的行为不可依赖，这里直接替换模板里的 force 字段值
        val forced = manifest().replace("\"force\": false", "\"force\": true")
        val info = UpdateInfo.parse(forced)!!
        assertTrue(info.requiresForce(currentVersionCode = 73))
    }

    @Test
    fun isNewerComparesVersionCodeOnly() {
        assertTrue(UpdateInfo.isNewer(73, 72))
        assertFalse(UpdateInfo.isNewer(72, 72))
        assertFalse(UpdateInfo.isNewer(70, 72))
        // versionName 只是展示，字符串比较会掉进 "1.9" > "1.10" 的坑
        assertTrue(UpdateInfo.isNewer(110, 109))
    }

    @Test
    fun formatSizeIsHumanReadable() {
        assertEquals("", UpdateInfo.formatSize(0))
        assertEquals("512 B", UpdateInfo.formatSize(512))
        assertEquals("25 KB", UpdateInfo.formatSize(25 * 1024))
        assertEquals("25.4 MB", UpdateInfo.formatSize(26614998))
    }
}
