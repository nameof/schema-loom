param([int]$Port = 11521)
$ErrorActionPreference = "Stop"
$container = "schema-loom-oracle-it"
if ([string]::IsNullOrWhiteSpace($env:SCHEMALOOM_IT_ORACLE_PASSWORD)) { throw "请设置 SCHEMALOOM_IT_ORACLE_PASSWORD" }
if ((podman container exists $container) -eq 0) { throw "测试容器已存在，请先执行 stop-oracle-integration.ps1" }
$env:ORACLE_PWD = $env:SCHEMALOOM_IT_ORACLE_PASSWORD
podman run -d --name $container -p "127.0.0.1:${Port}:1521" -e ORACLE_PWD container-registry.oracle.com/database/free:latest | Out-Host
for ($i = 0; $i -lt 180; $i++) {
    $inspect = podman inspect $container | ConvertFrom-Json
    if ($inspect.State.Health.Status -eq "healthy") {
        $oracleSetup = "ALTER SESSION SET CONTAINER=FREEPDB1;`nCREATE USER SCHEMALOOM IDENTIFIED BY $env:SCHEMALOOM_IT_ORACLE_PASSWORD;`nGRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE TO SCHEMALOOM;`nALTER USER SCHEMALOOM QUOTA UNLIMITED ON USERS;`nEXIT`n"
        $oracleSetup | podman exec -i $container sqlplus -s / as sysdba | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Oracle test user setup failed" }
        Write-Output "Oracle is ready on port $Port"
        exit 0
    }
    Start-Sleep -Seconds 2
}
throw "Oracle container did not become ready"
