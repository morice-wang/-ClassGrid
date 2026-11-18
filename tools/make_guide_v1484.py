# -*- coding: utf-8 -*-
import shutil
from docx import Document
from docx.shared import Pt

src = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.3_安装步骤.docx'
dst = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.4_安装步骤.docx'
shutil.copyfile(src, dst)
doc = Document(dst)

def fix_par(p):
    for r in p.runs:
        if '1.48.3' in r.text:
            r.text = r.text.replace('1.48.3', '1.48.4')

for p in doc.paragraphs:
    fix_par(p)
for t in doc.tables:
    for row in t.rows:
        for c in row.cells:
            for p in c.paragraphs:
                fix_par(p)

items = [
    '组件文字改为跟随系统「字体大小」：以前卡片是 Canvas 按 dp 画的，系统字体调到「大/超大」时桌面图标、别的组件都变大，只有课格不动，看起来就偏小、像被压缩；现在按 sp 绘制，字会跟着系统设置一起放大（上限 1.25 倍，不会撑破胶囊）。',
    '识别「把可拉伸上限当当前尺寸」的桌面：部分品牌把 MAX_HEIGHT 报成「最多能拉多高」，位图被画成又高又长的竖条，再用 fitXY 铺进矮格子就等于把内容压扁。现在 maxH 超过 minH 的 2.2 倍就不再采信，退回默认两行。',
    '形状上限兜底：高度再离谱也不超过宽度的 1.7 倍，避免出一张远超格子的长条位图。',
    '转屏 / 改密度 / 改字号 / 切深色模式时自动重画，不用手动拉一下才恢复。',
    '升级后建议删掉旧组件重新拖一个：升级前已经画好的位图会留在桌面上，直到下一次重画才换成新尺寸。',
]

heading = doc.add_paragraph()
run = heading.add_run('十四、v1.48.4 新增：修掉部分机型小组件的「压缩感」')
run.bold = True
run.font.size = Pt(13)
for it in items:
    p = doc.add_paragraph()
    r = p.add_run('· ' + it)
    r.font.size = Pt(10.5)

doc.save(dst)
print('saved', dst)