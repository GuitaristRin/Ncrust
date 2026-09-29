# 把 Ncrust 的公开证书 (.cer) 导入「受信任人」，否则 Windows 会拒绝安装自签的 MSIX。
# 只需做一次；日后换成正规代码签名证书，这一步就不需要了。
#
# 用法：
#   powershell -File windows/tools/release/install-cert.ps1 -CerPath .\ncrust-windows-x86_64-1.0.1.cer
#   # 想装到本机、供所有用户使用（需要管理员 PowerShell）：
#   powershell -File windows/tools/release/install-cert.ps1 -CerPath .\....cer -Scope LocalMachine
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CerPath,
    [ValidateSet('CurrentUser', 'LocalMachine')][string]$Scope = 'CurrentUser'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $CerPath)) {
    throw "找不到证书：$CerPath"
}

$store = if ($Scope -eq 'LocalMachine') {
    'Cert:\LocalMachine\TrustedPeople'
} else {
    'Cert:\CurrentUser\TrustedPeople'
}

$imported = Import-Certificate -FilePath $CerPath -CertStoreLocation $store
Write-Output ("已导入 {0} 到 {1}" -f ($imported | Select-Object -First 1).Subject, $store)
Write-Output "现在可以用 .appinstaller 安装 / 更新 Ncrust。"
