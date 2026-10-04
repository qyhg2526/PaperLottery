#!/usr/bin/env pwsh
# =============================================================================
#  从 README.md 的「更新日志」章节里提取指定版本的说明，用于生成 GitHub Release。
#
#  用法：pwsh -File scripts/extract-changelog.ps1 -Version 1.0.1 -Output notes.md
# =============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Readme,
    [string]$Output,
    [int]$MaxLines = 200
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Readme) { $Readme = Join-Path $root 'README.md' }
if (-not (Test-Path $Readme)) { throw "找不到 README：$Readme" }

$lines = Get-Content -LiteralPath $Readme -Encoding UTF8

# 定位形如 "### 1.0.1 ..." 的标题（允许标题后跟任意描述文字）
$startIndex = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match "^###\s+$([regex]::Escape($Version))(\s|$)") {
        $startIndex = $i + 1
        break
    }
}

if ($startIndex -lt 0) {
    Write-Warning "README 中未找到版本 $Version 的更新日志，使用占位说明。"
    $notes = "版本 $Version 的发布说明请参见 [README](README.md)。"
} else {
    $body = New-Object System.Collections.Generic.List[string]
    for ($i = $startIndex; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # 遇到同级或更高级标题，或分隔线，即结束当前版本段落
        if ($line -match '^#{1,3}\s' -or $line -match '^---\s*$') { break }
        if ($body.Count -ge $MaxLines) { break }
        $body.Add($line)
    }
    $notes = ($body -join "`n").Trim()
    if ([string]::IsNullOrWhiteSpace($notes)) {
        $notes = "版本 $Version 的发布说明请参见 [README](README.md)。"
    }
}

# 附加通用的安装说明
$jarName = "PaperLottery-$Version.jar"
$footer = @(
    '',
    '---',
    '',
    '### 安装',
    '',
    "1. 下载下方的 ``$jarName``",
    '2. 放入服务端 `plugins/` 目录（需要 **Paper 26.2** 与 **Java 25**）',
    '3. 重启服务器，插件会生成 `config.yml` 与 `rewards.yml`',
    '4. 编辑 `rewards.yml` 配置奖励，执行 `/lottery reload` 生效',
    '',
    '详细说明见 [README](https://github.com/qyhg2526/PaperLottery#readme)。'
) -join "`n"

$notes = $notes + "`n" + $footer

if ($Output) {
    [System.IO.File]::WriteAllText($Output, $notes, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "已写入 $Output（$($notes.Split("`n").Count) 行）"
} else {
    Write-Output $notes
}
