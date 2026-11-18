$ErrorActionPreference = "Stop"
$root = "D:\2026项目\大一课程表软件"
$tools = "D:\2026项目\大一课程表软件\classgrid-source-v1.9.0\classgrid-source\tools"
& (Join-Path $tools "gitee_publish.ps1") `
  -Tag v1.48.4 -VersionCode 86 -VersionName 1.48.4 `
  -Apk (Join-Path $root "课格ClassGrid_v1.48.4.apk") `
  -AssetName classgrid-1.48.4.apk `
  -ChangelogFile (Join-Path $tools "changelog_v1484.txt") `
  -TokenFile (Join-Path $root "令牌.txt") `
  -ExtraAssets @((Join-Path $root "课格ClassGrid_v1.48.4.zip") + ";classgrid-1.48.4.zip", (Join-Path $root "课格ClassGrid_v1.48.4_安装步骤.docx") + ";classgrid-1.48.4-guide.docx")