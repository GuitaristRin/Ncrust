# 把 Ncrust 的公开证书 (.cer) 导入「受信任人」，否则 Windows 会拒绝安装自签的 MSIX。
# 只需做一次；日后换成正规代码签名证书，这一步就不需要了。
#
# 默认装到「本地计算机」（需要管理员 PowerShell）：MSIX 的签名校验由 SYSTEM 上下文执行、
# 查的是机器存储，证书只装在「当前用户」里照样 0x800B0109 拒装（2026-10-21 本机事件日志
# Microsoft-Windows-AppxPackaging/Operational #154 实测）。CurrentUser 仅供无管理员权限时
# 试装，不保证成功。
#
# 用法（管理员 PowerShell）：
#   powershell -File windows/tools/release/install-cert.ps1 -CerPath .\ncrust-windows-x86_64-1.0.3.cer
#   # 无管理员权限时的降级选项（可能仍被拒装）：
#   powershell -File windows/tools/release/install-cert.ps1 -CerPath .\....cer -Scope CurrentUser
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CerPath,
    [ValidateSet('CurrentUser', 'LocalMachine')][string]$Scope = 'LocalMachine'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $CerPath)) {
    throw "找不到证书：$CerPath"
}

if ($Scope -eq 'LocalMachine' -and -not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "LocalMachine 作用域需要管理员 PowerShell（MSIX 签名校验在 SYSTEM 上下文查机器存储）。请以管理员身份重开，或明确指定 -Scope CurrentUser（可能仍被拒装）。"
}

$store = if ($Scope -eq 'LocalMachine') {
    'Cert:\LocalMachine\TrustedPeople'
} else {
    'Cert:\CurrentUser\TrustedPeople'
}

$imported = Import-Certificate -FilePath $CerPath -CertStoreLocation $store
Write-Output ("已导入 {0} 到 {1}" -f ($imported | Select-Object -First 1).Subject, $store)
Write-Output "现在可以用 .appinstaller 安装 / 更新 Ncrust。"
