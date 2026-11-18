package com.classgrid.app

import com.classgrid.app.SectionTimes.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.48.0 作息库的纯逻辑单测（不碰 Context） */
class ScheduleProfilesTest {

    private fun s(a: String, b: String) = Section(a, b)

    // ---- 时间工具 ----

    @Test
    fun hhmm_formatsAndWraps() {
        assertEquals("08:30", ScheduleProfiles.hhmm(510))
        assertEquals("00:00", ScheduleProfiles.hhmm(0))
        assertEquals("00:05", ScheduleProfiles.hhmm(1445))
    }

    @Test
    fun gaps_skipsFirstAndComputesBreak() {
        val list = listOf(s("08:30", "09:10"), s("09:20", "10:00"), s("10:00", "10:40"))
        val g = ScheduleProfiles.gaps(list)
        assertNull(g[0])
        assertEquals(10, g[1])
        assertEquals(0, g[2])
    }

    @Test
    fun gapLabel_wording() {
        assertEquals("—", ScheduleProfiles.gapLabel(null, "08:30"))
        assertEquals("10分", ScheduleProfiles.gapLabel("09:10", "09:20"))
        assertEquals("连堂", ScheduleProfiles.gapLabel("10:00", "10:00"))
        assertEquals("重叠", ScheduleProfiles.gapLabel("10:10", "10:00"))
    }

    // ---- 推算 ----

    @Test
    fun derive_buildsEvenCadence() {
        val list = ScheduleProfiles.derive("08:30", 3, 40, 10)
        assertEquals(3, list.size)
        assertEquals("08:30", list[0].start)
        assertEquals("09:10", list[0].end)
        assertEquals("09:20", list[1].start)
        assertEquals("10:00", list[1].end)
        assertEquals("10:10", list[2].start)
        assertEquals("10:50", list[2].end)
    }

    @Test
    fun derive_invalidInputGivesEmpty() {
        assertTrue(ScheduleProfiles.derive("25:00", 4, 40, 10).isEmpty())
        assertTrue(ScheduleProfiles.derive("08:30", 0, 40, 10).isEmpty())
    }

    @Test
    fun derive_clampsDurationAndBreak() {
        val list = ScheduleProfiles.derive("08:00", 2, 999, 999)
        assertEquals("08:00", list[0].start)
        assertEquals("11:00", list[0].end)
        assertEquals("13:00", list[1].start)
    }

    @Test
    fun cascade_shiftsLaterSectionsKeepingDuration() {
        val list = ScheduleProfiles.derive("08:30", 3, 40, 10)
        val moved = ScheduleProfiles.cascade(list, 1, "09:30", 40, 10)
        assertEquals("08:30", moved[0].start)
        assertEquals("09:10", moved[0].end)
        assertEquals("09:30", moved[1].start)
        assertEquals("10:10", moved[1].end)
        assertEquals("10:20", moved[2].start)
        assertEquals("11:00", moved[2].end)
    }

    // ---- 节数增减 ----

    @Test
    fun resize_fromEmptyFillsDefaults() {
        val list = ScheduleProfiles.resize(emptyList(), 12)
        assertEquals(12, list.size)
        assertEquals("08:30", list[0].start)
        assertNull(ScheduleProfiles.validate(list))
    }

    @Test
    fun resize_shrinkDropsTail() {
        val list = ScheduleProfiles.derive("08:30", 15, 40, 10)
        val shrunk = ScheduleProfiles.resize(list, 10)
        assertEquals(10, shrunk.size)
        assertEquals(list[9], shrunk[9])
    }

    @Test
    fun resize_growKeepsExistingAndCadence() {
        val base = ScheduleProfiles.derive("08:30", 2, 40, 10)
        val grown = ScheduleProfiles.resize(base, 4)
        assertEquals(4, grown.size)
        assertEquals("08:30", grown[0].start)
        assertEquals("10:10", grown[2].start)
        assertEquals("11:00", grown[3].start)
    }

    @Test
    fun resize_clampsToOneAndMax() {
        assertEquals(ScheduleProfiles.MAX_SECTIONS, ScheduleProfiles.resize(emptyList(), 999).size)
        assertEquals(1, ScheduleProfiles.resize(ScheduleProfiles.derive("08:30", 5, 40, 10), 0).size)
    }

    // ---- 校验 / 摘要 ----

    @Test
    fun validate_acceptsGoodTable() {
        assertNull(ScheduleProfiles.validate(ScheduleProfiles.derive("08:30", 14, 45, 5)))
    }

    @Test
    fun validate_reportsProblems() {
        assertEquals("至少要有一节", ScheduleProfiles.validate(emptyList()))
        assertEquals("第 1 节开始时间格式不对", ScheduleProfiles.validate(listOf(s("8点30", "09:10"))))
        assertEquals("第 1 节结束时间要晚于开始时间", ScheduleProfiles.validate(listOf(s("09:10", "09:10"))))
        assertEquals(
            "第 2 节开始时间早于第 1 节结束时间",
            ScheduleProfiles.validate(listOf(s("08:30", "09:10"), s("09:00", "09:40")))
        )
    }

    @Test
    fun validate_allowsBackToBack() {
        assertNull(ScheduleProfiles.validate(listOf(s("08:30", "09:10"), s("09:10", "09:50"))))
    }

    @Test
    fun summary_text() {
        assertEquals("未设置", ScheduleProfiles.summary(emptyList()))
        assertEquals("2 节 · 08:30–10:00", ScheduleProfiles.summary(listOf(s("08:30", "09:10"), s("09:20", "10:00"))))
    }

    // ---- 名字 ----

    @Test
    fun cleanName_trimsAndCaps() {
        assertNull(ScheduleProfiles.cleanName("   "))
        assertNull(ScheduleProfiles.cleanName(null))
        assertEquals("广外 作息", ScheduleProfiles.cleanName("  广外   作息 "))
        assertEquals(ScheduleProfiles.MAX_NAME_LEN, ScheduleProfiles.cleanName("一二三四五六七八九十一二三四五六七八九十")!!.length)
    }

    @Test
    fun uniqueName_appendsIndex() {
        assertEquals("我的作息", ScheduleProfiles.uniqueName("我的作息", emptyList()))
        assertEquals("我的作息(2)", ScheduleProfiles.uniqueName("我的作息", listOf("我的作息")))
        assertEquals("我的作息(3)", ScheduleProfiles.uniqueName("我的作息", listOf("我的作息", "我的作息(2)")))
    }

    // ---- 序列化 ----

    @Test
    fun sections_roundTrip() {
        val list = ScheduleProfiles.derive("08:30", 6, 45, 5)
        val back = ScheduleProfiles.decodeSections(ScheduleProfiles.encodeSections(list))
        assertNotNull(back)
        assertEquals(list, back)
    }

    @Test
    fun decodeSections_skipsBrokenEntries() {
        val json = "[{\"start\":\"08:30\",\"end\":\"09:10\"},{\"start\":\"xx\",\"end\":\"09:40\"},{\"start\":\"10:00\",\"end\":\"10:40\"}]"
        val back = ScheduleProfiles.decodeSections(json)!!
        assertEquals(2, back.size)
        assertEquals("10:00", back[1].start)
        assertNull(ScheduleProfiles.decodeSections(""))
        assertNull(ScheduleProfiles.decodeSections("not json"))
        assertNull(ScheduleProfiles.decodeSections("[]"))
    }

    @Test
    fun profiles_roundTrip() {
        val sections = ScheduleProfiles.derive("08:30", 5, 40, 10)
        val list = listOf(
            ScheduleProfiles.Profile("p1", "广外作息", sections, "gdufs", 1L),
            ScheduleProfiles.Profile("p2", "我的作息", sections, null, 2L)
        )
        val back = ScheduleProfiles.decodeProfiles(ScheduleProfiles.encodeProfiles(list))
        assertEquals(2, back.size)
        assertEquals("广外作息", back[0].name)
        assertEquals("gdufs", back[0].presetKey)
        assertEquals(sections, back[0].sections)
        assertNull(back[1].presetKey)
    }

    @Test
    fun decodeProfiles_toleratesGarbage() {
        assertTrue(ScheduleProfiles.decodeProfiles("").isEmpty())
        assertTrue(ScheduleProfiles.decodeProfiles("{}").isEmpty())
        val broken = "[{\"id\":\"p1\",\"name\":\"a\"},{\"id\":\"\",\"sections\":[]},{\"id\":\"p3\",\"name\":\"c\",\"sections\":\"[]\"}]"
        assertTrue(ScheduleProfiles.decodeProfiles(broken).isEmpty())
    }

    @Test
    fun binding_roundTrip() {
        val map = mapOf("t_1" to "p1", "t_2" to "p2")
        assertEquals(map, ScheduleProfiles.decodeBinding(ScheduleProfiles.encodeBinding(map)))
        assertTrue(ScheduleProfiles.decodeBinding(null).isEmpty())
    }

    // ---- 预设数据 ----

    @Test
    fun presets_areWellFormed() {
        val presets = ScheduleProfiles.presets()
        assertTrue("广东高校预设至少 6 份", presets.size >= 6)
        assertEquals(presets.size, presets.map { it.key }.toSet().size)
        presets.forEach { p ->
            assertTrue(p.name + " 名字不能为空", p.name.isNotBlank())
            assertTrue(p.name + " 至少 8 节", p.sections.size >= 8)
            assertNull(p.name + " 时间表要合法：" + ScheduleProfiles.validate(p.sections), ScheduleProfiles.validate(p.sections))
            assertTrue(p.name + " 首节应在上午", (ScheduleProfiles.startMinutes(p.sections.first()) ?: 0) < 12 * 60)
            assertTrue(p.name + " 末节应在晚上", (ScheduleProfiles.endMinutes(p.sections.last()) ?: 0) > 17 * 60)
        }
    }

    @Test
    fun presets_coverGuangdongUniversities() {
        val schools = ScheduleProfiles.presets().map { it.school }.toSet()
        listOf("广东外语外贸大学", "广东工业大学", "华南理工大学", "华南师范大学", "中山大学", "深圳大学")
            .forEach { assertTrue("缺 " + it + " 预设", it in schools) }
    }

    @Test
    fun presets_haveStableKeys() {
        val keys = ScheduleProfiles.presets().map { it.key }.toSet()
        listOf("gdufs", "gdut", "scut", "scnu", "sysu", "szu").forEach {
            assertTrue("缺 " + it + " 预设", it in keys)
        }
        assertNotNull(ScheduleProfiles.preset("scut"))
        assertNull(ScheduleProfiles.preset("nope"))
    }

    @Test
    fun gdufsDefault_isLegacy15Sections() {
        val def = SectionTimes.default()
        assertEquals(15, def.size)
        assertEquals("08:30", def.first().start)
        assertEquals("21:20", def.last().end)
        assertEquals(def, ScheduleProfiles.gdufsSections())
    }

    @Test
    fun presetSections_areIndependentCopies() {
        val preset = ScheduleProfiles.preset("scut")!!
        val edited = preset.sections.toMutableList()
        edited[0] = s("09:00", "09:45")
        assertEquals("08:00", ScheduleProfiles.preset("scut")!!.sections[0].start)
    }
}
