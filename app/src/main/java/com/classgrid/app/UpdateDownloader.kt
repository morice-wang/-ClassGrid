package com.classgrid.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 安装包下载器（第二阶段）：支持断点续传、镜像重试、流式 SHA-256 校验，
 * 以及 **zip 发布包**（Release 里上传的是 zip 时，自动取出里面的 APK 再校验）。
 *
 * 设计要点：
 * - 文件落在 App 私有 externalFilesDir/updates，不需要任何存储权限；
 * - 续传用 HTTP Range；服务器不支持 Range（回 200）就整份重下；
 * - 下载完必须过 size + sha256 双重校验（清单里的 size/sha256 指**解包后的 APK**）；
 * - 取消靠线程中断，抛 [CancelledException] 让调用方区分「用户取消」和「网络失败」。
 */
object UpdateDownloader {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val BUFFER_SIZE = 64 * 1024

    /** 一次下载最多续传几轮，防止服务器一直返回空响应时死循环。 */
    private const val MAX_FETCH_ATTEMPTS = 4

    /** 用户取消（线程被中断）。 */
    class CancelledException : IOException("下载已取消")

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "updates").apply { if (!exists()) mkdirs() }
    }

    /** 解包后可供安装的 APK 路径。 */
    fun file(context: Context, versionCode: Int): File =
        File(dir(context), "classgrid-$versionCode.apk")

    /**
     * 下载、必要时解包、校验，成功返回**APK**文件。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时传 -1。
     * @throws CancelledException 线程被中断
     * @throws IOException 全部镜像失败 / 校验不通过
     */
    @Throws(IOException::class)
    fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = downloadInto(dir(context), info, onProgress)

    /**
     * 同 [download]，但目录由调用方给（JVM 单测用，避免碰 Android Context）。
     */
    @Throws(IOException::class)
    fun downloadInto(
        directory: File,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        if (!directory.exists()) directory.mkdirs()
        val apk = File(directory, "classgrid-${info.versionCode}.apk")
        if (apk.exists() && verify(apk, info)) return apk

        val archive = File(directory, "classgrid-${info.versionCode}.download")
        var lastError: IOException? = null
        for (url in info.urls) {
            try {
                val archiveSize = expectedArchiveSize(info, url)
                if (archiveSize > 0) {
                    // 普通 APK：直接下到目标文件，保留断点续传能力
                    fetch(url, apk, archiveSize, onProgress)
                } else {
                    // zip 发布包：先下归档，再解出 APK 校验
                    if (archive.exists()) archive.delete()
                    if (apk.exists()) apk.delete()
                    fetch(url, archive, -1L, onProgress)
                    extractApk(archive, apk)
                }
                if (verify(apk, info)) {
                    archive.delete()
                    return apk
                }
                apk.delete()
                lastError = IOException("安装包校验失败，已删除")
            } catch (cancelled: CancelledException) {
                throw cancelled
            } catch (e: IOException) {
                lastError = e
            }
        }
        archive.delete()
        throw lastError ?: IOException("没有可用的下载地址")
    }

    /**
     * 归档文件的预期大小：清单里的 size 指解包后的 APK，所以 zip 包无法预知大小，
     * 交给 Content-Length 判断；普通 APK 直接用清单 size。
     */
    private fun expectedArchiveSize(info: UpdateInfo, url: String): Long =
        if (url.substringBefore('?').lowercase().endsWith(".zip")) -1L else info.size

    /**
     * 单次 HTTP 传输。
     *
     * 注意：真机上出现过「服务器提前收尾，文件比 Content-Length 少几个字节」的情况
     * （模拟器 NAT 偶尔丢最后一段）。所以这里按 [MAX_FETCH_ATTEMPTS] 轮「续传补齐」：
     * 每轮先看已有字节，仍缺就用 Range 接着要，补齐后立刻返回，绝不让一次不完整的
     * 传输把整个更新判死。
     */
    private fun fetch(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        var attempt = 0
        var lastError: IOException? = null
        while (attempt < MAX_FETCH_ATTEMPTS) {
            attempt++
            val existing = if (target.exists()) target.length() else 0L
            val resume = existing > 0 && (expectedSize <= 0 || existing < expectedSize)

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive,application/zip,application/octet-stream,*/*")
            conn.setRequestProperty("User-Agent", UpdateConfig.userAgent())
            if (resume) conn.setRequestProperty("Range", "bytes=$existing-")

            var contentLength = 0L
            try {
                val append = when (val code = conn.responseCode) {
                    200 -> false
                    206 -> true
                    else -> throw IOException("HTTP $code")
                }
                contentLength = conn.contentLengthLong
                val total = when {
                    append -> existing + contentLength
                    contentLength > 0 -> contentLength
                    else -> expectedSize
                }
                val start = if (append) existing else 0L
                var written = start
                FileOutputStream(target, append).use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancelledException()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                        out.flush()
                    }
                }
            } catch (cancelled: CancelledException) {
                throw cancelled
            } catch (e: IOException) {
                // 连接被提前掐断：记下错误，下面看已写入的字节决定要不要续传
                lastError = e
            } finally {
                conn.disconnect()
            }

            val got = if (target.exists()) target.length() else 0L
            val wanted = if (expectedSize > 0) expectedSize else contentLength
            if (wanted <= 0 || got == wanted) return
            if (attempt >= MAX_FETCH_ATTEMPTS) break
        }
        throw lastError ?: IOException("下载不完整：${if (target.exists()) target.length() else 0}")
    }

    /**
     * 把下载到的归档解成可安装的 APK：
     * - zip 发布包 → 取体积最大的 `.apk` 条目；
     * - 普通 APK → 直接改名复制。
     */
    @Throws(IOException::class)
    fun extractApk(archive: File, target: File) {
        if (!isZip(archive)) {
            if (!archive.renameTo(target)) archive.copyTo(target, overwrite = true)
            return
        }
        ZipFile(archive).use { zip ->
            var best: java.util.zip.ZipEntry? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!entry.name.lowercase().endsWith(".apk")) continue
                if (best == null || entry.size > best!!.size) best = entry
            }
            val entry = best ?: throw IOException("压缩包里没有找到 APK")
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { out -> input.copyTo(out, BUFFER_SIZE) }
            }
        }
        if (!target.exists() || target.length() <= 0) throw IOException("解压安装包失败")
    }

    /** 按文件头判断是不是 zip（PK\x03\x04）。 */
    fun isZip(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        file.inputStream().use { input ->
            val head = ByteArray(4)
            if (input.read(head) != 4) return false
            return head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        }
    }

    fun verify(file: File, info: UpdateInfo): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        if (info.size > 0 && file.length() != info.size) return false
        return sha256(file).equals(info.sha256, ignoreCase = true)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
