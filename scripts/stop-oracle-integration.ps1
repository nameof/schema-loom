$ErrorActionPreference = 'Stop'
$container = 'schema-loom-oracle-it'
$exists = podman ps -a --format '{{.Names}}' | Where-Object { $_ -eq $container }
if ($exists) { podman rm -f $container | Out-Host }
