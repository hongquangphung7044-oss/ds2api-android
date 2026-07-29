# 架构概览

本文档说明 ds2api-android 的整体架构、模块职责和数据流，帮助快速理解项目。

## 整体架构

App 采用「Java 宿主 + 双子进程」架构：Java 层负责 UI 和协调，两个 Go 二进制作为子进程分别承担 API 代理和代理桥职责。

```
┌─────────────────────────────────────────────────────────┐
│                   Android App (Java)                     │
│                                                          │
│  ┌──────────────┐   ┌───────────────────┐               │
│  │ MainActivity │   │ ProxyConfigActivity│               │
│  │  启停服务/日志 │   │  订阅/节点/延迟测试 │               │
│  └──────┬───────┘   └─────────┬─────────┘               │
│         │                      │                          │
│         ▼                      ▼                          │
│  ┌──────────────────────────────────────┐                │
│  │           ServerService               │                │
│  │  (前台 Service, 协调启动双子进程)        │                │
│  │  - 启动 mihomo → 注入 Proxy → 启动 ds2api│               │
│  └─────┬─────────────────────┬──────────┘                │
│        │                      │                            │
│        ▼                      ▼                            │
│  ┌───────────┐         ┌──────────────┐                  │
│  │MihomoManager│        │  LogStore    │                  │
│  │ mihomo 进程  │        │ 日志聚合/分发  │                  │
│  │ 管理/API封装 │        │              │                  │
│  └──────┬─────┘         └──────┬───────┘                  │
│         │                      ▲                          │
└─────────┼──────────────────────┼──────────────────────────┘
          │ exec                 │ stdout/stderr
          ▼                      │
   ┌───────────────┐      ┌───────────────┐
   │ 子进程1:       │      │ 子进程2:       │
   │ libmihomo.so  │      │ libds2api.so  │
   │               │      │               │
   │ - 解析订阅     │      │ - HTTP API    │
   │ - SOCKS5 入站  │      │   :5001       │
   │   7890/7891.. │      │ - 账号管理     │
   │ - RESTful API │      │ - 调 DeepSeek  │
   │   :9090       │      │   (走代理)     │
   └───────┬───────┘      └───────┬───────┘
           │                      │ SOCKS5
           │◀─────────────────────┘
           │
           ▼
   ┌───────────────┐
   │ 机场节点 (ss/  │
   │ vmess/trojan/ │
   │ hysteria...)  │
   └───────────────┘
```

## 模块职责

### Java 层

| 模块 | 职责 | 关键方法 |
|------|------|----------|
| [MainActivity](file:///workspace/ds2api-android/app/src/main/java/com/ds2api/android/MainActivity.java) | 主界面：启停服务、展示日志、请求电池优化豁免 | `requestBatteryOptimizationExemption()` |
| [ProxyConfigActivity](file:///workspace/ds2api-android/app/src/main/java/com/ds2api/android/ProxyConfigActivity.java) | 代理配置：订阅 CRUD、节点绑定、延迟测试、代理验证 | `doTestDelay()`, `doVerifyProxy()`, `saveConfig()` |
| [ServerService](file:///workspace/ds2api-android/app/src/main/java/com/ds2api/android/ServerService.java) | 前台服务：协调启动 mihomo + ds2api 子进程、注入 Proxy 配置 | `startMihomoIfNeeded()`, `injectProxies()` |
| [MihomoManager](file:///workspace/ds2api-android/app/src/main/java/com/ds2api/android/MihomoManager.java) | mihomo 进程管理、配置生成、API 封装、延迟测试、代理验证 | `start()`, `applyNodeSelection()`, `testGroupDelay()`, `verifyProxyExit()` |
| [LogStore](file:///workspace/ds2api-android/app/src/main/java/com/ds2api/android/LogStore.java) | 日志存储与监听分发，聚合双子进程输出 | `log()`, `addListener()` |

### 子进程层

| 二进制 | 来源 | 职责 |
|--------|------|------|
| `libds2api.so` | 上游 ouqiting/ds2api 交叉编译 | HTTP API 服务，把 DeepSeek 网页版封装成 OpenAI 兼容接口 |
| `libmihomo.so` | mihomo 官方发布 | 代理内核，解析订阅、SOCKS5 入站、节点选择 |

## 关键数据流

### 1. 启动服务流程

```
用户点"启动服务"
  → ServerService.startServer()
  → startInternal()
  → doStart():
      1. 确保 config.json / WebUI 就绪
      2. startMihomoIfNeeded():
         - 读 mihomo_config.json
         - MihomoManager.start(): 下载订阅 → 生成 config.yaml → exec libmihomo.so
         - probeReady(): 轮询 /version API（30s 超时）
         - applyNodeSelection(): 等待 provider 节点加载 → 切换 selector 到选定节点
         - injectProxies(): 往 config.json 写入 SOCKS5 Proxy 条目 + 设置 Account.ProxyID
      3. exec libds2api.so（读取注入后的 config.json）
      4. 轮询 ds2api 就绪 → 标记 RUNNING
```

### 2. API 请求流程（带代理）

```
客户端 → http://127.0.0.1:5001/v1/chat/completions
  → libds2api.so 收到请求
  → 查 Account.ProxyID = "mihomo-0"
  → 查 Proxy{ID:"mihomo-0", Type:"socks5", Host:"127.0.0.1", Port:7890}
  → 请求经 SOCKS5 127.0.0.1:7890 转发
  → libmihomo.so 收到 SOCKS5 连接
  → 查 listener inbound-acc-0 → proxy: acc-0
  → acc-0 (selector group) 当前选定的节点
  → 经节点加密隧道 → chat.deepseek.com
  → 响应原路返回
```

### 3. 视觉路由流程（上游功能）

```
客户端发送含图片的请求 (model: deepseek-chat)
  → libds2api.so 的 MaybeAutoRouteVision():
     - 检测到图片 + auto_route_vision.enabled = true
     - req["model"] 改为 vision 模型（如 deepseek-vision）
     - 记录 originalModel = "deepseek-chat"
  → 实际用 vision 模型调 DeepSeek（能识图）
  → 响应返回后，ResponseModel 回写为 originalModel
  → 客户端收到的响应 model 字段仍是 "deepseek-chat"（对客户端透明）
```

## 端口分配

| 端口 | 用途 | 配置项 |
|------|------|--------|
| 5001 | ds2api HTTP API | `ServerService.PORT`（固定） |
| 9090 | mihomo RESTful API | `mihomo_config.json: api_port` |
| 7890 | mihomo SOCKS5 入站（账号 0） | `socks5_base_port + 0` |
| 7891 | mihomo SOCKS5 入站（账号 1） | `socks5_base_port + 1` |
| ... | 每账号递增 | `socks5_base_port + N` |

## 配置文件生命周期

```
首次启动:
  assets/config.default.json → 复制到 → filesDir/config.json
  (无 mihomo_config.json，运行时按需创建)

代理配置保存:
  ProxyConfigActivity.saveConfig()
  → 写 filesDir/mihomo_config.json（订阅、节点绑定、开关）
  → 若 mihomo 运行中：reloadConfig() + applyNodeSelection()

mihomo 启动:
  读 mihomo_config.json
  → 下载订阅到 mihomo/providers/sub-N.yaml
  → 生成 mihomo/config.yaml（proxy-providers + proxy-groups + listeners）
  → exec libmihomo.so -d mihomo/ -f mihomo/config.yaml

ds2api 启动前:
  读 config.json
  → injectProxies(): 为每账号注入 SOCKS5 Proxy 条目 + 设 Account.ProxyID
  → 写回 config.json
  → exec libds2api.so（读注入后的 config.json）
```

## 关键设计决策

### 为什么用双子进程而非内嵌协议库？

mihomo 作为独立进程解析订阅（ss/vmess/trojan/hysteria 等多协议），比在 Go 内嵌协议库协议支持更全、故障转移更成熟、ds2api 零改动。详见 [设计文档](specs/2026-07-28-mihomo-proxy-bridge-design.md)。

### 为什么 provider 用 type: file 而非 http？

机场常对 mihomo/clash 默认 UA 返回 403。App 层下载订阅文件（多 UA 轮试）可绕过拦截，mihomo 只读本地文件。借鉴 FlClash。

### 为什么 group 用 select 而非 fallback？

fallback 的 filter 正则对含 emoji/中文的节点名匹配不可靠。改用 select + App 层 API 手动切换，用户明确控制节点，主节点失败时 App 顺位尝试备用节点。

### 为什么配置存独立文件？

ds2api Go 服务端运行时会写回 `config.json`，嵌套的 mihomo 配置段会被覆盖。独立 `mihomo_config.json` 避免此问题。

## 上游同步

App 不修改上游 Go 源码，通过 `Rebuild Native Binaries` workflow 同步上游：

1. 拉取上游指定分支/tag
2. 应用 `patches/0001-android-dns-resolver.patch`（Android 无 resolv.conf，注入显式 DNS）
3. 交叉编译 `CGO_ENABLED=0 GOOS=android GOARCH=arm64` → `libds2api.so`
4. 用 ldflags 注入版本号（避免显示 dev）
5. 构建 WebUI（`npm ci && npm run build`）
6. 下载对应版本 `libmihomo.so`
7. 提交产物到仓库

详细构建环境见 [BUILD-ENVIRONMENT.md](BUILD-ENVIRONMENT.md)。
