param([int]$Port = 15258)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-gbase-it'
if (-not $env:SCHEMALOOM_IT_GBASE_PASSWORD) { throw 'Please set SCHEMALOOM_IT_GBASE_PASSWORD' }
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot query Podman containers' }
if ($names | Where-Object { $_ -eq $container }) { throw "Container $container already exists. Run stop-gbase-integration.ps1 first" }
$image = if ($env:SCHEMALOOM_IT_GBASE_IMAGE) { $env:SCHEMALOOM_IT_GBASE_IMAGE } else { 'localhost/gbase8a:9.5.3-free' }
# GBase 8a 需要 --privileged 模式（因为需要 sysctl 设置和 SSH 服务）
podman run -d --name $container --privileged -p "127.0.0.1:${Port}:5258" $image | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'GBase 8a container failed to start' }

# 等待容器启动并修复初始化问题
Write-Output "Waiting for GBase 8a container to start..."
Start-Sleep -Seconds 5

# 修复 /opt 目录权限
podman exec $container bash -c "chown -R gbase:gbase /opt" | Out-Null

# 配置 SSH 密钥（gcinstall 需要通过 SSH 检查节点环境）
podman exec $container bash -c "
echo 'root:GBase_123' | chpasswd
echo 'gbase:GBase_123' | chpasswd
cat /home/gbase/.ssh/id_rsa.pub >> /home/gbase/.ssh/authorized_keys 2>/dev/null || true
cat /home/gbase/.ssh/id_rsa.pub >> /root/.ssh/authorized_keys 2>/dev/null || true
chmod 600 /home/gbase/.ssh/authorized_keys /root/.ssh/authorized_keys 2>/dev/null || true
chown gbase:gbase /home/gbase/.ssh/authorized_keys 2>/dev/null || true
" | Out-Null

# 执行 SetSysEnv.py（以 root 身份）
podman exec $container bash -c "cd /opt/gcinstall && python SetSysEnv.py --dbaUser=gbase --installPrefix=/opt" | Out-Null

# 修改 InstallFuns.py 跳过 sysctl 检查（容器环境无法设置网络内核参数）
podman exec $container bash -c "sed -i 's/return flag,noExecNode/return True, []/' /opt/gcinstall/InstallFuns.py" | Out-Null

# 执行集群初始化
Write-Output "Initializing GBase 8a cluster (this may take several minutes)..."
podman exec $container bash -c "chown gbase:gbase /opt/gcinstall/demo.options && su - gbase -c 'cd /opt/gcinstall && yes | ./gcinstall.py --silent=demo.options -i'" | Out-Null

# 建立 distribution
$ip = podman exec $container bash -c "hostname -I | awk '{print `$1}'"
podman exec $container bash -c "cat > /opt/gcinstall/gcChangeInfo.xml <<EOF
<?xml version=`"1.0`" encoding=`"utf-8`"?>
<servers>
    <server ip=`"${ip}`"/>
</servers>
EOF
chown gbase:gbase /opt/gcinstall/gcChangeInfo.xml" | Out-Null

podman exec $container bash -c "su - gbase -c 'cd /opt/gcinstall && gcadmin distribution gcChangeInfo.xml p 1 d 0'" | Out-Null

# 初始化节点数据映射
podman exec $container bash -c "su - gbase -c 'gccli -uroot -e `"initnodedatamap;`"'" | Out-Null

# 尝试从容器提取 JDBC 驱动
$driverPath = Join-Path $PSScriptRoot "..\drivers\gbase-connector-java-9.5.0.10-build1-bin.jar"
if (-not (Test-Path $driverPath)) {
    $driverLocations = @(
        "/opt/gbase/server/connector/jdbc/gbase-connector-java-9.5.0.10-build1-bin.jar",
        "/opt/gbase/jdbc/gbase-connector-java.jar",
        "/usr/local/gbase/jdbc/gbase-connector-java.jar"
    )
    foreach ($loc in $driverLocations) {
        podman cp "${container}:${loc}" $driverPath 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Output "JDBC driver extracted from ${loc} to drivers/"
            break
        }
    }
    if (-not (Test-Path $driverPath)) {
        Write-Warning "Failed to extract JDBC driver automatically. Please place it manually in drivers/"
    }
}

Write-Output "GBase 8a is ready on port $Port"
