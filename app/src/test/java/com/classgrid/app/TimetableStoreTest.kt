package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TimetableStore] 的纯逻辑单测（JVM，不依赖 Context / Robolectric）。
 *
 * 多课表真正容易出错的地方不是文件 IO（那部分复用旧 CourseStore 已验证的原子写语义），
 * 而是「索引 JSON 的容错」和「用户输入的名字」：
 *  - 索引被手改/写坏半截时，一条坏记录不能让整份索引失效（否则用户的课表会"消失"）；
 *  - id 是文件名前缀，非法 id 直接丢弃，避免 `../` 这类路径穿越写出目录；
 *  - 空名/超长名/重名要有确定的兜底行为，不能出现两张同名课表让用户分不清。
 * 迁移判定（`parseLegacy`）也在此测：null=旧文件不存在、空=空课表、损坏=null，
 * 三者语义不同，混了就会把用户的旧课表当空表覆盖掉。
 */
class TimetableStoreTest {

    // ---------------- id 生成与校验 ----------------

    @Test
    fun repairIds_重复id只保留第一次出现的() {
        val a = Course(name = "高等数学", dayOfWeek = 5, startSection = 1, endSection = 2, id = 7L)
        val b = Course(name = "基础会计", dayOfWeek = 5, startSection = 8, endSection = 10, id = 7L)
        val c = Course(name = "人工智能", dayOfWeek = 5, startSection = 3, endSection = 5, id = 9L)
        val out = TimetableStore.repairIds(listOf(a, b, c))
        assertEquals(3, out.size)
        assertEquals(7L, out[0].id)
        assertNotEquals(7L, out[1].id)
        assertEquals(9L, out[2].id)
        // 课程内容不能被动
        assertEquals("基础会计", out[1].name)
        assertEquals(8, out[1].startSection)
    }

    @Test
    fun repairIds_没有重复时返回原列表() {
        val list = listOf(
            Course(name = "高等数学", dayOfWeek = 1, startSection = 1, endSection = 2, id = 1L),
            Course(name = "大学英语", dayOfWeek = 2, startSection = 3, endSection = 4, id = 2L)
        )
        assertTrue(TimetableStore.repairIds(list) === list)
    }

    @Test
    fun newId_有前缀且单调递增不重复() {
        val ids = (1..2000).map { TimetableStore.newId() }
        assertEquals(2000, ids.toSet().size)
        ids.forEach { assertTrue("id 应能通过校验: $it", TimetableStore.isValidId(it)) }
        // 字符串长度可能不同（时间戳进位），比较数值部分
        val nums = ids.map { it.removePrefix("t_").toLong() }
        assertEquals(nums.sorted(), nums)
    }

    @Test
    fun isValidId_只接受内部生成的t加数字() {
        assertTrue(TimetableStore.isValidId("t_1"))
        assertTrue(TimetableStore.isValidId("t_1731234567890000"))
        assertFalse(TimetableStore.isValidId(""))
        assertFalse(TimetableStore.isValidId("t_"))
        assertFalse(TimetableStore.isValidId("t_abc"))
        assertFalse(TimetableStore.isValidId("x_1"))
        assertFalse(TimetableStore.isValidId("t_1 "))
        assertFalse(TimetableStore.isValidId(" t_1"))
        // 路径穿越/分隔符必须被拒（id 会拼进文件名）
        assertFalse(TimetableStore.isValidId("t_1/2"))
        assertFalse(TimetableStore.isValidId("t_../evil"))
        assertFalse(TimetableStore.isValidId("t_1.json"))
    }

    // ---------------- 课表名校验/消重 ----------------

    @Test
    fun validateName_去空白并拒绝空名() {
        assertEquals("我的课表", TimetableStore.validateName("  我的课表 "))
        assertEquals("a b", TimetableStore.validateName("a b"))
        assertNull(TimetableStore.validateName(""))
        assertNull(TimetableStore.validateName("   "))
        assertNull(TimetableStore.validateName("\n\t"))
    }

    @Test
    fun validateName_超长按最大长度截断() {
        val long = "课".repeat(TimetableStore.MAX_NAME_LEN + 15)
        val got = TimetableStore.validateName(long)
        assertNotNull(got)
        assertEquals(TimetableStore.MAX_NAME_LEN, got!!.length)
    }

    @Test
    fun uniqueName_不重名时原样返回() {
        assertEquals("高数", TimetableStore.uniqueName("高数", listOf("英语", "物理")))
        assertEquals("高数", TimetableStore.uniqueName("  高数  ", listOf("英语")))
    }

    @Test
    fun uniqueName_重名时追加序号() {
        assertEquals("高数 2", TimetableStore.uniqueName("高数", listOf("高数")))
        assertEquals("高数 3", TimetableStore.uniqueName("高数", listOf("高数", "高数 2")))
    }

    @Test
    fun uniqueName_空名退回默认名且仍会消重() {
        assertEquals(TimetableStore.DEFAULT_NAME, TimetableStore.uniqueName("", emptyList()))
        assertEquals(
            TimetableStore.DEFAULT_NAME,
            TimetableStore.uniqueName("   ", listOf("别的表"))
        )
        assertEquals(
            "${TimetableStore.DEFAULT_NAME} 2",
            TimetableStore.uniqueName("", listOf(TimetableStore.DEFAULT_NAME))
        )
    }

    @Test
    fun uniqueName_满长名字加序号后不超长且序号可见() {
        val base = "课".repeat(TimetableStore.MAX_NAME_LEN)
        val got = TimetableStore.uniqueName(base, listOf(base))
        assertEquals(TimetableStore.MAX_NAME_LEN, got.length)
        assertTrue("序号后缀不能丢: $got", got.endsWith(" 2"))
    }

    // ---------------- 索引序列化/反序列化 ----------------

    private fun meta(id: String, name: String, createdAt: Long) =
        TimetableStore.Meta(id, name, createdAt)

    @Test
    fun encodeIndex_decodeIndex_往返保序() {
        // 故意乱序传入：decode 后应按 createdAt 升序
        val index = TimetableStore.Index(
            active = "t_20",
            items = listOf(meta("t_30", "大三上", 30L), meta("t_10", "大一上", 10L), meta("t_20", "大二上", 20L))
        )
        val decoded = TimetableStore.decodeIndex(TimetableStore.encodeIndex(index))
        assertNotNull(decoded)
        assertEquals("t_20", decoded!!.active)
        assertEquals(listOf("t_10", "t_20", "t_30"), decoded.items.map { it.id })
        assertEquals(listOf("大一上", "大二上", "大三上"), decoded.items.map { it.name })
        assertEquals(10L, decoded.items.first().createdAt)
    }

    @Test
    fun decodeIndex_整份不可用时返回null() {
        assertNull(TimetableStore.decodeIndex(""))
        assertNull(TimetableStore.decodeIndex("   "))
        assertNull(TimetableStore.decodeIndex("不是 json"))
        assertNull(TimetableStore.decodeIndex("{}"))
        assertNull(TimetableStore.decodeIndex("{\"items\":[]}"))
        assertNull(TimetableStore.decodeIndex("{\"items\":\"oops\"}"))
        // 全部记录都非法 == 没有可用课表
        assertNull(TimetableStore.decodeIndex("{\"items\":[{\"id\":\"bad\"},{\"name\":\"无id\"}]}"))
        // 半截 JSON（写到一半被杀）
        assertNull(TimetableStore.decodeIndex("{\"active\":\"t_1\",\"items\":[{\"id\":\"t_1\""))
    }

    @Test
    fun decodeIndex_单条坏记录被丢弃其余保留() {
        val json = """
            {"active":"t_2","items":[
              {"id":"t_2","name":"好表","createdAt":2},
              {"id":"t_2","name":"重复id","createdAt":3},
              {"id":"../evil","name":"穿越","createdAt":4},
              {"id":"","name":"空id","createdAt":5},
              {"name":"缺id","createdAt":6},
              {"id":"t_1","name":"另一张","createdAt":1}
            ]}
        """.trimIndent()
        val decoded = TimetableStore.decodeIndex(json)
        assertNotNull(decoded)
        assertEquals(listOf("t_1", "t_2"), decoded!!.items.map { it.id })
        assertEquals("t_2", decoded.active)
    }

    @Test
    fun decodeIndex_名字缺失退回默认名超长截断() {
        val json = """
            {"active":"t_1","items":[
              {"id":"t_1","createdAt":1},
              {"id":"t_2","name":"${"课".repeat(TimetableStore.MAX_NAME_LEN + 8)}","createdAt":2}
            ]}
        """.trimIndent()
        val decoded = TimetableStore.decodeIndex(json)!!
        assertEquals(TimetableStore.DEFAULT_NAME, decoded.items[0].name)
        assertEquals(TimetableStore.MAX_NAME_LEN, decoded.items[1].name.length)
    }

    @Test
    fun decodeIndex_active失效时退回最早创建的课表() {
        val json = """
            {"active":"t_999","items":[
              {"id":"t_7","name":"晚建","createdAt":70},
              {"id":"t_5","name":"早建","createdAt":50}
            ]}
        """.trimIndent()
        val decoded = TimetableStore.decodeIndex(json)!!
        assertEquals("t_5", decoded.active)
    }

    // ---------------- 迁移判定 ----------------

    @Test
    fun parseLegacy_文件不存在视为空课表() {
        assertEquals(emptyList<Course>(), TimetableStore.parseLegacy(null))
    }

    @Test
    fun parseLegacy_空文件视为空课表() {
        assertEquals(emptyList<Course>(), TimetableStore.parseLegacy(""))
        assertEquals(emptyList<Course>(), TimetableStore.parseLegacy("  \n "))
    }

    @Test
    fun parseLegacy_损坏文件返回null以便留底() {
        assertNull(TimetableStore.parseLegacy("这不是 JSON"))
        assertNull(TimetableStore.parseLegacy("[{\"name\":"))
        // 合法 JSON 但类型不对（数组里全是非对象元素）→ 与旧 CourseStore 一致：读到空列表而非崩
        assertEquals(emptyList<Course>(), TimetableStore.parseLegacy("[1,2,3]"))
    }

    @Test
    fun parseLegacy_正常旧课表按课程读出() {
        val courses = listOf(
            Course(name = "高等数学", teacher = "张老师", dayOfWeek = 1, startSection = 1, endSection = 2),
            Course(name = "大学英语", dayOfWeek = 3, startSection = 3, endSection = 4)
        )
        val parsed = TimetableStore.parseLegacy(CourseJson.encode(courses))
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("高等数学", parsed[0].name)
        assertEquals("大学英语", parsed[1].name)
        // 老数据的 id 必须原样保留（否则编辑/删除会串台）
        assertEquals(courses[0].id, parsed[0].id)
    }

    @Test
    fun parseLegacy_对象包裹的旧格式也能读() {
        val courses = listOf(Course(name = "线性代数", dayOfWeek = 2, startSection = 5, endSection = 6))
        val wrapped = "{\"courses\":" + CourseJson.encode(courses) + "}"
        val parsed = TimetableStore.parseLegacy(wrapped)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertEquals("线性代数", parsed[0].name)
    }

    // ---------------- 其它纯逻辑 ----------------

    @Test
    fun 默认名与最大长度是稳定契约() {
        // UI（设置页/导入弹窗）直接展示这两个常量，改动会连带影响文案
        assertEquals("我的课表", TimetableStore.DEFAULT_NAME)
        assertEquals(20, TimetableStore.MAX_NAME_LEN)
    }

    @Test
    fun 生成的两张课表id不会相同() {
        assertNotEquals(TimetableStore.newId(), TimetableStore.newId())
    }
}
