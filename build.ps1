$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $root "build\classes"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $classes, $dist | Out-Null
Remove-Item -Recurse -Force $classes
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java
& javac --release 17 -encoding UTF-8 -d $classes $sources.FullName
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$manifest = Join-Path $root "build\MANIFEST.MF"
Set-Content -Encoding ascii $manifest "Manifest-Version: 1.0`nMain-Class: com.vcpudashboard.VcpuDashboardApp`n"
Push-Location $classes
& jar cfm (Join-Path $dist "vcpu-dashboard.jar") $manifest .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built dist\vcpu-dashboard.jar"
