param([int]$Port = 14321)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-kingbase-it'
if (-not $env:SCHEMALOOM_IT_KINGBASE_PASSWORD) { throw 'Please set SCHEMALOOM_IT_KINGBASE_PASSWORD' }
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot query Podman containers' }
if ($names | Where-Object { $_ -eq $container }) { throw "Container $container already exists. Run stop-kingbase-integration.ps1 first" }
$image = if ($env:SCHEMALOOM_IT_KINGBASE_IMAGE) { $env:SCHEMALOOM_IT_KINGBASE_IMAGE } else { 'localhost/kingbase:V009R001C010' }
podman run -d --name $container -p "127.0.0.1:${Port}:54321" -e "PASSWORD=$env:SCHEMALOOM_IT_KINGBASE_PASSWORD" $image | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'KingbaseES container failed to start' }
for ($i = 0; $i -lt 180; $i++) {
    try {
        $check = podman exec $container /bin/sh -c "echo 'SELECT 1;' | /home/kingbase/install/kingbase/bin/ksql -U system -d kingbase -p 54321 -t -A" 2>&1
        if ($LASTEXITCODE -eq 0 -and $check -match '1') {
            Write-Output "KingbaseES is ready on port $Port"
            return
        }
    } catch { }
    Start-Sleep -Seconds 1
}
throw 'KingbaseES container did not become ready in time'
