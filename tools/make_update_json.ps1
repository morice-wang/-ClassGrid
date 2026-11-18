# 生成 update.json（自动更新清单）
#
# 用法（在 classgrid-source 目录下运行，先构建好 release 包）：
#   .\tools\make_update_json.ps1 -VersionCode 74 -VersionName "1.47.5" `
#       -AssetUrl "https://gitee.com/moricecc/class-grid/releases/download/v1.47.5/xxx.apk" `
#       -ChangelogFile changelog.txt
#
# 输出 update.json（UTF-8 无 BOM），把它提交到 Gitee 仓库根目录的 master 分支即可。
# 注意：APK 文件名是中文时，AssetUrl 要用浏览器地址栏里复制出来的 percent-encoded 链接。
# 中文更新说明请用 -ChangelogFile 传文件（PowerShell 5.1 命令行传中文会乱码）。

param(
    [Parameter(Mandatory = $true)][string]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][string]$AssetUrl,
    [string]$ChangelogFile = "",
    [int]$MinVersionCode = 1,
    [switch]$Force,
    [string]$Apk = "app\build\outputs\apk\release\app-release.apk",
    [string]$Out = "update.json"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Apk)) { throw "找不到 APK: $Apk （先跑 .\gradlew.bat :app:assembleRelease）" }

$changelog = ""
if ($ChangelogFile -ne "" -and (Test-Path $ChangelogFile)) {
    $changelog = [System.IO.File]::ReadAllText((Resolve-Path $ChangelogFile), [System.Text.Encoding]::UTF8).Trim()
}

$apkFile = Get-Item $Apk
$size = $apkFile.Length
$sha = (Get-FileHash -Path $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()

$manifest = [ordered]@{
    versionCode    = [int]$VersionCode
    versionName    = $VersionName
    minVersionCode = $MinVersionCode
    size           = $size
    sha256         = $sha
    url            = $AssetUrl
    mirrors        = @()
    changelog      = $changelog
    force          = [bool]$Force
    publishedAt    = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
}

$json = $manifest | ConvertTo-Json -Depth 4
$outPath = Join-Path (Get-Location) $Out
[System.IO.File]::WriteAllText($outPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "已生成 $outPath"
Write-Host ("  版本   : {0} / {1}" -f $VersionName, $VersionCode)
Write-Host ("  大小   : {0}" -f $size)
Write-Host ("  SHA-256: {0}" -f $sha)
Write-Host ("  地址   : {0}" -f $AssetUrl)
Write-Host ""
Write-Host "下一步：把 update.json 提交到仓库 master 根目录，确认能打开："
Write-Host "  https://gitee.com/moricecc/class-grid/raw/master/update.json"