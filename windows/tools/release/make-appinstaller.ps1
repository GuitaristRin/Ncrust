# 生成 Windows 端的 .appinstaller（App Installer 的更新入口）。
#
# .appinstaller 是 Windows 端更新的唯一正解：只有经它安装的包，系统的 App Installer
# 才会在启动时按 <UpdateSettings> 自动更新；直接装 .msix 没有更新源，永远不更新。
# 详见 windows/AGENTS.md「Windows 产物与更新」与 docs/RELEASES.md。
#
# 用法（在发布出来的资产名生成之前，先按 ncrust-[平台]-[架构]-[版本] 规范重命名）：
#   powershell -File windows/tools/release/make-appinstaller.ps1 `
#       -Version 1.0.1 -BaseUrl https://github.com/GuitaristRin/Ncrust/releases/download/CrateStack-YYYYMMDDA
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$Name = 'TakahashiRinta.Ncrust',
    [string]$Publisher = 'CN=TakahashiRinta',
    [string]$Arch = 'x64',
    [string]$AssetPrefix = 'ncrust-windows-x86_64',
    [string]$OutDir = $PSScriptRoot,
    # 0 = 每次启动都检查；设为更大的小时数可降低检查频率。
    [int]$HoursBetweenUpdateChecks = 0
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$fullVersion = "$Version.0"
$msixName = "$AssetPrefix-$Version.msix"
$selfName = "$AssetPrefix-$Version.appinstaller"

$xml = @"
<?xml version="1.0" encoding="utf-8"?>
<AppInstaller xmlns="http://schemas.microsoft.com/appx/appinstaller/2018"
              Version="$fullVersion"
              Uri="$BaseUrl/$selfName">
  <MainPackage Name="$Name"
               Version="$fullVersion"
               Publisher="$Publisher"
               ProcessorArchitecture="$Arch"
               Uri="$BaseUrl/$msixName" />
  <UpdateSettings>
    <OnLaunch HoursBetweenUpdateChecks="$HoursBetweenUpdateChecks" />
    <AutomaticBackgroundTask />
  </UpdateSettings>
</AppInstaller>
"@

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir | Out-Null
}

$path = Join-Path $OutDir $selfName
[System.IO.File]::WriteAllText($path, $xml, (New-Object System.Text.UTF8Encoding($false)))
Write-Output $path
