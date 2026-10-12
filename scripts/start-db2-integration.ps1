param([int]$Port = 15000)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-db2-it'
if (-not $env:SCHEMALOOM_IT_DB2_PASSWORD) { throw 'Please set SCHEMALOOM_IT_DB2_PASSWORD' }
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot query Podman containers' }
if ($names | Where-Object { $_ -eq $container }) { throw "Container $container already exists. Run stop-db2-integration.ps1 first" }
$previousPassword = $env:DB2INST1_PASSWORD
$previousLicense = $env:LICENSE
$previousDbName = $env:DBNAME
try {
    $env:DB2INST1_PASSWORD = $env:SCHEMALOOM_IT_DB2_PASSWORD
    $env:LICENSE = 'accept'
    $env:DBNAME = if ($env:SCHEMALOOM_IT_DB2_DATABASE) { $env:SCHEMALOOM_IT_DB2_DATABASE } else { 'BLUDB' }
    $image = if ($env:SCHEMALOOM_IT_DB2_IMAGE) { $env:SCHEMALOOM_IT_DB2_IMAGE } else { 'icr.io/db2_community/db2:latest' }
    podman run -d --privileged --security-opt label=disable --shm-size=2g --name $container -p "127.0.0.1:${Port}:50000" -e DB2INST1_PASSWORD -e LICENSE -e DBNAME $image | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'DB2 container failed to start' }
} finally {
    $env:DB2INST1_PASSWORD = $previousPassword
    $env:LICENSE = $previousLicense
    $env:DBNAME = $previousDbName
}
for ($i = 0; $i -lt 360; $i += 10) {
    try {
        $result = podman exec $container su - db2inst1 -c "db2 connect to $env:DBNAME && db2 'VALUES 1'" 2>&1
        if ($LASTEXITCODE -eq 0) { Write-Output "DB2 ready on port $Port"; return }
    } catch { }
    Start-Sleep -Seconds 10
}
throw 'DB2 container did not become ready in time'
