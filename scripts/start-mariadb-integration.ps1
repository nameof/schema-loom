param([int]$Port = 13307)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-mariadb-it'
if (-not $env:SCHEMALOOM_IT_MARIADB_PASSWORD) { throw '请设置 SCHEMALOOM_IT_MARIADB_PASSWORD' }
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw '无法查询 Podman 容器' }
if ($names | Where-Object { $_ -eq $container }) { throw "测试容器 $container 已存在，请先执行 stop-mariadb-integration.ps1" }
$previousPassword = $env:MARIADB_PASSWORD
$previousRootPassword = $env:MARIADB_ROOT_PASSWORD
try {
    $env:MARIADB_PASSWORD = $env:SCHEMALOOM_IT_MARIADB_PASSWORD
    $env:MARIADB_ROOT_PASSWORD = $env:SCHEMALOOM_IT_MARIADB_PASSWORD
    podman run -d --name $container -p "127.0.0.1:${Port}:3306" -e MARIADB_DATABASE=schemaloom_it -e MARIADB_USER=schemaloom -e MARIADB_PASSWORD -e MARIADB_ROOT_PASSWORD docker.io/library/mariadb:11.4 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'MariaDB 容器启动失败' }
} finally {
    $env:MARIADB_PASSWORD = $previousPassword
    $env:MARIADB_ROOT_PASSWORD = $previousRootPassword
}
for ($i = 0; $i -lt 90; $i++) {
    podman exec $container healthcheck.sh --connect --innodb_initialized 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Output "MariaDB 已就绪，端口 $Port"; return }
    Start-Sleep -Seconds 1
}
throw 'MariaDB 容器未在等待时间内就绪'
