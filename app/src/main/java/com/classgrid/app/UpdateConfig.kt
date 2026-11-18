package com.classgrid.app

/**
 * 更新通道配置。
 *
 * 清单放 Gitee 仓库根目录的 update.json（走 raw 链接，始终指向最新版），APK 放同一个
 * 仓库的 Release 附件。国内 Gitee 直连快（实测 Release 附件 1–5 MB/s）。
 *
 * 维护方式：
 *   - `tools/sign_release.ps1` 出包后，用 `tools/make_update_json.ps1` 生成 update.json；
 *   - 把新的 APK 传到 Release（tag 与版本号一致），把 update.json 提交到 master 根目录。
 *
 * 清单格式见 [UpdateInfo] 的文档注释。
 */
object UpdateConfig {

    /** 主清单地址（Gitee raw，固定路径，内容随发版更新）。 */
    const val PRIMARY_MANIFEST =
        "https://gitee.com/moricecc/class-grid/raw/master/update.json"

    /**
     * 备用清单地址。
     *
     * 目前仓库只在 Gitee 上，这里默认留空；将来在 GitHub 建镜像仓库后把 raw 地址填进来，
     * [MANIFEST_URLS] 会自动按顺序回退。
     */
    const val MIRROR_MANIFEST = ""

    /** 按顺序尝试；一次 check 只取第一个解析成功的清单。 */
    val MANIFEST_URLS: List<String> =
        listOf(PRIMARY_MANIFEST, MIRROR_MANIFEST).filter { it.isNotBlank() }

    /** 自动检查的最小间隔：24 小时。 */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** 清单很小，超过这个大小说明拿到的不是清单（可能是错误页），直接放弃。 */
    const val MAX_MANIFEST_BYTES = 64 * 1024

    fun userAgent(): String = "ClassGrid/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.CHANNEL})"
}
