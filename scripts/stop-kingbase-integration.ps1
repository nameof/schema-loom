$ErrorActionPreference = 'Stop'
$container = 'schema-loom-kingbase-it'
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot query Podman containers' }
if ($names | Where-Object { $_ -eq $container }) {
    podman rm -f --volumes $container | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Failed to remove KingbaseES test container' }
}
