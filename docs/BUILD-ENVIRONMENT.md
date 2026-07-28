# APK 构建环境部署指南

本文档总结本项目在一台全新 Linux 机器（Debian/Ubuntu，x86_64 或 aarch64）上从零部署构建环境的完整过程。一键脚本见 `scripts/setup-build-env.sh`，手工步骤与排错说明如下。

## 工具链清单

| 组件 | 版本 | 用途 | 备注 |
|------|------|------|------|
| Go | ≥ 1.26（go.mod 要求） | 交叉编译 ds2api 服务端为 `android/arm64` | 官方 tar 包即可 |
| Node.js | ≥ 22 | 构建上游 WebUI（Vite 8） | Debian 12 自带 Node 18 太旧，需官方包 |
| JDK | 17（Temurin/OpenJDK） | 运行 Gradle / AGP | AGP 8.x 要求 JDK 17 |
| Android SDK | cmdline-tools + `platforms;android-35` + `build-tools;35.0.0` | 打包 APK | sdkmanager 安装 |
| Gradle | 8.9（wrapper 自动下载） | 构建系统 | 无需单独安装 |
| aapt2 (arm64) | 37.0.0（第三方构建） | **仅 aarch64 主机需要**，见下文 | x86_64 主机不需要 |

## 一键部署（推荐）

```bash
./scripts/setup-build-env.sh        # 幂等，已装组件自动跳过
source ~/.bashrc                    # 载入环境变量（或重开终端）
./gradlew assembleRelease           # 产物: app/build/outputs/apk/release/app-release.apk
```

脚本默认把工具装在 `/opt`（可用 `PREFIX` 环境变量改），并写入以下环境变量到 `~/.bashrc`：

```bash
export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
```

## 手工部署步骤

### 1. Go

```bash
curl -LO https://go.dev/dl/go1.26.5.linux-$(dpkg --print-architecture).tar.gz   # amd64/arm64
tar -C /usr/local -xzf go1.26.5.*.tar.gz
export PATH=/usr/local/go/bin:$PATH
```

### 2. Node.js 22

```bash
# aarch64 → linux-arm64；x86_64 → linux-x64
curl -LO https://nodejs.org/dist/v22.17.0/node-v22.17.0-linux-arm64.tar.gz
tar -C /usr/local -xzf node-v22.17.0-linux-arm64.tar.gz
ln -sf /usr/local/node-v22.17.0-linux-arm64/bin/{node,npm,npx} /usr/local/bin/
```

### 3. JDK 17（Temurin）

```bash
# aarch64 把 aarch64 换成 x64 即 x86_64 包
curl -LO "https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse"
tar -C /opt -xzf eclipse && ln -sfn /opt/jdk-17.* /opt/jdk17
```

### 4. Android SDK

```bash
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -d /opt/android-sdk/cmdline-tools commandlinetools-linux-*.zip
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

### 5. aarch64 主机的 aapt2 替换（关键坑）

Google 官方 SDK 与 AGP 自带的 aapt2 **只有 x86_64 Linux 版**，在 arm64 机器上构建会报：

```
AAPT2 ... Daemon startup failed ... Syntax error: "(" unexpected
```

解决方案（二选一）：

- **推荐**：使用社区构建的 arm64 aapt2，并告诉 AGP 覆盖默认路径：

  ```bash
  curl -LO https://github.com/HomuHomu833/android-sdk-custom/releases/download/37.0.0/android-sdk-aarch64-linux-gnu.tar.xz
  tar -xJf android-sdk-aarch64-linux-gnu.tar.xz
  # 全局生效，之后构建无需加参数：
  echo 'android.aapt2FromMavenOverride=/opt/arm64-sdk/android-sdk/build-tools/37.0.0/aapt2' >> ~/.gradle/gradle.properties
  ```

  也可以每次构建时命令行传入：`./gradlew assembleRelease -Pandroid.aapt2FromMavenOverride=/path/to/aapt2`

- 或者安装 qemu-user-static + amd64 运行库做透明模拟（慢，不推荐）。

AGP 其余原生工具（d8/r8、apksigner、zipflinger）都是 Java 实现，arm64 无问题；构建不涉及 NDK 编译（我们的 .so 是预编译产物直接打包）。

## 构建与签名

```bash
# （可选）从上游重新构建 libds2api.so + WebUI（自动应用 patches/*.patch 并注入版本号）
./scripts/build-go.sh master

# 打包（release 用仓库内 app/keystore/ds2api.keystore 签名，口令 ds2api123）
./gradlew assembleRelease
```

- 每次发布递增 `app/build.gradle` 的 `versionCode`。
- 正式发布请更换 keystore：`keytool -genkeypair -keystore app/keystore/ds2api.keystore -alias ds2api -keyalg RSA -validity 10950`。

## 移植要点备忘（改动上游时必须知道）

1. **二进制打包方式**：Go 服务端交叉编译 `CGO_ENABLED=0 GOOS=android GOARCH=arm64`，命名 `libds2api.so` 放入 `jniLibs/arm64-v8a/`，清单设 `android:extractNativeLibs="true"`，安装时释放到 `nativeLibraryDir` 可直接 exec（App 内含"复制到私有目录重试"兜底）。
2. **Android DNS 补丁**（`patches/0001-*.patch`）：Android 无 `/etc/resolv.conf`，纯 Go 构建 DNS 退化为 `127.0.0.1:53` 导致无法访问任何域名；补丁注入显式 DNS（默认 `223.5.5.5/119.29.29.29/1.2.4.8/8.8.8.8`，可用 `DS2API_DNS_SERVERS` 覆盖）。**重新拉取上游构建时必须应用**（`build-go.sh` 已自动处理）。
3. **版本号注入**：上游版本由发布流水线用 ldflags 注入，手动构建必须加 `-X ds2api/internal/version.BuildVersion=$(cat VERSION)`，否则界面显示 `dev` 且误报"发现新版本"。
4. **WebUI 预构建**：Android 上没有 npm，必须用 Node 预先 `npm ci && npm run build --prefix webui`，产物（`static/admin`）打包进 assets，运行时释放到私有目录并设 `DS2API_STATIC_ADMIN_DIR`、`DS2API_AUTO_BUILD_WEBUI=0`。
5. **路径全部走环境变量**：`DS2API_CONFIG_PATH`、`DS2API_STATIC_ADMIN_DIR`、`HOME`、`TMPDIR`、`PORT`、`DS2API_ADMIN_KEY` 由 App 在启动子进程时注入；工作目录设为应用私有目录。
6. **就绪探测需绕过系统代理**：App 内探测 `127.0.0.1` 必须 `Proxy.NO_PROXY`，否则用户开代理/VPN 时误报"服务未就绪"。
7. **仅支持 arm64-v8a**：Go 对 `android/arm`、`android/amd64` 已强制外部链接（需 NDK），真机基本都是 arm64，故只打包单 ABI。
