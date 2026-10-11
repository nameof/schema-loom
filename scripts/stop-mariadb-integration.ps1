$ErrorActionPreference = 'Stop'
$container = 'schema-loom-mariadb-it'
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw '无法查询 Podman 容器' }
if ($names | Where-Object { $_ -eq $container }) {
    podman rm -f --volumes $container | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'MariaDB 测试容器清理失败' }
}
