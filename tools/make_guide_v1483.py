# -*- coding: utf-8 -*-
import copy, shutil
from docx import Document
from docx.shared import Pt

src = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.2_安装步骤.docx'
dst = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.3_安装步骤.docx'
shutil.copyfile(src, dst)
doc = Document(dst)

def fix_par(p):
    for r in p.runs:
        if '1.48.2' in r.text:
            r.text = r.text.replace('1.48.2', '1.48.3')

for p in doc.paragraphs:
    fix_par(p)
for t in doc.tables:
    for row in t.rows:
        for c in row.cells:
            for p in c.paragraphs:
                fix_par(p)

items = [
    '桌面小组件默认就是 4×2，和支付宝等 4 列组件一样宽（minWidth 340dp，桌面按它推默认占位）。',
    '自定义大小回来了：长按组件拖四边的圆点可缩可放，最小 2×2、最大约 5×5。',
    '按真实占位出图：位图尺寸＝桌面实际给的尺寸，ImageView 用 fitXY 精确铺满，不再比别的组件小一圈。',
    '卡片整体等比缩放，字号、胶囊、间距跟着一起变，拉大不会只剩一张大空卡。',
    '被拉成 2×2 / 3×3 / 4×4 时换布局：上半部主课胶囊，下半部列出今天其余课程（时间、课名、教室），整组垂直居中。',
    '修掉窄卡裁切：之前 110dp 窄卡会把主胶囊右边画到位图外，截图里能看到「胶囊被平切 + 一道硬边」，现已按真实宽度 1:1 绘制。',
    '去掉主胶囊背后那层光晕：渐变盖不到四角时会留一条可见硬边，现在只保留胶囊自身的柔和投影。',
]

# 追加新章节：沿用文档里最后一个一级/粗体标题的样式
heading = doc.add_paragraph()
run = heading.add_run('十三、v1.48.3 新增：桌面小组件尺寸与自定义大小')
run.bold = True
run.font.size = Pt(13)
for it in items:
    p = doc.add_paragraph()
    r = p.add_run('· ' + it)
    r.font.size = Pt(10.5)

doc.save(dst)
print('saved', dst)