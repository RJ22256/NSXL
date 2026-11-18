# Registers (or removes) NSXL as an Xbox mode home app. Run elevated by NSXL (Settings > System).
#   setup.ps1 -Install   |   setup.ps1 -Remove
# Windows only allows the "gaming home" capability on a package installed while Developer Mode is on,
# and the package is signed with NSXL's own certificate, which has to be trusted first. Both are
# switched back afterwards, the same way other community home apps (e.g. AnyFSE) do it.
param([switch]$Install, [switch]$Remove)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $env:TEMP 'nsxl-xbox-mode.log'
Start-Transcript -Path $log -Force | Out-Null
try {
  if ($Remove) {
    Get-AppxPackage -Name 'NSXL.HomeApp' | Remove-AppxPackage
    Write-Output 'REMOVED'
    exit 0
  }
  $key = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock'
  if (-not (Test-Path $key)) { New-Item -Path $key -Force | Out-Null }
  $before = (Get-ItemProperty -Path $key -Name AllowDevelopmentWithoutDevLicense -ErrorAction SilentlyContinue).AllowDevelopmentWithoutDevLicense
  Set-ItemProperty -Path $key -Name AllowDevelopmentWithoutDevLicense -Value 1 -Type DWord
  $cert = Import-Certificate -FilePath (Join-Path $here 'NSXL.cer') -CertStoreLocation Cert:\LocalMachine\TrustedPeople
  try {
    Get-AppxPackage -Name 'NSXL.HomeApp' | Remove-AppxPackage -ErrorAction SilentlyContinue
    Add-AppxPackage -Path (Join-Path $here 'NSXL.HomeApp.msix') -ForceApplicationShutdown
  } finally {
    Remove-Item -Path ("Cert:\LocalMachine\TrustedPeople\" + $cert.Thumbprint) -ErrorAction SilentlyContinue
    if ($before -ne 1) { Set-ItemProperty -Path $key -Name AllowDevelopmentWithoutDevLicense -Value ([int]$before) -Type DWord }
  }
  Write-Output 'INSTALLED'
  exit 0
} catch {
  Write-Output ('FAILED ' + $_.Exception.Message)
  exit 1
} finally {
  Stop-Transcript | Out-Null
}
