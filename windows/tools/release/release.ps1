# 打包 Windows 端发布资产：编译 → 取出并重命名 MSIX → （可选）签名 → 生成 .appinstaller → 导出 .cer。
# 资产命名遵循 docs/RELEASES.md：ncrust-windows-x86_64-<版本>.<ext>。
#
# 私钥 (.pfx) 不入库；口令从参数或环境变量传入。签名需要 Windows SDK 的 signtool。
#
# 用法：
#   powershell -File windows/tools/release/release.ps1 -Version 1.0.1 `
#       -BaseUrl https://github.com/GuitaristRin/Ncrust/releases/download/CrateStack-YYYYMMDDA `
#       -PfxPath C:\keys\ncrust.pfx -PfxPassword (Read-Host -AsSecureString)
#   # 本机冒烟（不签名）：
#   powershell -File windows/tools/release/release.ps1 -Version 1.0.1 -BaseUrl https://example.invalid -SkipSign
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$PfxPath,
    [System.Security.SecureString]$PfxPassword,
    [string]$Platform = 'x64',
    [string]$Arch = 'x86_64',
    [string]$OutDir,
    [switch]$SkipBuild,
    [switch]$SkipSign
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
if (-not $OutDir) { $OutDir = Join-Path $root 'windows\dist' }
if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$fullVersion = "$Version.0"
$assetPrefix = "ncrust-windows-$Arch"
$msbuild = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe'

if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $msbuild)) { throw "找不到 MSBuild：$msbuild" }
    & $msbuild (Join-Path $root 'windows\Ncrust.Windows.sln') /t:Restore /p:Configuration=Release /p:Platform=$Platform /v:m /nologo
    if ($LASTEXITCODE -ne 0) { throw 'Restore 失败' }
    & $msbuild (Join-Path $root 'windows\Ncrust.Windows.sln') /p:Configuration=Release /p:Platform=$Platform /v:m /nologo
    if ($LASTEXITCODE -ne 0) { throw '生成失败' }
}

$appPkg = Join-Path $root "windows\src\Ncrust.App\AppPackages\Ncrust.App_${fullVersion}_${Platform}_Test"
$msixSource = Join-Path $appPkg "Ncrust.App_${fullVersion}_${Platform}.msix"
if (-not (Test-Path -LiteralPath $msixSource)) { throw "找不到 MSIX：$msixSource" }

$msixOut = Join-Path $OutDir "$assetPrefix-$Version.msix"
Copy-Item -LiteralPath $msixSource -Destination $msixOut -Force
Write-Output "MSIX  -> $msixOut"

if (-not $SkipSign) {
    if (-not $PfxPath -or -not $PfxPassword) { throw '签名需要 -PfxPath 与 -PfxPassword；只想本机测试请加 -SkipSign' }
    $signtool = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if (-not $signtool) { throw '找不到 signtool.exe（安装 Windows SDK 或加入 PATH）' }

    $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($PfxPassword))
    & $signtool.Source sign /fd SHA256 /f $PfxPath /p $plain $msixOut
    if ($LASTEXITCODE -ne 0) { throw '签名失败' }
    & $signtool.Source verify /pa $msixOut

    # 注意：Windows PowerShell 5.1 的 Get-PfxCertificate 没有 -Password 参数（PS 7 才有），
    # 用 X509Certificate2 构造器取同一张证书，5.1 / 7 都能跑。
    $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($PfxPath, $plain)
    $cerOut = Join-Path $OutDir "$assetPrefix-$Version.cer"
    Export-Certificate -Cert $cert -FilePath $cerOut -Type CERT | Out-Null
    Write-Output "证书 -> $cerOut"
}

$appinstaller = & (Join-Path $PSScriptRoot 'make-appinstaller.ps1') -Version $Version -BaseUrl $BaseUrl -Arch $Platform -OutDir $OutDir
Write-Output "更新入口 -> $appinstaller"
Write-Output ''
$assets = @($msixOut, $appinstaller)
if (-not $SkipSign) { $assets += (Join-Path $OutDir "$assetPrefix-$Version.cer") }
Write-Output '发布（先 draft，负责人冒烟后手动发布）：'
Write-Output ("  gh release create <CrateStack-YYYYMMDDA> --draft --latest=false " + (($assets | ForEach-Object { '"' + $_ + '"' }) -join ' '))
