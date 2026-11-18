# 给已有的 Gitee Release 补传单个附件（网页上传报「服务器返回 0 状态码」/ 大文件被掐断时用这个）。
#
# 上传一律走 curl + --limit-rate：Gitee 的 WAF 会对突发速率掐连接，
# 不限速上传 20MB+ 会在 4~6MB / 20 秒左右被重置；限速 1500k 后 26MB 稳定 19 秒传完。
#
# 用法：
#   & .\tools\upload_release_asset.ps1 -TokenFile "..\..\令牌.txt" -Tag v1.48.1 -File "D:\...\课格ClassGrid_v1.48.1.zip"
#   # 单独指定资产名、自定义限速（0 = 不限速，仅小文件）
#   ... -AssetName classgrid-1.48.1.zip -UploadRate 1200k
#
# 注意：正式发版优先用 gitee_publish.ps1 -ExtraAssets（APK 与附加文件一次搞定）。

param(
    [string]$Token = "",
    [string]$TokenFile = "",
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$File,
    [string]$AssetName = "",
    [string]$Owner = "moricecc",
    [string]$Repo = "class-grid",
    [string]$UploadRate = "1500k",
    [int]$MaxRetries = 3,
    [switch]$CreateRelease
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if ([string]::IsNullOrWhiteSpace($Token)) {
    if ($TokenFile -eq "") { throw "要么给 -Token，要么给 -TokenFile（令牌文件路径）" }
    if (-not (Test-Path -LiteralPath $TokenFile)) { throw "找不到令牌文件：$TokenFile" }
    $Token = ([System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenFile).Path)).Trim()
}
if ([string]::IsNullOrWhiteSpace($Token)) { throw "令牌是空的" }

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { throw "找不到 curl.exe（Windows 10+ 自带），无法限速上传" }

$base = "https://gitee.com/api/v5/repos/$Owner/$Repo"
$authHeaders = @{ Authorization = "token $Token" }
$filePath = (Resolve-Path -LiteralPath $File).Path
$fileName = if ($AssetName -ne "") { $AssetName } else { [System.IO.Path]::GetFileName($filePath) }
$size = (Get-Item -LiteralPath $filePath).Length

# 1. 找 Release
$release = $null
try {
    $probe = Invoke-RestMethod -Uri "$base/releases/tags/$Tag" -Method Get -TimeoutSec 30
    if ($probe -and $probe.id) { $release = $probe }
} catch {
    $release = $null
}

if (-not $release) {
    if (-not $CreateRelease) { throw "找不到 tag $Tag 的 Release（可加 -CreateRelease 自动创建）" }
    $body = @{ tag_name = $Tag; name = "课格 $Tag"; target_commitish = "master" } | ConvertTo-Json
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $release = Invoke-RestMethod -Uri "$base/releases?access_token=$Token" -Method Post -Body $bodyBytes `
        -ContentType "application/json; charset=utf-8" -TimeoutSec 30
    Write-Host "已创建 Release $Tag (id=$($release.id))"
}

$existing = @($release.assets | Where-Object { $_.name -eq $fileName })
if ($existing.Count -gt 0) {
    Write-Host "同名资产已存在，跳过：$Tag/$fileName → $($existing[0].browser_download_url)"
    Write-Host "（要覆盖先到网页删掉该资产再传）"
    return
}

# 2. curl 限速上传。curl 在 Windows 上按 ANSI 转参数，中文路径会坏，先复制到纯 ASCII 临时路径。
$tmpDir = Join-Path $env:TEMP ("cg_up_" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
$uploadFile = Join-Path $tmpDir ("payload" + [System.IO.Path]::GetExtension($filePath))
Copy-Item -LiteralPath $filePath -Destination $uploadFile -Force
$bodyFile = Join-Path $tmpDir "resp.json"
$attachUri = "$base/releases/$($release.id)/attach_files?access_token=$Token"

Write-Host "上传 $fileName（$([math]::Round($size / 1MB, 2)) MB）→ Release $Tag，限速 $UploadRate ..."
try {
    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        Write-Host "  尝试 $attempt/$MaxRetries ..."
        $args = @("-sS", "-H", "Expect:", "-m", "900")
        if ($UploadRate -and $UploadRate -ne "0") { $args += @("--limit-rate", $UploadRate) }
        $args += @("-X", "POST", "-F", "file=@$uploadFile;filename=$fileName", $attachUri, "-o", $bodyFile)

        Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
        & $curl.Source @args
        $exit = $LASTEXITCODE

        if ($exit -eq 0 -and (Test-Path -LiteralPath $bodyFile)) {
            $json = $null
            try { $json = Get-Content -LiteralPath $bodyFile -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $json = $null }
            if ($json -and $json.id) {
                if ($json.size -ne $size) { throw "上传后大小不一致：本地 $size / 线上 $($json.size)" }
                Write-Host "上传成功："
                Write-Host ("  资产 id: {0}" -f $json.id)
                Write-Host ("  名称   : {0}（{1} 字节）" -f $json.name, $json.size)
                Write-Host ("  地址   : {0}" -f $json.browser_download_url)
                return
            }
        }

        Write-Host "  失败（curl 退出码 $exit）"
        if ($attempt -lt $MaxRetries) {
            Start-Sleep -Seconds 3
            $rel = Invoke-RestMethod -Uri "$base/releases/$($release.id)" -Headers $authHeaders -Method Get -TimeoutSec 30
            $hit = @($rel.assets | Where-Object { $_.name -eq $fileName })
            if ($hit.Count -gt 0) {
                Write-Host "  服务端已有同名资产，视为成功：$($hit[0].browser_download_url)"
                return
            }
            Write-Host "  3 秒后重试..."
        }
    }
    throw "上传失败：$MaxRetries 次尝试均未成功"
} finally {
    Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
