import json, base64, os, urllib.request
TOKEN = os.environ.get("GITEE_TOKEN", "")
API="https://gitee.com/api/v5/repos/moricecc/class-grid/contents/update.json"
data={
 "versionCode":88,
 "versionName":"1.48.6",
 "minVersionCode":1,
 "size":26705935,
 "sha256":"1b2902d678ac879bad4f5fbc35b2fce7ffc76288c6e421e0e0a2141d344f5a15",
 "url":"https://gitee.com/moricecc/class-grid/releases/download/v1.48.6/classgrid-1.48.6.apk",
 "mirrors":[],
 "changelog":"v1.48.6（versionCode 88）— 桌面小组件重做：不再被拉伸、不再重叠\n\n1. 卡片底与文字内容分成两层位图：底层圆角/渐变铺满整格，上层文字只等比缩小、绝不拉伸 —— 桌面把格子尺寸报错也不会再把字压扁。\n2. 去掉整体缩放，字号/内边距/胶囊高度只跟宽度相关：拉高只多出空间，不会把字放大。\n3. 顶部「今天 / 第几周」固定在最上面一行，主胶囊从它下方往下排，结构上不可能再重叠。\n4. 卡片很高时把今天已上完的课接着列出（颜色淡一档），余下留白用「今天共 N 节课」收尾。\n5. 4x2/2x2/4x4、深色模式均已实测；升级后建议删掉旧组件重新拖一个。",
 "force":False,
 "publishedAt":"2026-11-16T21:40:00+08:00"
}
raw=json.dumps(data, ensure_ascii=False, indent=4).encode("utf-8")
req=urllib.request.Request(API+"?access_token="+TOKEN)
sha=json.load(urllib.request.urlopen(req))["sha"]
body=json.dumps({"access_token":TOKEN,"content":base64.b64encode(raw).decode(),"sha":sha,"message":"release v1.48.6"}).encode()
req2=urllib.request.Request(API+"?access_token="+TOKEN, data=body, method="PUT", headers={"Content-Type":"application/json"})
r=json.load(urllib.request.urlopen(req2))
print("OK", r["commit"]["sha"][:8])