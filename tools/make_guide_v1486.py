# -*- coding: utf-8 -*-
import shutil
from docx import Document
from docx.shared import Pt

src = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.5_安装步骤.docx'
dst = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.6_安装步骤.docx'
shutil.copyfile(src, dst)
doc = Document(dst)

def fix_par(p):
    for r in p.runs:
        if '1.48.5' in r.text:
            r.text = r.text.replace('1.48.5', '1.48.6')

for p in doc.paragraphs:
    fix_par(p)
for t in doc.tables:
    for row in t.rows:
        for c in row.cells:
            for p in c.paragraphs:
                fix_par(p)

items = [
    '这一版把小组件的画法整个换掉了：卡片底和文字内容分成两层位图 —— 底层只画圆角、渐变、高光，铺满整格；上层只画文字和胶囊，只等比缩小、绝不拉伸。桌面就算把格子尺寸报错，文字也不会再被压扁。',
    '去掉整体缩放后，字号、内边距、胶囊高度只跟宽度相关：把组件拉高只多出空间，不会把字放大；拉窄也不会挤成一团。',
    '顶部「今天 / 第几周」固定在最上面一行，主胶囊从它下方往下排、下面的课接着排 —— 旧版扁平卡片会把主胶囊压到顶部胶囊上，这一版结构上不可能再重叠。',
    '卡片很高时把今天已上完的课接着列出来（颜色淡一档），还不够就用右下角「今天共 N 节课」收尾，不再出现胶囊悬在正中、上下两片空白。',
    '4×2 默认尺寸实测：时间/教室 + 课名 + 还剩 N 分 + 下一节，与 1.47.1 的信息密度一致；2×2、4×4、深色模式都已实测。',
    '升级后建议删掉旧组件重新拖一个（旧位图会留在桌面上，直到下一次重画）。',
]

heading = doc.add_paragraph()
run = heading.add_run('十六、v1.48.6 新增：桌面小组件重做（不再被拉伸、不再重叠）')
run.bold = True
run.font.size = Pt(13)
for it in items:
    p = doc.add_paragraph()
    r = p.add_run('· ' + it)
    r.font.size = Pt(10.5)

doc.save(dst)
print('saved', dst)