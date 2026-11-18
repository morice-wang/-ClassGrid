package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 教务系统识别测试（v1.39.0）。
 *
 * 逻辑很简单，但错一次的代价是给用户指错菜单：比如把强智的 jsxsd 认成正方，
 * 状态栏就会让他去「信息查询 → 学生个人课表」，而强智里根本没有这个菜单。
 */
class WebSystemTest {

    @Test
    fun detectsZhengfangFromJwglxt() {
        assertEquals(
            WebSystem.Family.ZHENGFANG,
            WebSystem.detect("https://jwglxt.pku.edu.cn/jwglxt/xtgl/login_slogin.html", "登录")
        )
        assertEquals(
            WebSystem.Family.ZHENGFANG,
            WebSystem.detect("https://jw.example.edu.cn/zfsoft/", "正方教务管理系统")
        )
    }

    @Test
    fun detectsQiangzhiFromJsxsd() {
        assertEquals(
            WebSystem.Family.QIANGZHI,
            WebSystem.detect("https://jwxt.gdufs.edu.cn/jsxsd/xskb/xskb_list.do", "学生课表")
        )
        assertEquals(
            WebSystem.Family.QIANGZHI,
            WebSystem.detect("https://jw.qzdatasoft.com/", "强智科技")
        )
    }

    @Test
    fun detectsQingguoUrpAndHall() {
        assertEquals(
            WebSystem.Family.QINGGUO,
            WebSystem.detect("https://jw.kingosoft.com/", "青果软件")
        )
        assertEquals(
            WebSystem.Family.URP,
            WebSystem.detect("https://urp.example.edu.cn/", "URP 教务系统")
        )
        assertEquals(
            WebSystem.Family.JINZHI,
            WebSystem.detect("https://ehall.example.edu.cn/", "办事大厅")
        )
        assertEquals(
            WebSystem.Family.JINZHI,
            WebSystem.detect("https://authserver.example.edu.cn/cas/login", "统一身份认证")
        )
    }

    @Test
    fun detectsSchoolBuiltSystems() {
        assertEquals(
            WebSystem.Family.CUSTOM,
            WebSystem.detect("https://electsys.sjtu.edu.cn/edu/", "上海交通大学")
        )
        assertEquals(
            WebSystem.Family.CUSTOM,
            WebSystem.detect("https://dean.pku.edu.cn/service/web/courseSearch.do", "北京大学")
        )
    }

    @Test
    fun unknownWhenNothingMatches() {
        assertEquals(WebSystem.Family.UNKNOWN, WebSystem.detect(null, null))
        assertEquals(WebSystem.Family.UNKNOWN, WebSystem.detect("", ""))
        assertEquals(
            WebSystem.Family.UNKNOWN,
            WebSystem.detect("https://www.example.edu.cn/", "某某大学")
        )
    }

    @Test
    fun urlBeatsTitle() {
        // 标题里写着「教务」但 URL 是强智：按特征更强的 URL 判，别被「教务」二字误导
        assertEquals(
            WebSystem.Family.QIANGZHI,
            WebSystem.detect("https://jw.example.edu.cn/jsxsd/", "教务系统")
        )
    }
}
