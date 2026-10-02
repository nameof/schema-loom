param([int]$Port = 15432)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-postgresql-it'
if (-not $env:SCHEMALOOM_IT_POSTGRESQL_PASSWORD) { throw '请设置 SCHEMALOOM_IT_POSTGRESQL_PASSWORD' }
if (podman ps -a --format '{{.Names}}' | Where-Object { $_ -eq $container }) {
    throw "测试容器 $container 已存在，请先执行 stop-postgresql-integration.ps1"
}
$env:POSTGRES_PASSWORD = $env:SCHEMALOOM_IT_POSTGRESQL_PASSWORD
podman run -d --name $container -p "127.0.0.1:${Port}:5432" -e POSTGRES_DB=schemaloom_it -e POSTGRES_USER=schemaloom -e POSTGRES_PASSWORD postgres:16-alpine | Out-Host
for ($i = 0; $i -lt 60; $i++) {
    $ready = podman exec $container pg_isready -U schemaloom -d schemaloom_it 2>$null
    if ($LASTEXITCODE -eq 0) { Write-Output "PostgreSQL is ready on port $Port"; exit 0 }
    Start-Sleep -Seconds 1
}
throw 'PostgreSQL container did not become ready'
