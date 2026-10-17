param([int]$Port = 15258)
$ErrorActionPreference = 'Stop'
$container = 'schema-loom-gbase-it'
if (-not $env:SCHEMALOOM_IT_GBASE_PASSWORD) { throw 'Please set SCHEMALOOM_IT_GBASE_PASSWORD' }
$names = podman ps -a --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot query Podman containers' }
if ($names | Where-Object { $_ -eq $container }) { throw "Container $container already exists. Run stop-gbase-integration.ps1 first" }
$image = if ($env:SCHEMALOOM_IT_GBASE_IMAGE) { $env:SCHEMALOOM_IT_GBASE_IMAGE } else { 'localhost/gbase8a:9.5.3-free' }
# 测试库与用户，需与集成测试的环境变量保持一致
$dbName = if ($env:SCHEMALOOM_IT_GBASE_DATABASE) { $env:SCHEMALOOM_IT_GBASE_DATABASE } else { 'schemaloom_it' }
$dbUser = if ($env:SCHEMALOOM_IT_GBASE_USER) { $env:SCHEMALOOM_IT_GBASE_USER } else { 'schemaloom' }
$dbPwd = $env:SCHEMALOOM_IT_GBASE_PASSWORD
# 默认安装的 GBase 8a 为 root cluster 模式（不存在虚拟集群 vc0），无需 VC。
# 仅当目标集群确实启用了虚拟集群时才设置该变量，此时 DDL 需先 use vc、distribution 需追加 vc 参数。
$vcName = $env:SCHEMALOOM_IT_GBASE_VC
$vcSql = if ($vcName) { "use vc $vcName; " } else { "" }
$vcArg = if ($vcName) { " vc $vcName" } else { "" }
if ($dbPwd.Contains("'")) { throw 'Password must not contain a single quote' }
# 固定容器 IP。GBase 8a 的实例目录就是 /opt/<节点IP>，而 podman 重启容器后 IP 可能漂移：
# 一旦漂到别的地址，服务就会挂到历史残留的旧实例目录（曾漂回 10.88.0.2 后加载了 VC 模式的旧实例，
# 导致库和用户全部丢失）。固定 IP 可保证容器重启后仍加载同一实例目录。
$nodeFixedIp = if ($env:SCHEMALOOM_IT_GBASE_NODE_IP) { $env:SCHEMALOOM_IT_GBASE_NODE_IP } else { '10.88.0.10' }
# GBase 8a 需要 --privileged 模式（因为需要 sysctl 设置和 SSH 服务）
podman run -d --name $container --privileged --ip $nodeFixedIp -p "127.0.0.1:${Port}:5258" $image | Out-Host
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

# 实例目录为 /opt/<节点IP>。IP 已由 --ip 固定，此处仍动态探测以校验固定是否生效
$nodeIp = (podman exec $container bash -c "hostname -I | awk '{print `$1}'").Trim()
if (-not $nodeIp) { throw 'Failed to detect GBase 8a node IP' }
if ($nodeIp -ne $nodeFixedIp) {
    Write-Output "WARNING: node IP is $nodeIp but $nodeFixedIp was requested; the instance directory may be stale"
}
$prefix = "/opt/$nodeIp"
Write-Output "GBase 8a node IP = $nodeIp, instance prefix = $prefix"

# 调小内存与线程参数。安装生成的默认值按大内存物理机计算
# （max_heap_table_size=160GB、thread_pool_size=512、gcluster_max_thread_in_pool=600），
# 在受限的容器内存（默认约 8GB）下会导致排序类查询失败，报
# "Failed to query in gnode ... can't get datasession" 或 "Failed to get CommonTaskQueue"。
Write-Output "Tuning gnode/gcluster memory parameters for the container..."
podman exec $container bash -c "sed -i 's/^max_heap_table_size *=.*/max_heap_table_size = 1073741824/' $prefix/gnode/config/gbase_8a_gbase.cnf && sed -i 's/^thread_pool_size *=.*/thread_pool_size = 128/' $prefix/gnode/config/gbase_8a_gbase.cnf && sed -i 's/^gcluster_max_thread_in_pool *=.*/gcluster_max_thread_in_pool = 128/' $prefix/gcluster/config/gbase_8a_gcluster.cnf && sed -i 's/^gcluster_max_conn_in_pool *=.*/gcluster_max_conn_in_pool = 64/' $prefix/gcluster/config/gbase_8a_gcluster.cnf"
if ($LASTEXITCODE -ne 0) { throw 'Failed to tune GBase 8a memory parameters' }

# 参数在服务启动时读取，必须重启后生效
Write-Output "Restarting GBase 8a services to apply parameters..."
podman exec $container bash -c "su - gbase -c 'gcluster_services all restart'" | Out-Null
Start-Sleep -Seconds 20

# 生成 gcChangeInfo.xml。gcadmin distribution 只接受 <rack><node ip="..."/></rack> 形式，
# 写成 <server ip="..."/> 会被拒绝并报 "error format for distribution in xml file content"。
podman exec $container bash -c "printf '<?xml version=`"1.0`" encoding=`"utf-8`"?>\n<servers>\n    <rack>\n        <node ip=`"$nodeIp`"/>\n    </rack>\n</servers>\n' > /opt/gcinstall/gcChangeInfo.xml && chown gbase:gbase /opt/gcinstall/gcChangeInfo.xml"
if ($LASTEXITCODE -ne 0) { throw 'Failed to write gcChangeInfo.xml' }

# d 0 表示不生成备份分片，gcadmin 会交互式告警并要求确认（input [Y,y] or [N,n]）。
# 必须喂入 Y，否则命令会一直等待标准输入直到超时被杀，表现为"创建 distribution 超时"。
podman exec $container bash -c "su - gbase -c 'cd /opt/gcinstall && echo Y | gcadmin distribution gcChangeInfo.xml p 1 d 0$vcArg'"
if ($LASTEXITCODE -ne 0) { throw 'Failed to create GBase 8a distribution' }

# 初始化节点数据映射，否则建库报 "nodedatamap is not initialized"
podman exec $container bash -c "su - gbase -c `"gccli -uroot -e '${vcSql}initnodedatamap;'`""
if ($LASTEXITCODE -ne 0) { throw 'Failed to init nodedatamap' }

# 创建测试库与用户。create user 不支持 if not exists，因此本脚本要求容器为全新创建。
$initSql = "${vcSql}create database if not exists $dbName default character set utf8mb4; create user $dbUser identified by '$dbPwd'; grant all privileges on $dbName.* to $dbUser;"
podman exec $container bash -c "su - gbase -c `"gccli -uroot -e '$initSql'`""
if ($LASTEXITCODE -ne 0) { throw 'Failed to create GBase 8a test database or user' }

# 校验集群与测试库均可用
podman exec $container bash -c "su - gbase -c `"gccli -uroot -e '${vcSql}use $dbName; select 1;'`"" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'GBase 8a environment validation failed' }
Write-Output "GBase 8a cluster, database [$dbName] and user [$dbUser] are ready"

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
