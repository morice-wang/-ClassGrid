package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 清单拉取的镜像回退（JVM 本地 HTTP，不访问外网）。
 *
 * 守两件事：
 * 1. 主镜像挂掉时必须还能从备用镜像拿到清单 —— 用户不喜欢"检查更新失败"；
 * 2. 全部挂掉时必须安静返回 null，由调用方决定提示，而不是抛异常。
 *
 * 注意：Android 单元测试的编译 classpath 里有 android.jar，`com.sun.net.httpserver`
 * 不可用，所以这里用最原始的 ServerSocket 手写一个最小 HTTP 响应。
 */
class UpdateCheckerTest {

    /** 24h 节流：从未检查、正好到期、差 1 毫秒未到期都要对。 */
    @Test
    fun isDueRespectsInterval() {
        val interval = 24 * 60 * 60 * 1000L
        assertTrue(UpdateChecker.isDue(0L, 1_000L, interval))
        assertTrue(UpdateChecker.isDue(1_000L, 1_000L + interval, interval))
        assertEquals(false, UpdateChecker.isDue(1_000L, 1_000L + interval - 1, interval))
    }

    /** 失败重试：只推迟 2 小时，不是 24 小时（断网时别一次卡一天）。 */
    @Test
    fun failedAttemptRetriesAfterTwoHours() {
        // 用真实量级的时间戳：太小会让 now-interval+retry 变成负数，被当成「从未检查过」
        val now = 1_700_000_000_000L
        val stamp = now - UpdateConfig.AUTO_CHECK_INTERVAL_MS + UpdateChecker.RETRY_DELAY_MS
        assertEquals(false, UpdateChecker.isDue(stamp, now))
        assertTrue(UpdateChecker.isDue(stamp, now + UpdateChecker.RETRY_DELAY_MS))
    }

    private val manifestJson = """
        {
          "versionCode": 73,
          "versionName": "1.47.4",
          "minVersionCode": 55,
          "size": 26614998,
          "sha256": "9e7815596c371d86cf12672744184d8a965defa0d1fd31352f902c2920b9522f",
          "url": "https://gitee.com/classgrid/releases/download/v1.47.4/app.apk",
          "mirrors": [],
          "changelog": "测试清单",
          "force": false,
          "publishedAt": "2026-11-13T20:00:00+08:00"
        }
    """.trimIndent()

    /** 只应答一次的最小 HTTP/1.1 服务器。 */
    private class OneShotServer(private val body: String, private val status: Int = 200) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        // 不能用 bufferedReader().useLines{}：关掉 socket 的输入流会连带关掉整个
                        // socket，后面写响应就会失败。这里只读到请求头结束（CRLFCRLF）。
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n") && header.length < 32 * 1024) {
                            val b = input.read()
                            if (b < 0) break
                            header.append(b.toChar())
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $status OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(head.toByteArray(Charsets.UTF_8))
                            write(bytes)
                            flush()
                        }
                        socket.shutdownOutput()
                    }
                }
            }
        }

        fun url(path: String): String = "http://127.0.0.1:$port$path"

        fun close() = server.close()
    }

    @Test
    fun fallsBackToMirrorWhenPrimaryFails() {
        val bad = OneShotServer("not a manifest")
        val good = OneShotServer(manifestJson)
        try {
            val info = UpdateChecker.fetchLatest(listOf(bad.url("/bad.json"), good.url("/good.json")))
            assertEquals(73, info!!.versionCode)
            assertEquals(good.url("/good.json"), info.manifestUrl)
        } finally {
            bad.close(); good.close()
        }
    }

    @Test
    fun skipsUnreachablePrimary() {
        val good = OneShotServer(manifestJson)
        try {
            val info = UpdateChecker.fetchLatest(
                listOf("http://127.0.0.1:1/never.json", good.url("/good.json"))
            )
            assertEquals(73, info?.versionCode)
        } finally {
            good.close()
        }
    }

    @Test
    fun returnsNullWhenEveryManifestFails() {
        val notFound = OneShotServer("{}", status = 404)
        try {
            assertNull(
                UpdateChecker.fetchLatest(
                    listOf(notFound.url("/missing.json"), "http://127.0.0.1:1/x.json")
                )
            )
        } finally {
            notFound.close()
        }
    }

    @Test
    fun rejectsOversizedManifest() {
        val huge = "{\"versionCode\":1,\"junk\":\"" + "x".repeat(80 * 1024) + "\"}"
        val server = OneShotServer(huge)
        try {
            assertNull(UpdateChecker.fetchLatest(listOf(server.url("/huge.json"))))
        } finally {
            server.close()
        }
    }

    @Test
    fun manifestUrlsInConfigAreHttps() {
        // 防止误把 http 地址写进正式清单配置
        assertTrue(UpdateConfig.MANIFEST_URLS.isNotEmpty())
        assertTrue(UpdateConfig.MANIFEST_URLS.all { it.startsWith("https://") })
    }
}
