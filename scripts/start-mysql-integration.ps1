param([int]$Port = 13306)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-mysql-it'
if (-not $env:SCHEMALOOM_IT_MYSQL_PASSWORD) { throw '请设置 SCHEMALOOM_IT_MYSQL_PASSWORD' }
if (podman ps -a --format '{{.Names}}' | Where-Object { $_ -eq $container }) {
    throw "测试容器 $container 已存在，请先执行 stop-mysql-integration.ps1"
}
$env:MYSQL_PASSWORD = $env:SCHEMALOOM_IT_MYSQL_PASSWORD
$env:MYSQL_ROOT_PASSWORD = $env:SCHEMALOOM_IT_MYSQL_PASSWORD
podman run -d --name $container -p "127.0.0.1:${Port}:3306" -e MYSQL_DATABASE=schemaloom_it -e MYSQL_USER=schemaloom -e MYSQL_PASSWORD -e MYSQL_ROOT_PASSWORD mysql:8.0.36 | Out-Host
for ($i = 0; $i -lt 90; $i++) {
    $ready = podman exec $container mysqladmin ping --silent 2>&1
    if ($LASTEXITCODE -eq 0 -and $ready -match 'mysqld is alive') { Write-Output "MySQL is ready on port $Port"; exit 0 }
    Start-Sleep -Seconds 1
}
throw 'MySQL container did not become ready'
