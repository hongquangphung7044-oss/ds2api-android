# DS2API Android

将 [ouqiting/ds2api](https://github.com/ouqiting/ds2api)（DeepSeek 网页版 → OpenAI / Claude / Gemini 兼容 API 代理，Go 实现）移植到 Android 的 APK 封装，并集成 **mihomo 代理桥**实现多账号独立出口 IP。

## 它解决什么问题

DeepSeek 官方对网页版账号有严格的风控：同一 IP 下多账号高频请求极易触发封号。本 App 把上游 ds2api（把 DeepSeek 网页版封装成 OpenAI 兼容 API）打包成 Android 应用，并内置 mihomo 代理桥，让**每个 DeepSeek 账号走独立的机场节点出口 IP**，从根本上降低多账号同 IP 的封号风险。

## 核心特性

| 特性 | 说明 |
|------|------|
| **本地 API 服务** | 子进程运行 ds2api（默认端口 `5001`），暴露 OpenAI 兼容接口 |
| **mihomo 代理桥** | 多订阅导入、多协议节点支持（ss/vmess/trojan/hysteria 等）、每账号独立出口节点 |
| **主/备用节点顺位** | 每账号可指定主节点 + 多个备用节点，主节点响应超时自动切换备用1→备用2（fallback 内核级故障转移） |
| **两阶段启动** | 阶段1 用 `use` 池启动保证不 fatal；阶段2 通过 API 取真实节点列表补回用户顺位并热重载 |
| **失效节点自动恢复** | mihomo fatal 报节点 not found 时，自动从配置剔除失效节点并重启（循环多轮，机场改名/下架不阻塞启动） |
| **用量统计** | 每次 API 请求自动累加 token 用量，独立存储不丢失；按今日/累计/7日趋势/模型/账号多维度聚合（侧边栏"用量统计"页） |
| **账户停用检测** | 上游内核自动识别被风控停用的账号并在 WebUI 标记（上游 b2dae335+） |
| **视觉路由** | 含图片请求自动路由到 vision 模型，响应模型名回写为原模型（对客户端透明，上游 v4.6.1+） |
| **节点延迟测试** | mihomo 内核 healthcheck 批量测延迟，全节点列表可滚动查看 |
| **代理出口验证** | 一键验证代理出口 IP（ip-api / ip.sb / ipinfo.io 多源兜底） |
| **后台保活** | 前台服务 + 电池优化豁免，降低后台被杀概率 |
| **与手机代理工具共存** | mihomo 仅监听 127.0.0.1，不设系统代理；端口自动避让 Clash/FlClash |
| **内置 WebUI** | 上游管理界面打包进 assets，无需联网 |
| **实时日志** | 合并显示 ds2api + mihomo 全部日志，支持复制 |

## 系统要求

- Android 7.0（API 24）及以上
- 仅支持 **arm64-v8a** 架构（覆盖绝大多数真机）
- 机场订阅（代理功能需要，无订阅可作纯 API 代理用）

## 快速开始

### 1. 安装

从 [Releases](https://github.com/hongquangphung7044-oss/ds2api-android/releases) 下载最新 APK 安装。首次启动会请求通知权限和电池优化豁免（建议同意，防止后台被杀）。

### 2. 配置代理（可选，但推荐）

1. 主界面点 **代理配置**
2. **机场订阅** 区点 `+ 添加订阅`，填名称和订阅 URL，勾选启用
3. 点 **启动 mihomo**，等待日志出现 `API 就绪 ✓` 和节点列表加载
4. 在 **节点绑定** 区，为每个账号选择 **主节点 + 备用节点1 + 备用节点2**（顺序即故障转移优先级，主节点响应超时自动切备用1→备用2；每账号选不同节点可最大化隔离）
5. 点 **保存配置**
6. 点 **测试全部延迟** 查看全节点延迟列表，点 **验证代理** 确认出口 IP

> 节点顺位通过两阶段启动保证：阶段1 用 use 池启动避免 fatal，阶段2 用 API 取真实节点列表补回用户顺位。即使用户选了已下架/改名的节点，mihomo 也不会启动失败，只会跳过该节点继续用其他节点。

### 3. 启动 API 服务

1. 回主界面点 **启动服务**，等待日志出现 `服务就绪 ✓`
2. 点 **打开网页** 进入管理界面，用界面顶部显示的 **管理密钥** 登录
3. 在 WebUI 中添加 DeepSeek 账号、创建 API Key
4. 客户端以 `http://127.0.0.1:5001/v1` 为 Base URL 调用 OpenAI 兼容接口

> 代理桥会在服务启动时自动拉起（如果已启用），无需手动启动 mihomo。

### 4. 查看用量统计

1. WebUI 左侧侧边栏顶部点 **用量统计**（BarChart3 图标）
2. 查看 4 张概览卡：今日 token / 累计总量 / 今日请求数 / 活跃账号
3. 7 日趋势柱状图：点击柱子查看该日详情（输入/输出/流式分布）
4. 按模型 / 按账号统计表：查看各模型 token 消耗占比、各账号用量分布
5. 右上角可切换时间范围（7 天 / 30 天 / 全部）并导出 CSV

> 用量数据基于每次请求完成后的 token 估算（tiktoken 编码），独立于对话历史存储，长期累积不丢失。仅统计请求成功的记录。

## 项目结构

```
ds2api-android/
├── app/src/main/
│   ├── java/com/ds2api/android/
│   │   ├── MainActivity.java          # 主界面：启停服务、日志展示、电池优化豁免
│   │   ├── ProxyConfigActivity.java   # 代理配置界面：订阅管理、节点绑定、延迟测试
│   │   ├── ServerService.java         # 前台服务：协调启动 ds2api + mihomo 双子进程
│   │   ├── MihomoManager.java         # mihomo 进程管理、API 封装、代理验证
│   │   └── LogStore.java              # 日志存储与监听
│   ├── jniLibs/arm64-v8a/
│   │   ├── libds2api.so               # 上游 ds2api Go 二进制（交叉编译）
│   │   └── libmihomo.so               # mihomo 内核二进制
│   ├── assets/
│   │   ├── webui/                     # 上游 WebUI 预构建产物
│   │   └── config.default.json        # 默认配置
│   └── AndroidManifest.xml
├── scripts/                           # 构建脚本（Go 交叉编译、mihomo 下载、环境部署）
├── patches/                           # 上游补丁（Android DNS 修复等）
├── docs/                              # 文档
│   ├── ARCHITECTURE.md                # 架构概览
│   ├── BUILD-ENVIRONMENT.md           # 构建环境部署
│   └── specs/                         # 设计文档
└── .github/workflows/                 # CI：Build APK + Rebuild Native
```

## 构建方式

### 方式一：GitHub Actions（推荐，无需本地环境）

1. **发布新版本**：修改 `app/build.gradle` 的 `versionCode`/`versionName`，提交后打 `v*` tag 推送，`Build APK` workflow 自动构建并发布 Release。
2. **更新上游二进制**：在 Actions 页面手动触发 `Rebuild Native Binaries` workflow，它会拉取上游最新代码、应用补丁、重新编译 `libds2api.so` 和下载 `libmihomo.so`，提交回仓库。

```bash
# 发布新版本示例
git tag -a v4.6.1-mihomo-vision-fix15 -m "修复说明"
git push origin v4.6.1-mihomo-vision-fix15
```

### 方式二：本地构建

环境：JDK 17、Android SDK（platform 35 + build-tools）、Go ≥ 1.26、Node ≥ 22（仅更新 WebUI 时需要）。

```bash
# 0. 全新机器一键部署构建环境（幂等）
./scripts/setup-build-env.sh && source ~/.bashrc

# 1.（可选）从上游重新构建原生二进制与 WebUI（自动应用 patches/ 并注入版本号）
./scripts/build-go.sh master
./scripts/build-mihomo.sh v1.19.29

# 2. 构建 APK
./gradlew assembleRelease    # 产物: app/build/outputs/apk/release/app-release.apk
```

> 仓库已内置预编译的 `libds2api.so`、`libmihomo.so` 与 WebUI 资源，不运行构建脚本也能直接打包。
> `app/keystore/ds2api.keystore` 为公开示例签名（口令 `ds2api123`），正式发布请自行更换。

详细构建环境说明见 [docs/BUILD-ENVIRONMENT.md](docs/BUILD-ENVIRONMENT.md)，架构设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 配置文件说明

| 文件 | 位置 | 作用 |
|------|------|------|
| `config.json` | 应用私有目录 | ds2api 主配置：账号、API Key、Proxy 条目（mihomo 启动时自动注入） |
| `mihomo_config.json` | 应用私有目录 | mihomo 代理桥配置：订阅列表、账号节点绑定、启用开关 |
| `mihomo/config.yaml` | 应用私有目录/mihomo/ | 运行时生成：proxy-providers、proxy-groups、listeners |

`mihomo_config.json` 关键字段：

```json
{
  "enabled": true,
  "api_port": 9090,
  "socks5_base_port": 7890,
  "subscriptions": [
    {"name": "机场A", "url": "https://...", "enabled": true}
  ],
  "accounts": [
    {
      "identifier": "xxx@example.com",
      "subscription_name": "机场A",
      "node_names": ["主节点", "备用节点"],
      "current_node_index": 0
    }
  ]
}
```

## 与上游的版本对应

| 本仓库 | 上游 ds2api | 说明 |
|--------|-------------|------|
| 4.6.1-mihomo-vision-fix18 | v4.6.1 + main 分支（b2dae335，含视觉路由） | 修复用量统计：按本地时区按日重置、7 日趋势补零并滚动刷新、今日活跃账号计数 |
| 4.6.1-mihomo-vision-fix17 | v4.6.1 + main 分支（b2dae335，含视觉路由） | 新增 token 用量统计功能（独立存储 + 多维聚合 + webui 页面） |
| 4.6.1-mihomo-vision-fix16 | v4.6.1 + main 分支（b2dae335，含视觉路由） | 同步上游内核（账户停用检测、专家模式文件拆分） |
| 4.6.1-mihomo-vision-fix15 | v4.6.1 + main 分支（含视觉路由） | 两阶段启动、主/备用节点顺位、失效节点自动恢复、端口冲突修复 |
| 4.6.1-mihomo-vision-fix14 | v4.6.1 + main 分支（含视觉路由） | fallback 故障转移、多订阅支持、视觉路由 |
| 4.6.1-mihomo-vision-fix3 | v4.6.1 + main 分支（含视觉路由） | 集成 mihomo 代理桥、视觉路由、端口冲突修复 |

## 与手机代理工具共存

本 App 的 mihomo 子进程**仅在 App 内运行**，不设系统代理、不拦截其他 App 流量、随 App 退出而终止。可与 Clash/FlClash/Shadowrocket 等手机代理工具同时运行：

- 默认使用高位端口（SOCKS5 `17890`、API `19090`），避开代理工具常用的 7890/9090
- 启动时自动检测端口占用，被占则递增找可用端口（日志会输出调整记录）
- 旧版本配置的 7890/9090 端口会自动迁移到新默认值

> 注：若手机代理工具开启 **TUN/VPN 全局模式**，会接管所有流量包括 mihomo 的出口，可能导致代理套代理。建议用规则模式，或放行 `chat.deepseek.com` 直连。

## 排查常见问题

| 现象 | 排查 |
|------|------|
| mihomo 启动失败（节点 not found） | 已自动恢复：解析 fatal 日志剔除失效节点后重启，机场改名/下架不阻塞使用 |
| 保存配置后节点变未选择 | 已修复：本地不再 YAML 解析过滤节点，改用两阶段启动 + API 取真实节点 |
| 日志提示"热重载用户顺位失败" | 不影响核心功能：fallback group 仍可用 use 池自动故障转移，仅用户顺位未生效 |
| 切换节点 404 | provider 节点未加载完，已修复（等待 15s + 重试），仍失败检查订阅是否有效 |
| 代理验证 ECONNREFUSED | 新增账号后未重启 mihomo，已修复（保存配置自动重启）；或端口被占用，已自动避让 |
| 与 Clash/FlClash 端口冲突 | 已改用高位端口 17890/19090 + 自动避让，旧配置自动迁移 |
| 代理配置页闪退 | 删除无效订阅条目后重进，或删除 `mihomo_config.json` 重配 |
| 后台被杀 | 同意电池优化豁免请求，或手动在系统设置加入白名单 |
| 视觉路由不生效 | 确认 WebUI 设置中已开启，且 `libds2api.so` 为含视觉路由的版本（重新触发 Rebuild Native） |
| 响应模型名仍是原模型 | 正常行为，视觉路由对客户端透明（请求用 vision 模型，响应回写原模型名） |
| 用量统计 token 数与 DeepSeek 账单不一致 | 正常：统计基于本地 tiktoken 估算（非上游返回），中文场景通常偏低 5-15%，仅作趋势参考 |
| 用量统计页为空 | 正常：仅有成功请求才记录；首次使用需有 API 请求后才生成数据 |

## 许可

上游 ds2api 采用 **AGPL-3.0**（见 `LICENSE`）。本移植封装同样以 AGPL-3.0 发布。
