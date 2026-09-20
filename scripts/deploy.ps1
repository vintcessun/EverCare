#!/usr/bin/env pwsh
<#
.SYNOPSIS
    把 build-alinux3.ps1 产出的 `run` 上传到服务器并重启 systemd 服务。

.DESCRIPTION
    面向 root@121.43.244.103 的 `/root/evercare` 部署：
      1. 校验本地 `run` 存在（由 scripts/build-alinux3.ps1 生成）；
      2. 以 `.new` 临时名上传，上传期间服务照常运行，零停机；
      3. 上传成功后才进入停机窗口：停服务 -> 原子 `mv` 就位（旧二进制备份为 run.bak）
         -> 启动服务 -> 回显状态与健康检查。
         上传中断不会破坏现有可用版本。

    **不会上传任何密钥。** 上游 LLM 的 key、兑换码这些放在服务器上的
    /root/evercare/evercare.env（chmod 600），由 systemd 的 EnvironmentFile 读取。
    这个仓库是公开的，密钥绝不能进 git。

.EXAMPLE
    pwsh scripts/deploy.ps1
    pwsh scripts/deploy.ps1 -NoRestart
    pwsh scripts/deploy.ps1 -HostName 1.2.3.4 -RemoteBase /opt/evercare
#>
param(
    [string]$User = "root",
    [string]$HostName = "121.43.244.103",
    [int]$Port = 22,
    [string]$RemoteBase = "/root/evercare",
    [string]$Service = "evercare.service",
    [string]$HealthUrl = "http://127.0.0.1:8087/healthz",
    [string]$IdentityFile = "",
    [switch]$NoRestart
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$Remote = "$User@$HostName"

# ---- 0. 前置检查 ----
foreach ($exe in @("ssh", "scp")) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) {
        throw "未找到 $exe，需要 OpenSSH 客户端；Windows 可在 [设置 - 应用 - 可选功能] 中安装。"
    }
}

$LocalRun = Join-Path $ProjectRoot "run"
if (-not (Test-Path $LocalRun)) {
    throw "本地未找到 run：$LocalRun`n请先运行 pwsh scripts/build-alinux3.ps1"
}

$LocalLib = Join-Path $ProjectRoot "data\lib"
$libFiles = @()
if (Test-Path $LocalLib) {
    $libFiles = @(Get-ChildItem $LocalLib -File -ErrorAction SilentlyContinue)
}

$runSizeMB = [math]::Round((Get-Item $LocalRun).Length / 1MB, 1)
Write-Host "===== 部署目标 =====" -ForegroundColor Cyan
Write-Host "远端      : ${Remote}:$Port"
Write-Host "远端目录  : $RemoteBase"
Write-Host "服务      : $Service"
Write-Host "本地 run  : $LocalRun  ($runSizeMB MB)"
Write-Host "附带库    : $($libFiles.Count) 个"
Write-Host ""

$commonOpts = @("-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=10")
$sshOpts = $commonOpts + @("-p", "$Port")
$scpOpts = $commonOpts + @("-P", "$Port")
if ($IdentityFile -ne "") {
    $idPath = (Resolve-Path $IdentityFile).Path
    $sshOpts += @("-i", $idPath)
    $scpOpts += @("-i", $idPath)
}

function Invoke-Remote {
    param([string]$Script, [string]$What)
    & ssh @sshOpts $Remote $Script
    if ($LASTEXITCODE -ne 0) { throw "远端步骤失败（$What），exit=$LASTEXITCODE" }
}

# ---- 1. 连通性探测 ----
Write-Host "==> 探测 SSH 连接..." -ForegroundColor DarkGray
Invoke-Remote "echo connected as `$(whoami) on `$(hostname)" "连接测试"

# ---- 2. 准备上传目录，不停服务 ----
Write-Host "==> 准备上传目录（不停服务）..." -ForegroundColor DarkGray
$prep = @"
set -e
mkdir -p '$RemoteBase/data'
rm -rf '$RemoteBase/run.new' '$RemoteBase/data/lib.new'
echo '[remote] 上传目录就绪'
"@
Invoke-Remote $prep "建目录/清残留"

# ---- 3. 上传（用相对路径，避免 Windows 盘符的冒号被 scp 当成主机名）----
Push-Location $ProjectRoot
try {
    Write-Host "==> 上传 run -> $RemoteBase/run.new ..." -ForegroundColor DarkGray
    & scp @scpOpts "run" "${Remote}:$RemoteBase/run.new"
    if ($LASTEXITCODE -ne 0) { throw "上传 run 失败，exit=$LASTEXITCODE" }

    if ($libFiles.Count -gt 0) {
        Write-Host "==> 上传 data/lib -> $RemoteBase/data/lib.new ..." -ForegroundColor DarkGray
        & scp @scpOpts "-r" "data/lib" "${Remote}:$RemoteBase/data/lib.new"
        if ($LASTEXITCODE -ne 0) { throw "上传 data/lib 失败，exit=$LASTEXITCODE" }
    }
}
finally {
    Pop-Location
}

# ---- 4. 就位并重启，停机窗口仅此一段 ----
$swapLib = if ($libFiles.Count -gt 0) {
    "rm -rf '$RemoteBase/data/lib'; mv '$RemoteBase/data/lib.new' '$RemoteBase/data/lib'"
} else {
    "true"
}

if ($NoRestart) {
    Write-Host "==> 热替换文件（-NoRestart：不重启服务）..." -ForegroundColor DarkGray
    $deploy = @"
set -e
[ -f '$RemoteBase/run' ] && cp -f '$RemoteBase/run' '$RemoteBase/run.bak' || true
mv -f '$RemoteBase/run.new' '$RemoteBase/run'
chmod +x '$RemoteBase/run'
$swapLib
echo '[remote] 文件已就位（旧二进制备份为 run.bak）；需手动 systemctl restart $Service 生效'
"@
    Invoke-Remote $deploy "替换文件"
}
else {
    Write-Host "==> 停服务 -> 替换 -> 启动..." -ForegroundColor DarkGray
    $deploy = @"
set -e
echo '[remote] 停止服务...'
systemctl stop '$Service' 2>/dev/null || true
[ -f '$RemoteBase/run' ] && cp -f '$RemoteBase/run' '$RemoteBase/run.bak' || true
mv -f '$RemoteBase/run.new' '$RemoteBase/run'
chmod +x '$RemoteBase/run'
$swapLib
echo '[remote] 已替换（旧二进制备份为 run.bak）'
echo '[remote] 启动服务...'
systemctl start '$Service'
sleep 2
echo -n '[remote] is-active: '; systemctl is-active '$Service' || true
echo '----- 健康检查 -----'
curl -sS --max-time 5 '$HealthUrl' || echo '(健康检查失败，看下面的日志)'
echo
echo '----- 最近日志 -----'
journalctl -u '$Service' -n 15 --no-pager 2>&1 || true
"@
    Invoke-Remote $deploy "停服务/替换/启动"
}

Write-Host ""
Write-Host "===== 部署完成 =====" -ForegroundColor Green
Write-Host "回滚：ssh $Remote `"systemctl stop $Service && mv -f $RemoteBase/run.bak $RemoteBase/run && systemctl start $Service`""
