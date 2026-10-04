# =============================================================================
#  PaperLottery 一键构建脚本（Windows / PowerShell）
#  ---------------------------------------------------------------------------
#  为什么不用 Gradle 的 javac？
#    Paper 26.2 的 paper-api jar 中，部分方法（如 Material#getItemAttributes）
#    同时带有 RuntimeInvisibleAnnotations 与 RuntimeInvisibleTypeAnnotations
#    两份相同的 @NotNull，javac 会因此报
#    “无法将类型批注 @NotNull 附加到 ...”。
#    Eclipse 编译器（ECJ）可以正常处理该 jar，因此这里直接使用 ECJ 编译，
#    无需联网、无需 Gradle 守护进程。
#
#  用法：
#      pwsh -File build.ps1
#  产物：
#      build/libs/PaperLottery-<版本>.jar
# =============================================================================

[CmdletBinding()]
param(
    [string]$Configuration = 'release'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$lib = Join-Path $root 'lib'
$srcDir = Join-Path $root 'src\main\java'
$resDir = Join-Path $root 'src\main\resources'
$buildDir = Join-Path $root 'build'
$classesDir = Join-Path $buildDir 'classes'
$libsDir = Join-Path $buildDir 'libs'
$version = '1.0.1'

# ---------------------------------------------------------------- 前置检查
if (-not (Test-Path $lib)) { throw "缺少依赖目录：$lib" }
if (-not (Test-Path $srcDir)) { throw "缺少源码目录：$srcDir" }

$ecj = Get-ChildItem $lib -Filter 'ecj-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $ecj) { throw "未找到 ECJ 编译器（lib\ecj-*.jar）" }

$java = (Get-Command java -ErrorAction SilentlyContinue)
if (-not $java) { throw '未找到 java，请先安装 JDK 25 并加入 PATH。' }

# ---------------------------------------------------------------- 清理
foreach ($dir in @($classesDir, $libsDir)) {
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
}
New-Item -ItemType Directory -Force -Path $classesDir, $libsDir | Out-Null

# ---------------------------------------------------------------- 编译
$classpath = (Get-ChildItem $lib -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'ecj-*' } |
    ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem $srcDir -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

Write-Host "[1/4] 使用 ECJ 编译 $($sources.Count) 个源文件 ..." -ForegroundColor Cyan
$compileLog = & java -jar $ecj.FullName `
    -source 25 -target 25 `
    -encoding UTF-8 `
    -warn:none -proc:none `
    -cp $classpath `
    -d $classesDir `
    $sources 2>&1
if ($LASTEXITCODE -ne 0) {
    $compileLog | Select-Object -First 80 | ForEach-Object { Write-Host $_ }
    throw "编译失败（退出码 $LASTEXITCODE）"
}

# ---------------------------------------------------------------- 资源
Write-Host '[2/4] 复制资源文件 ...' -ForegroundColor Cyan
Get-ChildItem $resDir -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($resDir.Length).TrimStart('\')
    $target = Join-Path $classesDir $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    if ($_.Extension -in @('.yml', '.yaml', '.txt')) {
        # 替换 ${version} 之类的占位符
        $content = Get-Content -Raw -Encoding UTF8 $_.FullName
        $content = $content.Replace('${version}', $version)
        [System.IO.File]::WriteAllText($target, $content, (New-Object System.Text.UTF8Encoding($false)))
    } else {
        Copy-Item $_.FullName $target -Force
    }
}

# ---------------------------------------------------------------- 清单
Write-Host '[3/4] 生成 MANIFEST.MF ...' -ForegroundColor Cyan
$manifest = Join-Path $buildDir 'MANIFEST.MF'
$manifestText = @(
    'Manifest-Version: 1.0',
    'Implementation-Title: PaperLottery',
    "Implementation-Version: $version",
    'Built-For: Paper 26.2',
    'Created-By: ECJ (Eclipse Compiler for Java)',
    'Enable-Native-Access: ALL-UNNAMED',
    '',
    ''
) -join "`r`n"
[System.IO.File]::WriteAllText($manifest, $manifestText, (New-Object System.Text.UTF8Encoding($false)))

# ---------------------------------------------------------------- 打包
Write-Host '[4/4] 打包 jar ...' -ForegroundColor Cyan
$jarFile = Join-Path $libsDir "PaperLottery-$version.jar"
Push-Location $classesDir
try {
    & jar --create --file $jarFile --manifest $manifest .
    if ($LASTEXITCODE -ne 0) { throw "打包失败（退出码 $LASTEXITCODE）" }
} finally {
    Pop-Location
}

$size = (Get-Item $jarFile).Length
Write-Host ''
Write-Host "构建成功：$jarFile ($([math]::Round($size / 1KB, 1)) KB)" -ForegroundColor Green
Write-Host "目标服务端：Paper 26.2（Java 25）" -ForegroundColor Green
