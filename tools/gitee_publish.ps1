<#
.SYNOPSIS
  课格 ClassGrid 一键发版：建/复用 Release → 上传 APK → 生成并推送 update.json → 回读校验。

.DESCRIPTION
  - Release 里放 APK；update.json 是仓库 master 根目录的普通文件，用 Contents API 推送。
  - 资产名默认取 APK 文件名；建议用 ASCII（如 classgrid-1.47.6.apk），中文名也可以（下载地址会被 percent-encode）。
  - 同名资产已存在时跳过上传（要覆盖先到网页删掉）。
  - 【上传限速】Gitee 的 WAF 会对突发速率掐连接：不限速上传 20MB+ 会在 4~6MB / 20 秒左右被重置。
    所以上传一律走 curl 并加 --limit-rate（默认 1500k，约 1.5MB/s，26MB 用 19 秒稳定传完）。
    可用 -UploadRate 调整；-UploadRate 0 表示不限速（只用于小文件或调试）。
  - 上传失败会自动重试（默认 3 次），并在重试前查一次同名资产，避免断线前已落库时重复上传。
  - 上传完成后校验线上资产大小与本地一致；update.json 推送后延迟回读校验 versionCode 与 sha256。
  - 只在用户明确要求发版/上传时运行。

.PARAMETER UploadRate
  curl --limit-rate 的值，默认 "1500k"。实测：不限速 26MB 必被掐断，1500k 稳定成功。

.PARAMETER MaxRetries
  上传尝试次数，默认 3。

.PARAMETER ExtraAssets
  额外要挂到同一个 Release 上的文件（zip 源码包、安装说明 docx 等），每一项写成 "本地路径" 或 "本地路径;资产名"。
  走同一套限速与重试逻辑；同名且大小一致时跳过。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\gitee_publish.ps1 `
    -Tag v1.48.1 -VersionCode 83 -VersionName 1.48.1 `
    -Apk "D:...课格ClassGrid_v1.48.1.apk" -AssetName classgrid-1.48.1.apk `
    -ChangelogFile C:Tempchangelog.txt -TokenFile "D:...令牌.txt"

.EXAMPLE
  # 只打印计划，不写任何东西
  ... -DryRun
#>

param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$AssetName = "",
    [string]$TokenFile = "C:UsersAsus.dshgitee_token.txt",
    [string]$Owner = "moricecc",
    [string]$Repo = "class-grid",
    [string]$Changelog = "",
    [string]$ChangelogFile = "",
    [int]$MinVersionCode = 1,
    [string]$UploadRate = "1500k",
    [int]$MaxRetries = 3,
    [string[]]$ExtraAssets = @(),
    [switch]$Force,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
[System.Net.ServicePointManager]::Expect100Continue = $false

$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$apkName = if ($AssetName -ne "") { $AssetName } else { [System.IO.Path]::GetFileName($apkPath) }
$size = (Get-Item -LiteralPath $apkPath).Length
$sha = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()

if ($ChangelogFile -ne "" -and (Test-Path -LiteralPath $ChangelogFile)) {
    $Changelog = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $ChangelogFile).Path, [System.Text.Encoding]::UTF8).Trim()
}

$base = "https://gitee.com/api/v5/repos/$Owner/$Repo"
$encodedName = [uri]::EscapeDataString($apkName)
$downloadUrl = "https://gitee.com/$Owner/$Repo/releases/download/$Tag/$encodedName"

$manifest = [ordered]@{
    versionCode    = $VersionCode
    versionName    = $VersionName
    minVersionCode = $MinVersionCode
    size           = $size
    sha256         = $sha
    url            = $downloadUrl
    mirrors        = @()
    changelog      = $Changelog
    force          = [bool]$Force
    publishedAt    = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
}
$manifestJson = ($manifest | ConvertTo-Json -Depth 5) + "`n"

Write-Host "== 发版计划 =="
Write-Host "  tag        : $Tag"
Write-Host "  版本       : $VersionName ($VersionCode)"
Write-Host "  资产名     : $apkName"
Write-Host "  大小       : $size"
Write-Host "  SHA-256    : $sha"
Write-Host "  上传限速   : $(if ($UploadRate -and $UploadRate -ne '0') { $UploadRate } else { '不限速' })"
Write-Host "  下载地址   : $downloadUrl"
Write-Host "  清单推送至 : master/update.json"
Write-Host "  附加资产   : $(if ($ExtraAssets.Count -gt 0) { $ExtraAssets -join ', ' } else { '无' })"
Write-Host ""
Write-Host $manifestJson

if ($DryRun) {
    Write-Host "[-DryRun] 未做任何写操作。"
    return
}

if (-not (Test-Path -LiteralPath $TokenFile)) { throw "找不到令牌文件：$TokenFile" }
$token = ([System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenFile).Path)).Trim()
if ([string]::IsNullOrWhiteSpace($token)) { throw "令牌文件是空的：$TokenFile" }
$authHeaders = @{ Authorization = "token $token" }

# ---------------------------------------------------------------------------
# 上传实现
# ---------------------------------------------------------------------------

# 用 curl 限速上传单个资产。curl 在 Windows 上把参数按 ANSI 转换，中文/空格路径会坏，
# 所以先把文件复制到纯 ASCII 的临时路径再传。成功后校验线上大小与本地一致。
function Invoke-GiteeAttachCurl {
    param(
        [Parameter(Mandatory = $true)][int]$ReleaseId,
        [Parameter(Mandatory = $true)][string]$LocalPath,
        [Parameter(Mandatory = $true)][string]$AssetFileName,
        [string]$Rate = "1500k",
        [int]$Retries = 3
    )

    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if (-not $curl) { throw "找不到 curl.exe，无法限速上传（Windows 10+ 自带 curl）" }

    $tmpDir = Join-Path $env:TEMP ("cg_up_" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
    $uploadFile = Join-Path $tmpDir ("payload" + [System.IO.Path]::GetExtension($LocalPath))
    Copy-Item -LiteralPath $LocalPath -Destination $uploadFile -Force
    $expectedSize = (Get-Item -LiteralPath $uploadFile).Length
    $bodyFile = Join-Path $tmpDir "resp.json"
    $errFile = Join-Path $tmpDir "resp.err"
    $attachUri = "$base/releases/$ReleaseId/attach_files?access_token=$token"

    try {
        for ($attempt = 1; $attempt -le $Retries; $attempt++) {
            $rateText = if ($Rate -and $Rate -ne "0") { $Rate } else { "关" }
            Write-Host "  上传尝试 $attempt/$Retries（限速 $rateText）..."

            $curlArgs = @("-sS", "-H", "Expect:", "-m", "900")
            if ($Rate -and $Rate -ne "0") { $curlArgs += @("--limit-rate", $Rate) }
            $curlArgs += @(
                "-X", "POST",
                "-F", "file=@$uploadFile;filename=$AssetFileName",
                $attachUri,
                "-o", $bodyFile
            )

            Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
            & $curl.Source @curlArgs 2> $errFile
            $exit = $LASTEXITCODE

            if ($exit -eq 0 -and (Test-Path -LiteralPath $bodyFile)) {
                $resp = $null
                try { $resp = Get-Content -LiteralPath $bodyFile -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $resp = $null }
                if ($resp -and $resp.id) {
                    if ($resp.size -ne $expectedSize) {
                        throw "上传后大小不一致：本地 $expectedSize / 线上 $($resp.size)"
                    }
                    return $resp
                }
            }

            # 连接可能在服务端已落库后才断开：先查一次同名资产，避免重复上传
            $errText = if (Test-Path -LiteralPath $errFile) { (Get-Content -LiteralPath $errFile -Raw -Encoding UTF8).Trim() } else { "" }
            Write-Host "  尝试 $attempt 失败（curl 退出码 $exit）$errText"
            Start-Sleep -Seconds 3
            try {
                $rel = Invoke-RestMethod -Uri "$base/releases/$ReleaseId" -Headers $authHeaders -Method Get -TimeoutSec 30
                $hit = @($rel.assets | Where-Object { $_.name -eq $AssetFileName })
                if ($hit.Count -gt 0) {
                    $hitSize = Get-GiteeAssetSize $hit[0].browser_download_url
                    if ($null -eq $hitSize -or $hitSize -eq $expectedSize) {
                        Write-Host "  服务端已有同名资产，视为成功：$($hit[0].browser_download_url)"
                        return $hit[0]
                    }
                }
            } catch { }
            if ($attempt -lt $Retries) { Write-Host "  3 秒后重试..." }
        }
        throw "上传失败：$Retries 次尝试均未成功（限速 $Rate）"
    } finally {
        Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# 用 HEAD 读线上资产大小：Gitee 的 Release API 不返回 size，只有上传响应里有。
function Get-GiteeAssetSize {
    param([Parameter(Mandatory = $true)][string]$Url)
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if (-not $curl) { return $null }
    $hdr = Join-Path $env:TEMP ("cg_head_" + [guid]::NewGuid().ToString("N") + ".txt")
    try {
        & $curl.Source @("-sS", "-I", "-L", "-m", "60", "-o", "NUL", "-D", $hdr, $Url) | Out-Null
        if (Test-Path -LiteralPath $hdr) {
            $m = Select-String -LiteralPath $hdr -Pattern "^content-length:\s*(\d+)" | Select-Object -Last 1
            if ($m -and $m.Line -match "(\d+)") { return [int64]$matches[1] }
        }
        return $null
    } catch {
        return $null
    } finally {
        Remove-Item -LiteralPath $hdr -Force -ErrorAction SilentlyContinue
    }
}

# 旧路径：Invoke-RestMethod 一次性大 body。不支持限速，仅作 curl 缺失时的兜底或 -UploadRate 0 时使用。
function Invoke-GiteeAttachLegacy {
    param(
        [Parameter(Mandatory = $true)][int]$ReleaseId,
        [Parameter(Mandatory = $true)][string]$LocalPath,
        [Parameter(Mandatory = $true)][string]$AssetFileName
    )
    $boundary = "----ClassGridBoundary" + [guid]::NewGuid().ToString("N")
    $pre = "--$boundary" + "`r`n" +
           "Content-Disposition: form-data; name=""file""; filename=""$AssetFileName""" + "`r`n" +
           "Content-Type: application/octet-stream" + "`r`n" + "`r`n"
    $post = "`r`n--$boundary--`r`n"

    $ms = New-Object System.IO.MemoryStream
    $pb = [System.Text.Encoding]::UTF8.GetBytes($pre)
    $ms.Write($pb, 0, $pb.Length)
    $fb = [System.IO.File]::ReadAllBytes($LocalPath)
    $ms.Write($fb, 0, $fb.Length)
    $sb = [System.Text.Encoding]::UTF8.GetBytes($post)
    $ms.Write($sb, 0, $sb.Length)
    $bodyBytes = $ms.ToArray()
    $ms.Dispose()

    Write-Host "  [legacy] 不限速上传 $([math]::Round($bodyBytes.Length / 1MB, 1)) MB（大文件很可能被 Gitee 掐断）..."
    $asset = Invoke-RestMethod -Uri "$base/releases/$ReleaseId/attach_files?access_token=$token" `
        -Method Post -ContentType "multipart/form-data; boundary=$boundary" -Body $bodyBytes -TimeoutSec 900
    if (-not $asset -or -not $asset.browser_download_url) { throw "上传失败：Gitee 未返回下载地址" }
    return $asset
}

if ($env:CG_LIB_ONLY -eq "1") { return }

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

# 1. 找 Release；Gitee 在 tag 不存在时返回 200 + 字面量 null，所以要判 id
$release = $null
try {
    $probe = Invoke-RestMethod -Uri "$base/releases/tags/$Tag" -Headers $authHeaders -Method Get -TimeoutSec 30
    if ($probe -and $probe.id) { $release = $probe }
} catch {
    $release = $null
}

if (-not $release) {
    $body = @{
        tag_name         = $Tag
        name             = "课格 $VersionName"
        body             = $Changelog
        target_commitish = "master"
    } | ConvertTo-Json
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $release = Invoke-RestMethod -Uri "$base/releases" -Headers $authHeaders -Method Post `
        -Body $bodyBytes -ContentType "application/json; charset=utf-8" -TimeoutSec 30
    Write-Host "已创建 Release：$Tag (id=$($release.id))"
} else {
    Write-Host "复用已有 Release：$Tag (id=$($release.id))"
}

# 2. 上传 APK（同名资产已存在就跳过，并核对大小）
$existing = @($release.assets | Where-Object { $_.name -eq $apkName })
if ($existing.Count -gt 0) {
    $remoteSize = Get-GiteeAssetSize $existing[0].browser_download_url
    if ($null -eq $remoteSize) {
        Write-Host "  [警告] 读不到线上 $apkName 的大小，仅按名称跳过（要确认请手动下载核对）"
    } elseif ($remoteSize -ne $size) {
        throw "同名资产 $apkName 已存在但大小不一致（线上 $remoteSize / 本地 $size），请先到网页删掉再发版"
    }
    Write-Host "资产 $apkName 已存在（$remoteSize 字节），跳过上传：$($existing[0].browser_download_url)"
    $downloadUrl = $existing[0].browser_download_url
} else {
    Write-Host "上传 $apkName（$([math]::Round($size / 1MB, 1)) MB）..."
    $hasCurl = [bool](Get-Command curl.exe -ErrorAction SilentlyContinue)
    if (-not $hasCurl) { Write-Host "  [警告] 找不到 curl.exe，退回不限速上传，大文件可能失败" }

    if ($hasCurl) {
        $asset = Invoke-GiteeAttachCurl -ReleaseId $release.id -LocalPath $apkPath -AssetFileName $apkName -Rate $UploadRate -Retries $MaxRetries
    } else {
        $asset = Invoke-GiteeAttachLegacy -ReleaseId $release.id -LocalPath $apkPath -AssetFileName $apkName
    }
    $downloadUrl = $asset.browser_download_url
    Write-Host "上传成功：id=$($asset.id) $downloadUrl（$($asset.size) 字节）"
}

# 2.5 附加资产（zip / 安装说明 docx 等）：同一套限速上传
if ($ExtraAssets.Count -gt 0) {
    $relFresh = Invoke-RestMethod -Uri "$base/releases/$($release.id)" -Headers $authHeaders -Method Get -TimeoutSec 30
    foreach ($item in $ExtraAssets) {
        $bits = $item.Split(";")
        $exPath = (Resolve-Path -LiteralPath $bits[0]).Path
        $exName = if ($bits.Count -gt 1 -and $bits[1] -ne "") { $bits[1] } else { [System.IO.Path]::GetFileName($exPath) }
        $exSize = (Get-Item -LiteralPath $exPath).Length
        $hit = @($relFresh.assets | Where-Object { $_.name -eq $exName })
        if ($hit.Count -gt 0) {
            $exRemote = Get-GiteeAssetSize $hit[0].browser_download_url
            if ($null -ne $exRemote -and $exRemote -ne $exSize) { throw "附加资产 $exName 已存在但大小不一致（线上 $exRemote / 本地 $exSize），请先到网页删掉" }
            Write-Host "附加资产 $exName 已存在，跳过上传"
            continue
        }
        Write-Host "上传附加资产 $exName（$([math]::Round($exSize / 1MB, 2)) MB）..."
        $exAsset = Invoke-GiteeAttachCurl -ReleaseId $release.id -LocalPath $exPath -AssetFileName $exName -Rate $UploadRate -Retries $MaxRetries
        Write-Host "  附加资产上传成功：id=$($exAsset.id) $($exAsset.browser_download_url)"
    }
}

# 3. 推送 update.json（Contents API：有 sha 就更新，没有就新建）
$manifest.url = $downloadUrl
$manifestJson = ($manifest | ConvertTo-Json -Depth 5) + "`n"
$contentB64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($manifestJson))
$contentsUri = "$base/contents/update.json"
$contentsRefUri = $contentsUri + "?ref=master"

$shaExisting = $null
try {
    $cur = Invoke-RestMethod -Uri $contentsRefUri -Headers $authHeaders -Method Get -TimeoutSec 30
    if ($cur -and $cur.sha) { $shaExisting = $cur.sha }
} catch {
    $shaExisting = $null
}

$putBody = @{
    access_token = $token
    content      = $contentB64
    message      = "更新 update.json 到 $VersionName ($VersionCode)"
    branch       = "master"
}
if ($shaExisting) { $putBody["sha"] = $shaExisting }

$putBytes = [System.Text.Encoding]::UTF8.GetBytes(($putBody | ConvertTo-Json))
$putMethod = if ($shaExisting) { "Put" } else { "Post" }
Invoke-RestMethod -Uri $contentsUri -Headers $authHeaders -Method $putMethod `
    -Body $putBytes -ContentType "application/json; charset=utf-8" -TimeoutSec 60 | Out-Null
Write-Host "update.json 已推送：https://gitee.com/$Owner/$Repo/raw/master/update.json"

# 4. 回读校验
Start-Sleep 2
$check = Invoke-RestMethod -Uri "https://gitee.com/$Owner/$Repo/raw/master/update.json?t=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" -TimeoutSec 30
if ($check.versionCode -ne $VersionCode -or $check.sha256 -ne $sha) {
    throw "回读校验失败：线上清单与本次不一致（versionCode=$($check.versionCode), sha256=$($check.sha256)）"
}
Write-Host "回读校验通过：versionCode=$($check.versionCode)，sha256 一致。"
Write-Host "完成。"
