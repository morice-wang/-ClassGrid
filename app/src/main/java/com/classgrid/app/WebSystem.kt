package com.classgrid.app

import android.content.Context

/**
 * 识别「这是哪一套教务系统」，并给出该系统的课表入口提示。
 *
 * 为什么要做：全国高校的教务系统不是同一套。0.38.0 之前按「广外智慧广外」一套写死，
 * 换个学校就抓不到。常见的是这几家（按 2025 年高校实际使用情况）：
 *
 * - 正方：jwglxt / zfsoft，登录后「信息查询 → 学生个人课表」；
 * - 强智：jsxsd / qzdatasoft，登录后「学生个人中心 → 我的课表」；
 * - 金智：ehall / 办事大厅 / urp，课表在服务大厅的「课表查询」里；
 * - 青果：kingosoft，登录后「信息查询 → 学生课表」；
 * - 少数学校自研（如上交 electsys）。
 *
 * 识别只依据 URL 与页面标题里的特征串，不联网、不依赖页面结构；识别不出来就返回
 * [Family.UNKNOWN]，界面照样能用，只是不给提示。
 */
object WebSystem {

    enum class Family {
        ZHENGFANG,
        QIANGZHI,
        JINZHI,
        QINGGUO,
        URP,
        CUSTOM,
        UNKNOWN
    }

    /** URL / 标题特征 → 系统。顺序有讲究：先匹配特征更强的。 */
    private val RULES: List<Pair<Regex, Family>> = listOf(
        Regex("""(zfsoft|zfcsoft|jwglxt|zhengfang)""", RegexOption.IGNORE_CASE) to Family.ZHENGFANG,
        Regex("""(qzdatasoft|jsxsd)""", RegexOption.IGNORE_CASE) to Family.QIANGZHI,
        Regex("""kingosoft""", RegexOption.IGNORE_CASE) to Family.QINGGUO,
        Regex("""(urp|uims|jwmis)""", RegexOption.IGNORE_CASE) to Family.URP,
        Regex("""(electsys|dean\.|zhjw\.)""", RegexOption.IGNORE_CASE) to Family.CUSTOM,
        Regex("""(ehall|authserver|cas|sso|一网通办|办事大厅)""", RegexOption.IGNORE_CASE) to Family.JINZHI
    )

    fun detect(url: String?, title: String?): Family {
        val hay = (url.orEmpty() + " " + title.orEmpty())
        if (hay.isBlank()) return Family.UNKNOWN
        for ((regex, family) in RULES) {
            if (regex.containsMatchIn(hay)) return family
        }
        return Family.UNKNOWN
    }

    fun label(context: Context, family: Family): String = when (family) {
        Family.ZHENGFANG -> context.getString(R.string.web_system_zhengfang)
        Family.QIANGZHI -> context.getString(R.string.web_system_qiangzhi)
        Family.JINZHI -> context.getString(R.string.web_system_jinzhi)
        Family.QINGGUO -> context.getString(R.string.web_system_qingguo)
        Family.URP -> context.getString(R.string.web_system_urp)
        Family.CUSTOM -> context.getString(R.string.web_system_custom)
        Family.UNKNOWN -> context.getString(R.string.web_system_unknown)
    }

    /**
     * 课表入口提示：用户在登录页上看到「登录成功后课表在哪」，比一句「抓取」有用得多。
     * 识别不出来时给通用提示（不同系统的共性：登录 → 找「课表 / 我的课表 / 学生课表」）。
     */
    fun hint(context: Context, family: Family): String = when (family) {
        Family.ZHENGFANG -> context.getString(R.string.web_system_hint_zhengfang)
        Family.QIANGZHI -> context.getString(R.string.web_system_hint_qiangzhi)
        Family.JINZHI -> context.getString(R.string.web_system_hint_jinzhi)
        Family.QINGGUO -> context.getString(R.string.web_system_hint_qingguo)
        Family.URP -> context.getString(R.string.web_system_hint_urp)
        Family.CUSTOM -> context.getString(R.string.web_system_hint_custom)
        Family.UNKNOWN -> context.getString(R.string.web_system_hint_unknown)
    }
}
