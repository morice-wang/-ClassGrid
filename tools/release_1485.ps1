$ErrorActionPreference = 'Stop'
$root = 'D:/2026项目/大一课程表软件'
$src  = 'D:/2026项目/大一课程表软件/classgrid-source-v1.9.0/classgrid-source'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $src 'tools/gitee_publish.ps1') `
    -Tag 'v1.48.5' -VersionCode 87 -VersionName '1.48.5' `
    -Apk (Join-Path $root '课格ClassGrid_v1.48.5.apk') -AssetName 'classgrid-1.48.5.apk' `
    -ChangelogFile (Join-Path $src 'tools/changelog_v1485.txt') `
    -TokenFile (Join-Path $root '令牌.txt') `
    -ExtraAssets @((Join-Path $root '课格ClassGrid_v1.48.5.zip') + ';classgrid-1.48.5.zip', (Join-Path $root '课格ClassGrid_v1.48.5_安装步骤.docx') + ';classgrid-1.48.5-guide.docx') `
    -UploadRate '1500k' -MaxRetries 3