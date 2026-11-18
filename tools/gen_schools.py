# -*- coding: utf-8 -*-
"""生成 app/src/main/assets/schools.json。

数据来源：
  https://github.com/dataxiv/data-universities
  （教育部全国高等学校名单 + 官网链接整理，数据文件 tools/data/data_all.tsv）

用法：
  python tools/gen_schools.py

输出字段（数组形式以压缩体积）：
  [学校名称, 省份, 城市, 办学层次, 官网链接, 全拼, 拼音首字母, 标签, 英文名,
   教务系统链接, 教务链接是否已验证(1/0)]
"""
import csv
import json
import os
import re

from pypinyin import lazy_pinyin

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "data", "data_all.tsv")
JW_SRC = os.path.join(ROOT, "tools", "data", "jw_result.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "schools.json")

PROVINCE_MAP = {
    "北京市": "北京", "天津市": "天津", "河北省": "河北", "山西省": "山西",
    "内蒙古自治区": "内蒙古", "辽宁省": "辽宁", "吉林省": "吉林", "黑龙江省": "黑龙江",
    "上海市": "上海", "江苏省": "江苏", "浙江省": "浙江", "安徽省": "安徽",
    "福建省": "福建", "江西省": "江西", "山东省": "山东", "河南省": "河南",
    "湖北省": "湖北", "湖南省": "湖南", "广东省": "广东", "广西壮族自治区": "广西",
    "海南省": "海南", "重庆市": "重庆", "四川省": "四川", "贵州省": "贵州",
    "云南省": "云南", "西藏自治区": "西藏", "陕西省": "陕西", "甘肃省": "甘肃",
    "青海省": "青海", "宁夏回族自治区": "宁夏", "新疆维吾尔自治区": "新疆",
}
PROVINCE_ORDER = [
    "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
    "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
    "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
    "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆",
]


def clean_site(raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    s = s.replace(" ", "")
    if not re.match(r"^https?://", s, re.I):
        s = "https://" + s.lstrip("/")
    return re.sub(r"^(https?)://", lambda m: m.group(1).lower() + "://", s, flags=re.I)


def pinyin_of(name: str) -> str:
    py = "".join(lazy_pinyin(name)).lower()
    return re.sub(r"[^a-z0-9]", "", py)


def initials_of(name: str) -> str:
    ini = "".join(p[0] for p in lazy_pinyin(name) if p and p[0].isalpha())
    return re.sub(r"[^a-z]", "", ini.lower())


def clean_jw(raw: str) -> str:
    """教务系统链接：只保留 http(s) 地址；空串表示没找到。"""
    s = clean_site(raw)
    if not s:
        return ""
    return s


def load_jw() -> dict:
    """tools/data/jw_result.json：{学校名: {jw, verified, ...}}（由 tools/crawl_jw.py 生成）。"""
    if not os.path.exists(JW_SRC):
        return {}
    with open(JW_SRC, "r", encoding="utf-8") as f:
        raw = json.load(f)
    out = {}
    for name, v in raw.items():
        jw = clean_jw(v.get("jw", ""))
        if jw:
            out[name] = (jw, 1 if v.get("verified") else 0)
    return out


def build() -> int:
    jw_map = load_jw()
    # 广外：官网是自适应的（手机竖屏、电脑横屏），且教务系统电脑版会把同一门课拆成
    # 「卡片表 + 周次辅助表」两份，早期版本在这里导过重复。入口改回官网，让用户从官网进。
    jw_map.pop("广东外语外贸大学", None)
    rows = []
    with open(SRC, "r", encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            if r.get("状态") != "1":
                continue
            level = r.get("办学层次", "")
            if level not in ("本科", "专科"):
                continue
            prov = PROVINCE_MAP.get(r.get("地区", ""))
            if not prov:
                continue
            name = r.get("学校名称", "").strip()
            if not name:
                continue
            site = clean_site(r.get("官网链接"))
            jw, jw_ok = jw_map.get(name, ("", 0))
            rows.append({
                "name": name,
                "province": prov,
                "city": (r.get("所在地") or "").strip(),
                "level": level,
                "site": site,
                "py": pinyin_of(name),
                "ini": initials_of(name),
                "tags": (r.get("双一流建设") or "").replace("，", ",").replace("、", ",").strip(),
                "en": (r.get("英文名称") or "").replace(" ", "").lower(),
                "jw": "" if jw == site else jw,
                "jwv": jw_ok if jw != site else 0,
            })

    def rank(s):
        tags = s["tags"]
        if "985" in tags:
            return 0
        if "211" in tags:
            return 1
        if "双一流" in tags:
            return 2
        return 3 if s["level"] == "本科" else 4

    rows.sort(key=lambda s: (rank(s), s["py"]))
    data = {
        "version": "2026-11",
        "source": "教育部全国高等学校名单 + dataxiv/data-universities 官网整理；教务系统入口由 tools/crawl_jw.py 从各校官网抓取并抽验",
        "provinces": PROVINCE_ORDER,
        "schools": [
            [s["name"], s["province"], s["city"], s["level"], s["site"],
             s["py"], s["ini"], s["tags"], s["en"], s["jw"], s["jwv"]]
            for s in rows
        ],
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    empty = sum(1 for s in rows if not s["site"])
    with_jw = sum(1 for s in rows if s["jw"])
    verified = sum(1 for s in rows if s["jwv"])
    print("schools=%d emptySite=%d jw=%d verifiedJw=%d size=%dKB"
          % (len(rows), empty, with_jw, verified, os.path.getsize(OUT) // 1024))
    return 0


if __name__ == "__main__":
    raise SystemExit(build())
