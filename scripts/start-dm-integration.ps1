param([int]$Port = 15236)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-dm-it'
if (-not $env:SCHEMALOOM_IT_DM_PASSWORD) { throw 'Please set SCHEMALOOM_IT_DM_PASSWORD' }
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot query Podman containers' }
if ($names | Where-Object { $_ -eq $container }) { throw "Container $container already exists. Run stop-dm-integration.ps1 first" }
$image = if ($env:SCHEMALOOM_IT_DM_IMAGE) { $env:SCHEMALOOM_IT_DM_IMAGE } else { 'docker.io/xuxuclassmate/dameng:20251203' }
podman run -d --name $container -p "127.0.0.1:${Port}:5236" $image | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'DM container failed to start' }
for ($i = 0; $i -lt 180; $i++) {
    try {
        $check = podman exec $container bash -c "echo 'SELECT 1;' | /opt/dmdbms/bin/disql SYSDBA/$env:SCHEMALOOM_IT_DM_PASSWORD" 2>&1
        if ($LASTEXITCODE -eq 0) {
            $driverPath = Join-Path $PSScriptRoot "..\drivers\DmJdbcDriver8.jar"
            if (-not (Test-Path $driverPath)) {
                podman cp "${container}:/opt/dmdbms/drivers/jdbc/DmJdbcDriver8.jar" $driverPath 2>&1 | Out-Null
                if ($LASTEXITCODE -ne 0) { Write-Warning "Failed to extract JDBC driver" }
                else { Write-Output "JDBC driver extracted to drivers/" }
            }
            Write-Output "DM is ready on port $Port"
            return
        }
    } catch { }
    Start-Sleep -Seconds 1
}
throw 'DM container did not become ready in time'
