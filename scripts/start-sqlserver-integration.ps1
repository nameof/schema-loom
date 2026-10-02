param([int]$Port = 11433)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-sqlserver-it'
if (-not $env:SCHEMALOOM_IT_SQLSERVER_PASSWORD) { throw '请设置 SCHEMALOOM_IT_SQLSERVER_PASSWORD' }
$password = $env:SCHEMALOOM_IT_SQLSERVER_PASSWORD
if (podman ps -a --format '{{.Names}}' | Where-Object { $_ -eq $container }) {
    throw "测试容器 $container 已存在，请先执行 stop-sqlserver-integration.ps1"
}
$env:MSSQL_SA_PASSWORD = $password
podman run -d --name $container -p "127.0.0.1:${Port}:1433" -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD mcr.microsoft.com/mssql/server:2022-latest | Out-Host
for ($i = 0; $i -lt 120; $i++) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $ready = podman exec $container bash -lc '(/opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -Q "SELECT 1" || /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -Q "SELECT 1")' 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($exitCode -eq 0 -and $ready -match '1 rows affected') { Write-Output "SQL Server is ready on port $Port"; exit 0 }
    Start-Sleep -Seconds 2
}
throw 'SQL Server container did not become ready'
