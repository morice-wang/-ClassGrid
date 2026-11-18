# -*- coding: utf-8 -*-
import os, zipfile
root = r'D:/2026项目/大一课程表软件'
apk = os.path.join(root, '课格ClassGrid_v1.48.5.apk')
docx = os.path.join(root, '课格ClassGrid_v1.48.5_安装步骤.docx')
out = os.path.join(root, '课格ClassGrid_v1.48.5.zip')
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    z.write(apk, '课格ClassGrid_v1.48.5.apk')
    z.write(docx, '课格ClassGrid_v1.48.5_安装步骤.docx')
print('saved', out, os.path.getsize(out))