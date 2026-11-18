package com.classgrid.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * 下载器的续传 / 镜像回退 / 校验（JVM 本地 HTTP，不访问外网）。
 *
 * 守三件事：
 * 1. 断点续传必须真的只补后半段（Range），不是重下整包；
 * 2. 服务器不认 Range（回 200）时必须重下，不能把两份内容拼在一起；
 * 3. 校验不过必须换镜像；全坏必须失败，绝不把坏包装成「下载完成」。
 */
class UpdateDownloaderTest {

    private val payload = ByteArray(200_000) { (it * 31 % 251).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 支持 Range 的最小 HTTP 服务器；记录收到的 Range 头。 */
    private class RangeServer(
        private val payload: ByteArray,
        private val honorRange: Boolean = true,
        private val overrideBody: ByteArray? = null
    ) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        @Volatile var lastRange: String? = null
        @Volatile var requests = 0

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { socket ->
                            requests++
                            val input = socket.getInputStream()
                            val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n") && header.length < 32 * 1024) {
                                val b = input.read()
                                if (b < 0) break
                                header.append(b.toChar())
                            }
                            val rangeLine = header.lineSequence()
                                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                            lastRange = rangeLine?.substringAfter("bytes=")?.trim()
                            val start = if (honorRange && lastRange != null) {
                                lastRange!!.substringBefore("-").toIntOrNull() ?: 0
                            } else 0
                            val body = if (start > 0) payload.copyOfRange(start, payload.size) else payload
                            val code = if (start > 0) 206 else 200
                            val bytes = overrideBody ?: body
                            val head = "HTTP/1.1 $code OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
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
        }

        fun url(): String = "http://127.0.0.1:$port/app.apk"
        fun close() = server.close()
    }

    private fun info(urls: List<String>, size: Long = payload.size.toLong(), sha: String = sha256(payload)) =
        UpdateInfo(
            versionCode = 73,
            versionName = "1.47.4",
            minVersionCode = 0,
            size = size,
            sha256 = sha,
            urls = urls,
            changelog = "",
            force = false,
            publishedAt = "",
            manifestUrl = ""
        )

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "cg-update-${System.nanoTime()}").apply { mkdirs() }

    /**
     * 首次请求声明完整长度、却只发一段就断开的服务器（模拟真机上被掐断的连接），
     * 之后的请求按 Range 补齐。用来守住「下载器必须能自己续传补齐」这条线。
     */
    private class TruncatingServer(
        private val payload: ByteArray,
        private val firstCut: Int = 100_000
    ) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val ranges = mutableListOf<String?>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { socket ->
                            val input = socket.getInputStream()
                            val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n") && header.length < 32 * 1024) {
                                val b = input.read()
                                if (b < 0) break
                                header.append(b.toChar())
                            }
                            val range = header.lineSequence()
                                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                                ?.substringAfter("bytes=")?.trim()
                            synchronized(ranges) { ranges.add(range) }
                            val start = range?.substringBefore("-")?.toIntOrNull() ?: 0
                            val out = socket.getOutputStream()
                            if (start == 0) {
                                val head = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${payload.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                out.write(head.toByteArray(Charsets.UTF_8))
                                out.write(payload, 0, firstCut)
                            } else {
                                val body = payload.copyOfRange(start, payload.size)
                                val head = "HTTP/1.1 206 Partial Content\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                out.write(head.toByteArray(Charsets.UTF_8))
                                out.write(body)
                            }
                            out.flush()
                            socket.shutdownOutput()
                        }
                    }
                }
            }
        }

        fun url(): String = "http://127.0.0.1:$port/app.apk"
        fun close() = server.close()
    }

    @Test
    fun resumesAfterTruncatedTransfer() {
        val server = TruncatingServer(payload)
        val dir = tempDir()
        try {
            val file = UpdateDownloader.downloadInto(dir, info(listOf(server.url()))) { _, _ -> }
            assertEquals(payload.size.toLong(), file.length())
            assertArrayEquals(payload, file.readBytes())
            assertTrue(server.ranges.any { it == "100000-" })
        } finally {
            server.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun downloadsAndVerifies() {
        val server = RangeServer(payload)
        val dir = tempDir()
        try {
            val file = UpdateDownloader.downloadInto(dir, info(listOf(server.url()))) { _, _ -> }
            assertEquals(payload.size.toLong(), file.length())
            assertArrayEquals(payload, file.readBytes())
            assertTrue(UpdateDownloader.verify(file, info(listOf(server.url()))))
        } finally {
            server.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun resumesPartialDownloadWithRange() {
        val server = RangeServer(payload)
        val dir = tempDir()
        try {
            val target = File(dir, "classgrid-73.apk")
            target.writeBytes(payload.copyOfRange(0, 50_000))
            val file = UpdateDownloader.downloadInto(dir, info(listOf(server.url()))) { _, _ -> }
            assertArrayEquals(payload, file.readBytes())
            assertEquals("50000-", server.lastRange)
        } finally {
            server.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        val server = RangeServer(payload, honorRange = false)
        val dir = tempDir()
        try {
            val target = File(dir, "classgrid-73.apk")
            target.writeBytes(payload.copyOfRange(0, 50_000))
            val file = UpdateDownloader.downloadInto(dir, info(listOf(server.url()))) { _, _ -> }
            // 服务器回 200 时不能 append，否则长度会是 250000
            assertEquals(payload.size.toLong(), file.length())
            assertArrayEquals(payload, file.readBytes())
        } finally {
            server.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun retriesMirrorWhenFirstChecksumFails() {
        val corrupt = ByteArray(payload.size) { 1 }
        val bad = RangeServer(corrupt)
        val good = RangeServer(payload)
        val dir = tempDir()
        try {
            val file = UpdateDownloader.downloadInto(dir, info(listOf(bad.url(), good.url()))) { _, _ -> }
            assertArrayEquals(payload, file.readBytes())
        } finally {
            bad.close(); good.close(); dir.deleteRecursively()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun failsWhenEveryMirrorIsBad() {
        val bad = RangeServer(ByteArray(payload.size) { 2 })
        val dir = tempDir()
        try {
            UpdateDownloader.downloadInto(dir, info(listOf(bad.url()))) { _, _ -> }
        } finally {
            bad.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun progressReportsTotalSize() {
        val server = RangeServer(payload)
        val dir = tempDir()
        try {
            var lastDone = 0L
            var total = 0L
            UpdateDownloader.downloadInto(dir, info(listOf(server.url()))) { done, t ->
                lastDone = done
                total = t
            }
            assertEquals(payload.size.toLong(), lastDone)
            assertEquals(payload.size.toLong(), total)
            assertTrue(total > 0)
        } finally {
            server.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun verifyRejectsSizeMismatch() {
        val file = File.createTempFile("classgrid", ".apk").apply { writeBytes(payload) }
        try {
            assertFalse(UpdateDownloader.verify(file, info(listOf("https://x/a.apk"), size = payload.size.toLong() + 1)))
        } finally {
            file.delete()
        }
    }

    /** 把一个 APK 字节数组打进 zip，模拟 Gitee Release 里上传的发布包。 */
    private fun zipOf(apkBytes: ByteArray, entryName: String = "课格ClassGrid_v1.47.5.apk"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(entryName))
            zip.write(apkBytes)
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("安装步骤.docx"))
            zip.write("docx".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun zipArchiveIsExtractedAndVerified() {
        val zip = zipOf(payload)
        val server = RangeServer(zip)
        val dir = tempDir()
        try {
            val url = server.url().replace("app.apk", "app.zip")
            val file = UpdateDownloader.downloadInto(dir, info(listOf(url))) { _, _ -> }
            assertArrayEquals(payload, file.readBytes())
            assertTrue(UpdateDownloader.verify(file, info(listOf(url))))
        } finally {
            server.close(); dir.deleteRecursively()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun zipWithWrongApkIsRejected() {
        val zip = zipOf(ByteArray(payload.size) { 7 })
        val server = RangeServer(zip)
        val dir = tempDir()
        try {
            val url = server.url().replace("app.apk", "app.zip")
            UpdateDownloader.downloadInto(dir, info(listOf(url))) { _, _ -> }
        } finally {
            server.close(); dir.deleteRecursively()
        }
    }
}
