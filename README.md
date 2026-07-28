# DS2API Android

将 [ouqiting/ds2api](https://github.com/ouqiting/ds2api)（DeepSeek 网页版 → OpenAI / Claude / Gemini 兼容 API 代理，Go 实现）移植到 Android 的 APK 封装。

原理：上游 Go 服务端交叉编译为 `android/arm64` 可执行文件，以 `libds2api.so` 之名打包进 `jniLibs`，安装时随原生库释放到应用目录；App 以子进程方式启动它，并实时捕获 stdout/stderr 显示在界面上。内置上游 WebUI 静态资源，无需联网构建。

## 界面功能

- **启动服务** —— 前台服务保活，子进程运行 ds2api（默认端口 `5001`）
- **停止服务** —— 结束子进程
- **打开网页** —— 浏览器打开管理界面 `http://127.0.0.1:5001/admin/`
- **实时运行日志** —— 详细输出 Go 服务端全部日志；支持“复制日志”一键复制、长按自由选择复制，方便排查问题
- 界面同时显示 **服务地址** 与 **管理密钥（Admin Key）**，点击即可复制

## 使用

1. 安装 APK（仅支持 arm64-v8a 设备，Android 7.0+）。
2. 点击 **启动服务**，等待日志出现 `服务就绪 ✓`。
3. 点击 **打开网页** 进入管理界面，用界面顶部显示的管理密钥登录。
4. 在管理界面中添加 DeepSeek 账号、创建 API Key（配置保存在应用私有目录 `config.json`，重启不丢失）。
5. 客户端以 `http://127.0.0.1:5001/v1` 为 Base URL 调用 OpenAI 兼容接口。

## 自行构建

环境：JDK 17、Android SDK（platform 35 + build-tools）、Go ≥ 1.26、Node ≥ 22（仅更新 WebUI 时需要）。

```bash
# 0. 全新机器一键部署构建环境（幂等；x86_64 / aarch64 均可）
./scripts/setup-build-env.sh && source ~/.bashrc

# 1.（可选）从上游重新构建原生二进制与 WebUI（自动应用 patches/ 并注入版本号）
./scripts/build-go.sh master

# 2. 构建 APK
./gradlew assembleRelease    # 产物: app/build/outputs/apk/release/app-release.apk
```

> 仓库已内置预编译的 `libds2api.so` 与 WebUI 资源，不运行 `build-go.sh` 也能直接打包。
> `app/keystore/ds2api.keystore` 为公开示例签名（口令 `ds2api123`），仅用于方便构建可安装的 APK，正式发布请自行更换。

完整环境说明、arm64 主机 aapt2 坑位、移植要点备忘见 [docs/BUILD-ENVIRONMENT.md](docs/BUILD-ENVIRONMENT.md)。

## 与上游的版本对应

| 本仓库 | 上游 ds2api |
|--------|-------------|
| 4.6.1  | v4.6.1      |

## 许可

上游 ds2api 采用 **AGPL-3.0**（见 `LICENSE`）。本移植封装同样以 AGPL-3.0 发布。
