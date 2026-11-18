# -*- coding: utf-8 -*-
import os, zipfile
root = r'D:/2026项目/大一课程表软件'
apk = os.path.join(root, '课格ClassGrid_v1.48.6.apk')
docx = os.path.join(root, '课格ClassGrid_v1.48.6_安装步骤.docx')
out = os.path.join(root, '课格ClassGrid_v1.48.6.zip')
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    z.write(apk, '课格ClassGrid_v1.48.6.apk')
    z.write(docx, '课格ClassGrid_v1.48.6_安装步骤.docx')
print('saved', out, os.path.getsize(out))