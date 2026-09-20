#!/usr/bin/env pwsh
<#
.SYNOPSIS
    在 Alibaba Cloud Linux 3 容器里编译 EverCare 服务端，产出可直接上传的 `run`。

.DESCRIPTION
    与 scripts/deploy.ps1 配套：
      1. 构建（或复用）与服务器同款系统的编译镜像；
      2. 把项目挂进容器，`cargo build --release`；
      3. 产物拷成项目根目录的 `run`，并把非 glibc 的动态库收进 `data/lib/`。

    EverCare 服务端的 TLS 走 rustls，没有 OpenSSL / OpenCV 之类的原生依赖，所以
    `data/lib/` 正常情况下应该是空的 —— 脚本仍然做这一步并检查 ldd，是因为空目录
    本身就是"没有引入原生依赖"的证据，哪天有人加了带 C 依赖的 crate，这里会立刻发现。

.EXAMPLE
    pwsh scripts/build-alinux3.ps1
    pwsh scripts/build-alinux3.ps1 -SkipImageBuild      # 镜像已存在时跳过构建
    pwsh scripts/build-alinux3.ps1 -Clean               # 先 cargo clean
    pwsh scripts/build-alinux3.ps1 -Proxy ""            # 不走代理
#>
param(
    [string]$BinaryName = "evercare-server",
    [string]$ImageName = "evercare-server-alinux3",
    [switch]$NoCache,
    [switch]$SkipImageBuild,
    [string]$Proxy = "http://host.docker.internal:7890",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRootPath = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$DockerfilePath = Join-Path $ScriptDir "Dockerfile.alinux3"
$InnerScriptPath = Join-Path $ScriptDir ".build_alinux3_inner.sh"

if (!(Test-Path $DockerfilePath)) {
    throw "找不到 Dockerfile：$DockerfilePath"
}

Write-Host "===== 项目根目录 =====" -ForegroundColor Cyan
Write-Host $ProjectRootPath
Write-Host "二进制名：$BinaryName"
Write-Host ""

Write-Host "===== 检查 Docker =====" -ForegroundColor Cyan
docker version --format '{{.Server.Version}}'
if ($LASTEXITCODE -ne 0) {
    throw "docker 不可用，请先启动 Docker Desktop"
}

# =========================
# 1. 构建编译镜像
# =========================
if (-not $SkipImageBuild) {
    Write-Host ""
    Write-Host "===== 构建镜像：$ImageName =====" -ForegroundColor Cyan

    $buildArgs = @(
        "build",
        "--progress=plain",
        "-f", $DockerfilePath,
        "-t", $ImageName
    )

    if ($Proxy -ne "") {
        $buildArgs += @(
            "--build-arg", "HTTP_PROXY=$Proxy",
            "--build-arg", "HTTPS_PROXY=$Proxy",
            "--build-arg", "ALL_PROXY=$Proxy",
            "--build-arg", "NO_PROXY=localhost,127.0.0.1,::1"
        )
    }

    if ($NoCache) { $buildArgs += "--no-cache" }

    $buildArgs += $ScriptDir

    docker @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "docker build 失败" }
}

# =========================
# 2. 写入容器内构建脚本
# =========================
# 单独写文件而不是内联到 docker run，是为了固定 LF + 无 BOM：
# bash 读到 Windows 的 CRLF 会报难以定位的 "bad interpreter"。
$CleanValue = if ($Clean) { "1" } else { "0" }

$InnerContent = @'
#!/usr/bin/env bash
set -euo pipefail

cd /work

BIN_NAME="${BIN_NAME:-evercare-server}"
CLEAN="${CLEAN:-0}"

echo "===== 容器环境 ====="
cat /etc/os-release | head -2 || true
uname -m
ldd --version | head -n 1 || true
rustc --version
cargo --version
echo

if [ "$CLEAN" = "1" ]; then
  echo "===== cargo clean ====="
  ( cd server && cargo clean )
fi

echo "===== 编译 ====="
cd /work/server
cargo build --release --bin "$BIN_NAME"

SRC_BIN="target/release/$BIN_NAME"
if [ ! -f "$SRC_BIN" ]; then
  echo "ERROR: 没找到产物 $SRC_BIN"
  find target/release -maxdepth 1 -type f -printf "%f\n" 2>/dev/null | sort || true
  exit 1
fi

cd /work

echo
echo "===== 整理产物 ====="
rm -f ./run
mkdir -p ./data/lib
rm -rf ./data/lib/*

cp -v "server/$SRC_BIN" ./run
chmod +x ./run

# 运行时先从 ./data/lib 找库。即使现在那里是空的，将来引入原生依赖也不用改部署方式。
patchelf --set-rpath '$ORIGIN/data/lib' ./run

echo
echo "===== 动态库依赖 ====="
ldd ./run | tee ./data/ldd.txt

if grep -q "not found" ./data/ldd.txt; then
  echo "ERROR: 有动态库找不到，见上面的 ldd 输出"
  exit 1
fi

# glibc 自带的那些不打包：服务器上一定有，而且版本必须跟着系统走。
is_glibc_family() {
  case "$1" in
    libc.so*|ld-linux*.so*|libpthread.so*|libdl.so*|libm.so*|librt.so*|libresolv.so*|libutil.so*|libnss_*.so*|libanl.so*|libcrypt.so*|libgcc_s.so*)
      return 0 ;;
    *) return 1 ;;
  esac
}

echo
echo "===== 打包非 glibc 动态库 ====="
ldd ./run 2>/dev/null | awk '$2 == "=>" && $3 ~ /^\// { print $3 }' | sort -u | while read -r lib; do
  base="$(basename "$lib")"
  if is_glibc_family "$base"; then continue; fi
  [ -f "$lib" ] || continue
  echo "copy $lib -> ./data/lib/$base"
  cp -Lv "$lib" "./data/lib/$base"
done

LIB_COUNT="$(find ./data/lib -maxdepth 1 -type f -name '*.so*' | wc -l)"
echo "data/lib 中的库数量：$LIB_COUNT"
if [ "$LIB_COUNT" = "0" ]; then
  echo "（空目录是预期结果：服务端只依赖 glibc）"
fi

echo
echo "===== 需要的 GLIBC 版本 ====="
# 这是能不能在服务器上跑起来的关键：这里的最高版本必须 <= 服务器的 glibc 版本。
strings ./run 2>/dev/null | grep -E '^GLIBC_[0-9]' | sort -V | uniq | tail -n 5 || true

echo
echo "===== 产物 ====="
ls -lh ./run
echo "OK"
'@

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$InnerContent = $InnerContent -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($InnerScriptPath, $InnerContent, $Utf8NoBom)

# =========================
# 3. 在容器里编译
# =========================
Write-Host ""
Write-Host "===== 容器内编译 =====" -ForegroundColor Cyan

$runArgs = @(
    "run",
    "--rm",
    "-e", "BIN_NAME=$BinaryName",
    "-e", "CLEAN=$CleanValue"
)

if ($Proxy -ne "") {
    $runArgs += @(
        "-e", "HTTP_PROXY=$Proxy",
        "-e", "HTTPS_PROXY=$Proxy",
        "-e", "ALL_PROXY=$Proxy",
        "-e", "http_proxy=$Proxy",
        "-e", "https_proxy=$Proxy",
        "-e", "all_proxy=$Proxy",
        "-e", "NO_PROXY=localhost,127.0.0.1,::1,host.docker.internal",
        "-e", "no_proxy=localhost,127.0.0.1,::1,host.docker.internal"
    )
}

$runArgs += @(
    "--mount", "type=bind,source=$ProjectRootPath,target=/work",
    "-w", "/work",
    $ImageName,
    "bash", "/work/scripts/.build_alinux3_inner.sh"
)

docker @runArgs
if ($LASTEXITCODE -ne 0) { throw "容器内编译失败" }

# =========================
# 4. 宿主机侧校验
# =========================
$RunPath = Join-Path $ProjectRootPath "run"
if (!(Test-Path $RunPath)) { throw "宿主机上没有生成 run：$RunPath" }

$runSizeMB = [math]::Round((Get-Item $RunPath).Length / 1MB, 1)
$LibDir = Join-Path $ProjectRootPath "data\lib"
$LibCount = 0
if (Test-Path $LibDir) {
    $LibCount = (Get-ChildItem $LibDir -File -ErrorAction SilentlyContinue).Count
}

Write-Host ""
Write-Host "===== 编译完成 =====" -ForegroundColor Green
Write-Host "run       => $RunPath ($runSizeMB MB)"
Write-Host "data/lib  => $LibCount 个文件"
Write-Host ""
Write-Host "下一步：pwsh scripts/deploy.ps1"
