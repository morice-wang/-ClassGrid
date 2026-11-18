# -*- coding: utf-8 -*-
import shutil
from docx import Document
from docx.shared import Pt

src = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.4_安装步骤.docx'
dst = r'D:/2026项目/大一课程表软件/课格ClassGrid_v1.48.5_安装步骤.docx'
shutil.copyfile(src, dst)
doc = Document(dst)

def fix_par(p):
    for r in p.runs:
        if '1.48.4' in r.text:
            r.text = r.text.replace('1.48.4', '1.48.5')

for p in doc.paragraphs:
    fix_par(p)
for t in doc.tables:
    for row in t.rows:
        for c in row.cells:
            for p in c.paragraphs:
                fix_par(p)

items = [
    '真正的原因是字体，不是尺寸也不是字号：空态「今天没有课」和已结束态这两处文案，原来按「文字宽度占胶囊宽度的比例」反推字号 —— 同一句话换一款更宽的系统字体（行楷、圆体、不少手机自带字体），量出来更宽，反推出的字号就更小，整段文字缩水，看起来像被压扁；把组件拖大、目标宽度变大，字号才回来。',
    '这一版把字号改成按高度定基准（标题取胶囊高度 24%、副标题 13%；已结束态 34% / 15%），字体宽窄只在 ±18% 内微调，不再由字体决定字号大小。',
    '实测：同一张 4×2 卡换成 serif（宋体，比默认黑体宽），旧版标题 49px 高、254px 宽；新版 68px / 356px，与默认字体的 68px / 355px 一致 —— 换字体不再影响文字大小，也不用再拖大组件。',
    '有课 / 进行中那一屏本来就不受影响：时间是固定字号，换字体只会截断，不会整体缩水。',
    '升级后建议删掉旧组件重新拖一个（旧位图会留在桌面上，直到下一次重画）。',
]

heading = doc.add_paragraph()
run = heading.add_run('十五、v1.48.5 新增：换字体后文字变小的根因修复')
run.bold = True
run.font.size = Pt(13)
for it in items:
    p = doc.add_paragraph()
    r = p.add_run('· ' + it)
    r.font.size = Pt(10.5)

doc.save(dst)
print('saved', dst)