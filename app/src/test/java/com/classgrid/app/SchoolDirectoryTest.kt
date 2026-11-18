package com.classgrid.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SchoolDirectory 的纯逻辑测试（v1.38.0）：
 * 选校页能不能用，取决于「解析出来的名单对不对」和「搜索/网址处理稳不稳」，
 * 这两块都不依赖 Android，所以直接单测，不用跑模拟器。
 */
class SchoolDirectoryTest {

    private val sampleJson = """
        {
          "version": "2025-11",
          "source": "unit-test",
          "provinces": ["北京", "广东"],
          "schools": [
            ["清华大学", "北京", "北京市", "本科", "https://www.tsinghua.edu.cn/", "qinghuadaxue", "qhdx", "211，985，双一流", "tsinghua university"],
            ["北京大学", "北京", "北京市", "本科", "https://www.pku.edu.cn/", "beijingdaxue", "bjdx", "211，985，双一流", "peking university"],
            ["广东外语外贸大学", "广东", "广州市", "本科", "https://www.gdufs.edu.cn/", "guangdongwaiyuwaimaodaxue", "gdwyywmdx", "", "guangdong university of foreign studies", "https://jwxt.gdufs.edu.cn/", 1],
            ["深圳职业技术大学", "广东", "深圳市", "专科", "", "shenzhenzhiyejishudaxue", "szzyjsdx", "", "shenzhen polytechnic university"]
          ]
        }
    """.trimIndent()

    private fun schools(): List<SchoolDirectory.School> = SchoolDirectory.parse(sampleJson)

    // ---------------------------------------------------------------- 解析

    @Test
    fun parseReadsEveryField() {
        val list = schools()
        assertEquals(4, list.size)

        val tsinghua = list[0]
        assertEquals("清华大学", tsinghua.name)
        assertEquals("北京", tsinghua.province)
        assertEquals("北京市", tsinghua.city)
        assertEquals("本科", tsinghua.level)
        assertEquals("https://www.tsinghua.edu.cn/", tsinghua.site)
        assertEquals("qinghuadaxue", tsinghua.pinyin)
        assertEquals("qhdx", tsinghua.initials)
        assertEquals("211，985，双一流", tsinghua.tags)
        assertEquals("tsinghua university", tsinghua.english)
        assertEquals(false, tsinghua.custom)
    }

    @Test
    fun parseReadsJwEntryAndVerifiedFlag() {
        val gdufs = schools()[2]
        // 第 10/11 项是 v1.39.0 新增的「教务系统入口 + 是否抽验可用」
        assertEquals("https://jwxt.gdufs.edu.cn/", gdufs.jw)
        assertEquals(true, gdufs.jwVerified)
        assertTrue(SchoolDirectory.hasBuiltinJw(gdufs))
        // 没有教务入口的学校：jw 为空，抓取时退回官网
        assertTrue(schools()[0].jw.isEmpty())
        assertTrue(!SchoolDirectory.hasBuiltinJw(schools()[0]))
    }

    @Test
    fun metaPutsTagsInFixedOrder() {        // 数据文件里写的是「211，985，双一流」，展示时统一成 985 → 211 → 双一流
        assertEquals("北京 · 本科 · 985 · 211 · 双一流", schools()[0].meta())
        // 没有标签的学校只显示地区 + 层次
        assertEquals("广东 · 本科", schools()[2].meta())
    }

    @Test
    fun parseSkipsRowsWithoutName() {
        // 只有名称行没有地区是合法的（自定义学校就没地区）；名称空、或行内容为空才跳过
        val json = """{"schools":[["", "北京"], [], ["只有名字"], ["某某大学", "广东"]]}"""
        val list = SchoolDirectory.parse(json)
        assertEquals(2, list.size)
        assertEquals("只有名字", list[0].name)
        assertEquals("某某大学", list[1].name)
    }

    // ---------------------------------------------------------------- 搜索

    @Test
    fun searchByNamePinyinAndInitials() {
        assertEquals("清华大学", SchoolDirectory.search(schools(), "清华").first().name)
        assertEquals("清华大学", SchoolDirectory.search(schools(), "qinghua").first().name)
        assertEquals("清华大学", SchoolDirectory.search(schools(), "qhdx").first().name)
        assertEquals("北京大学", SchoolDirectory.search(schools(), "bjdx").first().name)
        assertEquals("清华大学", SchoolDirectory.search(schools(), "tsinghua").first().name)
    }

    @Test
    fun searchKeepsDataOrderForEqualScores() {
        // 四个校名都含「大学」，分数相同，顺序应保持数据里的 985/211 顺序
        val names = SchoolDirectory.search(schools(), "大学").map { it.name }
        assertEquals(
            listOf("清华大学", "北京大学", "广东外语外贸大学", "深圳职业技术大学"),
            names
        )
    }

    @Test
    fun searchCanBeLimitedToProvince() {
        val inGuangdong = SchoolDirectory.search(schools(), "", "广东")
        assertEquals(2, inGuangdong.size)
        assertTrue(inGuangdong.all { it.province == "广东" })

        // 关键词 + 省份同时生效：深圳职院不在「北京」，搜不到
        assertTrue(SchoolDirectory.search(schools(), "深圳", "北京").isEmpty())
    }

    @Test
    fun searchUnknownKeywordReturnsEmpty() {
        assertTrue(SchoolDirectory.search(schools(), "不存在的学校").isEmpty())
        // 空关键词 + 没有省份 = 全量，且不改变顺序
        assertEquals(schools().map { it.name }, SchoolDirectory.search(schools(), "").map { it.name })
    }

    // ---------------------------------------------------------------- 网址

    @Test
    fun normalizeUrlAddsSchemeAndKeepsPath() {
        assertEquals("https://www.pku.edu.cn", SchoolDirectory.normalizeUrl("www.pku.edu.cn"))
        assertEquals("http://www.pku.edu.cn/", SchoolDirectory.normalizeUrl("http://www.pku.edu.cn/"))
        assertEquals(
            "https://gdufs.edu.cn/jwxt/xskb?xn=2025#top",
            SchoolDirectory.normalizeUrl("gdufs.edu.cn/jwxt/xskb?xn=2025#top")
        )
        // 协议名统一小写，域名保留用户输入的大小写
        assertEquals("https://WWW.PKU.EDU.CN", SchoolDirectory.normalizeUrl("HTTPS://WWW.PKU.EDU.CN"))
    }

    @Test
    fun normalizeUrlRejectsGarbage() {
        assertEquals("", SchoolDirectory.normalizeUrl(""))
        assertEquals("", SchoolDirectory.normalizeUrl("   "))
        assertEquals("", SchoolDirectory.normalizeUrl("abc"))
        assertEquals("", SchoolDirectory.normalizeUrl("http://"))
        assertEquals("", SchoolDirectory.normalizeUrl("https://localhost"))
    }

    @Test
    fun hostOfStripsPathAndPort() {
        assertEquals(
            "jwxt.gdufs.edu.cn",
            SchoolDirectory.hostOf("https://jwxt.gdufs.edu.cn/xskb/xskb_list.do?xn=2025")
        )
        assertEquals("", SchoolDirectory.hostOf("not a url"))
    }

    @Test
    fun displaySiteHidesSchemeAndTrailingSlash() {
        assertEquals("www.pku.edu.cn", SchoolDirectory.displaySite("https://www.pku.edu.cn/"))
        assertEquals("jwxt.gdufs.edu.cn", SchoolDirectory.displaySite("http://jwxt.gdufs.edu.cn"))
        assertEquals("", SchoolDirectory.displaySite(""))
    }

    @Test
    fun entryCandidatesPutsBuiltinJwFirstAndHomepageLast() {
        val list = SchoolDirectory.entryCandidates(
            "https://jwxt.gdufs.edu.cn/jsxsd/",
            "https://www.gdufs.edu.cn/"
        )
        // 内置教务入口必须排第一：它才是用户真正要去的页面
        assertEquals("教务系统", list.first().first)
        assertEquals("https://jwxt.gdufs.edu.cn/jsxsd/", list.first().second)
        // 官网永远垫底：前面的猜测全失败时才回退到官网找入口
        assertEquals("学校官网", list.last().first)
        assertEquals("https://www.gdufs.edu.cn/", list.last().second)

        val labels = list.map { it.first }
        assertTrue("jwxt 子域" in labels)
        assertTrue("jwglxt 子域" in labels)
        assertTrue("jwgl 子域" in labels)
        assertTrue("jwc 子域" in labels)
        assertTrue("ehall 子域" in labels)
        assertTrue("one 子域" in labels)
    }

    @Test
    fun candidateUrlsWithoutJwEndsWithHomepage() {
        val list = SchoolDirectory.candidateUrls("https://www.gdufs.edu.cn/")
        assertEquals(
            listOf("jwxt 子域", "jwglxt 子域", "jwgl 子域", "jwc 子域", "ehall 子域", "one 子域", "学校官网"),
            list.map { it.first }
        )
        assertEquals("https://www.gdufs.edu.cn/", list.last().second)
        assertTrue(SchoolDirectory.candidateUrls("").isEmpty())
        // 只有教务入口、没有官网时也能用
        assertEquals(
            listOf("教务系统"),
            SchoolDirectory.entryCandidates("https://jw.example.edu.cn/", "").map { it.first }
        )
    }

    // ---------------------------------------------------------------- 内置名单（回归）

    @Test
    fun bundledListCoversMainlandSchools() {
        val file = File("src/main/assets/schools.json")
        assertTrue("缺少 assets/schools.json，选校页会空列表", file.exists())
        val text = file.readText(Charsets.UTF_8)
        val list = SchoolDirectory.parse(text)

        // 与 MOE 2024 名单的 状态=1 条目数一致：本科 1308 + 专科 1560
        assertEquals(2868, list.size)
        assertEquals(31, JSONObject(text).getJSONArray("provinces").length())

        val names = list.map { it.name }
        assertEquals("学校名单有重名", names.size, names.distinct().size)

        assertTrue(list.any { it.name == "清华大学" && it.site.startsWith("http") })
        assertTrue(list.any { it.name == "广东外语外贸大学" })
        // 147 所没有官网（名单里允许留空，用户自己补），其余网址必须带协议
        assertEquals(2721, list.count { it.site.isNotEmpty() })
        assertTrue(list.all { it.site.isEmpty() || it.site.startsWith("http") })

        // v1.39.0：教务系统入口由 tools/crawl_jw.py 从各校官网抓取（2026-11 快照 1272 所，863 所抽验可用）。
        // 其余学校没有内置入口，App 会按子域猜测并对失败自动回退官网 —— 所以这里只要求数量别塌。
        assertTrue(
            "教务入口数量异常：" + list.count { it.jw.isNotEmpty() },
            list.count { it.jw.isNotEmpty() } >= 1200
        )
        assertTrue(list.all { it.jw.isEmpty() || it.jw.startsWith("http") })
        assertTrue(list.count { it.jwVerified } >= 800)
        // 抽两所真实学校：北大的 dean 入口、广外的强智入口
        assertTrue(list.any { it.name == "北京大学" && it.jw.contains("dean.pku.edu.cn") })
        // 广外官网是自适应的，入口改回官网（v1.42.2：电脑版教务系统会拆出周次副本导致重复）\n        assertTrue(\n            "广外不应该再内置教务系统入口：" + list.first { it.name == "广东外语外贸大学" },\n            list.none { it.name == "广东外语外贸大学" && it.jw.isNotEmpty() }\n        )
    }
}
