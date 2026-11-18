# -*- coding: utf-8 -*-
import zipfile, os
root = r'D:/2026项目/大一课程表软件'
apk = root + '/课格ClassGrid_v1.48.4.apk'
docx = root + '/课格ClassGrid_v1.48.4_安装步骤.docx'
out = root + '/课格ClassGrid_v1.48.4.zip'
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(apk, '课格ClassGrid_v1.48.4.apk')
    z.write(docx, '课格ClassGrid_v1.48.4_安装步骤.docx')
print('zip', out, os.path.getsize(out))