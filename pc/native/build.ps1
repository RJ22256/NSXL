# Builds NSXL's Windows parts into pc/resources (CI runs this before electron-builder):
#   resources/native/  nsxl-native.exe (Bluetooth, volume, brightness, media, Xbox buttons)
#   resources/fse/     NSXL.HomeApp.msix + NSXL.cer + setup.ps1 (Xbox mode home app)
param([string]$Version = '1.0.0')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$res = Join-Path (Split-Path -Parent $root) 'resources'
Remove-Item $res -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$res\native", "$res\fse" | Out-Null

# 1. the helper and the home stub
dotnet build "$root\Helper\Helper.csproj" -c Release -o "$res\native" --nologo
if ($LASTEXITCODE) { throw 'helper build failed' }
$stage = Join-Path $env:TEMP 'nsxl-fse'
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
dotnet build "$root\Home\Home.csproj" -c Release -o "$stage\bin" --nologo
if ($LASTEXITCODE) { throw 'home stub build failed' }

# 2. the package folder
$pkg = Join-Path $stage 'pkg'
New-Item -ItemType Directory -Force -Path "$pkg\Assets" | Out-Null
Copy-Item "$stage\bin\NSXLHome.exe" $pkg
Copy-Item "$root\fse\CustomCapability.SCCD" $pkg
$v = ($Version -split '[^0-9]+' | Where-Object { $_ -ne '' } | Select-Object -First 3) + @('0', '0', '0')
$pv = ($v[0..2] -join '.') + '.0'
(Get-Content "$root\fse\AppxManifest.xml" -Raw).Replace('__VERSION__', $pv) | Set-Content "$pkg\AppxManifest.xml" -Encoding UTF8
Add-Type -AssemblyName System.Drawing
$icon = [System.Drawing.Image]::FromFile((Join-Path (Split-Path -Parent $root) 'build\icon.png'))
foreach ($a in @(@('StoreLogo.png', 50), @('Square44x44Logo.png', 44), @('Square150x150Logo.png', 150))) {
  $bmp = New-Object System.Drawing.Bitmap $a[1], $a[1]
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = 'HighQualityBicubic'
  $g.DrawImage($icon, 0, 0, $a[1], $a[1])
  $bmp.Save("$pkg\Assets\$($a[0])", [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
}
$icon.Dispose()

# 3. pack and sign it with a certificate made for this build (users trust it only while installing)
$kits = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin' -Directory | Where-Object { $_.Name -match '^10\.' } | Sort-Object Name -Descending
$makeappx = ($kits | ForEach-Object { Join-Path $_.FullName 'x64\makeappx.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1)
$signtool = ($kits | ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1)
& $makeappx pack /o /d $pkg /p "$res\fse\NSXL.HomeApp.msix" /nv
if ($LASTEXITCODE) { throw 'makeappx failed' }
$cert = New-SelfSignedCertificate -Type Custom -Subject 'CN=NSXL' -KeyUsage DigitalSignature -FriendlyName 'NSXL home app' `
  -CertStoreLocation 'Cert:\CurrentUser\My' -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}') -NotAfter (Get-Date).AddYears(10)
$pw = ConvertTo-SecureString -String 'nsxl' -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath "$stage\nsxl.pfx" -Password $pw | Out-Null
Export-Certificate -Cert $cert -FilePath "$res\fse\NSXL.cer" | Out-Null
& $signtool sign /fd SHA256 /f "$stage\nsxl.pfx" /p nsxl "$res\fse\NSXL.HomeApp.msix"
if ($LASTEXITCODE) { throw 'signtool failed' }
Copy-Item "$root\fse\setup.ps1" "$res\fse"
Get-ChildItem $res -Recurse | Select-Object FullName, Length | Format-Table -AutoSize
