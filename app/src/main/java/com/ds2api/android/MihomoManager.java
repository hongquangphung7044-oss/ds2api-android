package com.ds2api.android;

import android.content.Context;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 管理 mihomo 子进程的生命周期与配置。
 *
 * mihomo 作为本地 SOCKS5 桥运行：解析机场订阅、暴露多个 SOCKS5 入站端口
 * (每账号独占一个)、提供 RESTful API 用于节点切换。ds2api 的账号代理
 * 指向本地 mihomo 端口，实现账号定向选节点 + 故障转移。
 *
 * 所有方法设计为静态，进程级状态与 ServerService 共享。
 */
public final class MihomoManager {

    private static final String TAG = "mihomo";

    // mihomo 默认配置常量
    public static final int DEFAULT_API_PORT = 9090;
    public static final int DEFAULT_SOCKS5_BASE_PORT = 7890;
    private static final int READY_PROBE_TIMEOUT_MS = 30_000;
    private static final int READY_PROBE_INTERVAL_MS = 1000;

    // 进程级状态
    private static volatile Process process;
    private static volatile boolean enabled;
    private static volatile int apiPort = DEFAULT_API_PORT;
    private static volatile String apiSecret = "";
    private static volatile File workDir;
    /** 上次退出码：-100 从未启动，-1 启动中/运行中，>=0 已退出。 */
    private static volatile int lastExitCode = -100;

    private MihomoManager() {}

    public static boolean isEnabled() { return enabled; }
    public static boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }
    public static int getApiPort() { return apiPort; }
    public static String getApiSecret() { return apiSecret; }
    public static int getLastExitCode() { return lastExitCode; }

    /** 释放 libmihomo.so 到可执行目录，返回可执行路径。 */
    static String ensureBinary(Context ctx) throws Exception {
        String binPath = ctx.getApplicationInfo().nativeLibraryDir + "/libmihomo.so";
        File bin = new File(binPath);
        if (!bin.exists()) {
            throw new IllegalStateException("未找到 mihomo 二进制: " + binPath
                    + "（仅内置 arm64-v8a）");
        }
        try {
            Os.chmod(binPath, 0755);
        } catch (Throwable t) {
            LogStore.get().log(TAG, "chmod 失败（通常可忽略）: " + t.getMessage());
        }
        if (!bin.canExecute()) {
            // 部分 ROM 不允许执行 nativeLibraryDir，复制到私有目录兜底
            File filesDir = ctx.getFilesDir();
            File local = new File(filesDir, "bin/libmihomo");
            local.getParentFile().mkdirs();
            try (InputStream in = new java.io.FileInputStream(bin);
                 OutputStream out = new FileOutputStream(local)) {
                copy(in, out);
            }
            Os.chmod(local.getAbsolutePath(), 0755);
            binPath = local.getAbsolutePath();
            LogStore.get().log(TAG, "nativeLibraryDir 不可执行，已改用 " + binPath);
        }
        return binPath;
    }

    /**
     * 启动 mihomo 子进程。
     * @param ctx Context
     * @param mihomoWorkDir mihomo 工作目录（存放 config.yaml 和 providers）
     * @param config mihomo 配置 JSON（来自 ds2api config.json 的 mihomo 段）
     */
    static synchronized void start(Context ctx, File mihomoWorkDir, JSONObject config) throws Exception {
        if (isRunning()) {
            LogStore.get().log(TAG, "mihomo 已在运行，忽略重复启动");
            return;
        }
        workDir = mihomoWorkDir;
        workDir.mkdirs();

        // 解析配置
        enabled = true;
        apiPort = config.optInt("api_port", DEFAULT_API_PORT);
        apiSecret = config.optString("api_secret", "").trim();
        if (apiSecret.isEmpty()) {
            apiSecret = UUID.randomUUID().toString().replace("-", "");
            config.put("api_secret", apiSecret);
        }
        int socks5Base = config.optInt("socks5_base_port", DEFAULT_SOCKS5_BASE_PORT);
        int updateInterval = config.optInt("subscription_update_interval", 3600);

        // 解析订阅列表（支持多订阅）+ 兼容旧版单订阅字段
        List<Subscription> subs = parseSubscriptions(config);
        if (subs.isEmpty()) {
            throw new IllegalStateException("未配置任何订阅地址");
        }
        // 读取账号绑定
        List<AccountBinding> bindings = parseBindings(config, socks5Base, subs);

        // 1. 先下载所有订阅文件（App 层下载，绕过 mihomo 内置 http provider 的 403）
        File providersDir = new File(workDir, "providers");
        providersDir.mkdirs();
        List<Subscription> okSubs = new ArrayList<>();
        for (Subscription sub : subs) {
            File subFile = new File(providersDir, sub.providerName + ".yaml");
            if (downloadSubscription(sub.url, subFile, sub.name)) {
                okSubs.add(sub);
            } else {
                LogStore.get().log(TAG, "订阅 [" + sub.name + "] 下载失败，跳过该订阅");
            }
        }
        LogStore.get().log(TAG, "订阅下载完成: " + okSubs.size() + "/" + subs.size() + " 成功");

        // 2. 没有订阅下载成功则无法继续
        if (okSubs.isEmpty()) {
            throw new IllegalStateException("所有订阅均下载失败（可能是 UA 被机场拦截或订阅 URL 无效），请检查日志");
        }

        // 3. 重建 bindings：只保留订阅下载成功的账号绑定
        List<AccountBinding> okBindings = new ArrayList<>();
        for (AccountBinding b : bindings) {
            if (b.subscription == null) continue;
            for (Subscription s : okSubs) {
                if (s.providerName.equals(b.subscription.providerName)) {
                    okBindings.add(b);
                    break;
                }
            }
        }
        // 没有有效绑定时，给第一个账号兜底用第一个成功订阅
        if (okBindings.isEmpty() && !bindings.isEmpty() && !okSubs.isEmpty()) {
            AccountBinding b0 = bindings.get(0);
            okBindings.add(new AccountBinding(b0.accountIdentifier, b0.nodeNames,
                    b0.currentNodeIndex, b0.socksPort, b0.index, okSubs.get(0)));
            LogStore.get().log(TAG, "账号 " + b0.accountIdentifier + " 未绑定订阅或绑定订阅失败，兜底使用 ["
                    + okSubs.get(0).name + "]");
        }

        // 4. 只用下载成功的订阅生成 config.yaml（避免引用不存在的 provider 文件）
        File configFile = new File(workDir, "config.yaml");
        String yaml = generateConfigYaml(okSubs, updateInterval, apiPort, apiSecret, okBindings);
        try (OutputStream out = new FileOutputStream(configFile)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        LogStore.get().log(TAG, "配置已写入 " + configFile.getAbsolutePath());

        // 启动子进程
        String binPath = ensureBinary(ctx);
        ProcessBuilder pb = new ProcessBuilder(binPath, "-d", workDir.getAbsolutePath(),
                "-f", configFile.getAbsolutePath());
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        Process p;
        try {
            p = pb.start();
        } catch (java.io.IOException ioe) {
            String msg = String.valueOf(ioe.getMessage());
            if (!msg.contains("Permission denied") && !msg.contains("error=13")) {
                throw ioe;
            }
            LogStore.get().log(TAG, "直接执行被拒绝（" + msg + "），复制到私有目录后重试");
            File filesDir = ctx.getFilesDir();
            File local = new File(filesDir, "bin/libmihomo");
            local.getParentFile().mkdirs();
            try (InputStream in = new java.io.FileInputStream(new File(binPath));
                 OutputStream out = new FileOutputStream(local)) {
                copy(in, out);
            }
            Os.chmod(local.getAbsolutePath(), 0755);
            pb.command(local.getAbsolutePath(), "-d", workDir.getAbsolutePath(),
                    "-f", configFile.getAbsolutePath());
            p = pb.start();
        }
        process = p;
        final Process proc = p;
        lastExitCode = -1;
        LogStore.get().log(TAG, "进程已启动，等待 API 就绪...");

        // 日志读取线程
        Thread reader = new Thread(() -> readOutput(proc), "mihomo-log-reader");
        reader.setDaemon(true);
        reader.start();

        // 退出监听线程
        Thread waiter = new Thread(() -> {
            int code;
            try {
                code = proc.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            LogStore.get().log(TAG, "进程已退出，exit code = " + code);
            // 只有当前进程仍是自己时才更新状态（避免覆盖手动 stop 后的重置）
            if (process == proc) {
                lastExitCode = code;
                process = null;
            }
        }, "mihomo-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /** 探测 mihomo API 是否就绪。 */
    static boolean probeReady() {
        long deadline = System.currentTimeMillis() + READY_PROBE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) {
                return false;
            }
            if (apiGet("/version") != null) {
                LogStore.get().log(TAG, "API 就绪 ✓  控制端口: " + apiPort);
                return true;
            }
            try {
                Thread.sleep(READY_PROBE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return false;
            }
        }
        LogStore.get().log(TAG, "警告: " + (READY_PROBE_TIMEOUT_MS / 1000)
                + " 秒内未检测到 API 就绪");
        return false;
    }

    /** 停止 mihomo 子进程。 */
    static synchronized void stop() {
        Process p = process;
        if (p != null) {
            LogStore.get().log(TAG, "正在停止 mihomo...");
            p.destroy();
            new Thread(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }, "mihomo-killer").start();
            process = null;
        }
        // 手动停止：重置为"未运行"，避免 UI 显示"已退出"
        lastExitCode = -100;
        enabled = false;
    }

    // ========== mihomo API 封装 ==========

    /**
     * 获取指定订阅 provider 的节点名列表（不过滤，返回全部）。
     */
    static List<String> fetchNodeList(String providerName) {
        List<String> names = new ArrayList<>();
        JSONObject resp = apiGet("/providers/proxies/" + providerName);
        if (resp == null) return names;
        JSONArray proxies = resp.optJSONArray("proxies");
        if (proxies == null) return names;
        for (int i = 0; i < proxies.length(); i++) {
            JSONObject node = proxies.optJSONObject(i);
            if (node == null) continue;
            String name = node.optString("name", "");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 强制刷新指定订阅 provider（file 类型 provider 不支持 API 刷新，返回 false）。 */
    static boolean refreshSubscription(String providerName) {
        // file 类型 provider 无法通过 API 重新拉取，需 App 层重新下载订阅文件后 reload
        return apiPut("/providers/proxies/" + providerName, null);
    }

    /**
     * App 层重新下载所有订阅文件到 providers/ 目录，并触发内核热重载。
     * 用于"更新订阅"按钮：file provider 只能通过替换文件 + reload 刷新。
     * @param config mihomo 配置 JSON（含 subscriptions）
     * @return 成功下载的订阅数
     */
    static int redownloadAllSubscriptions(JSONObject config) {
        if (workDir == null) return 0;
        List<Subscription> subs = parseSubscriptions(config);
        File providersDir = new File(workDir, "providers");
        providersDir.mkdirs();
        int ok = 0;
        for (Subscription sub : subs) {
            File subFile = new File(providersDir, sub.providerName + ".yaml");
            if (downloadSubscription(sub.url, subFile, sub.name)) {
                ok++;
            }
        }
        LogStore.get().log(TAG, "重新下载订阅: " + ok + "/" + subs.size() + " 成功");
        if (ok > 0) {
            reloadConfig();
        }
        return ok;
    }

    /** 获取所有已配置订阅的 provider 名列表。 */
    static List<String> fetchAllProviderNames() {
        List<String> names = new ArrayList<>();
        JSONObject resp = apiGet("/providers/proxies");
        if (resp == null) return names;
        JSONObject providers = resp.optJSONObject("providers");
        if (providers == null) return names;
        JSONArray keys = providers.names();
        if (keys == null) return names;
        for (int i = 0; i < keys.length(); i++) {
            names.add(keys.optString(i));
        }
        return names;
    }

    /** 切换 selector group 的当前节点。 */
    static boolean switchNode(String groupName, String nodeName) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("name", nodeName);
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            java.net.URI uri = new java.net.URI("http", null, "127.0.0.1", apiPort,
                    "/proxies/" + groupName, null, null);
            c = (HttpURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (!apiSecret.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + apiSecret);
            }
            try (OutputStream os = c.getOutputStream()) {
                os.write(bodyBytes);
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) return true;
            // 读取错误信息
            InputStream err = c.getErrorStream();
            if (err != null) {
                byte[] data = readAll(err);
                LogStore.get().log(TAG, "切换节点失败 [" + groupName + " → " + nodeName
                        + "] HTTP " + code + ": " + new String(data, StandardCharsets.UTF_8));
            }
            return false;
        } catch (Throwable t) {
            LogStore.get().log(TAG, "切换节点异常: " + t.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 热重载配置（修改节点绑定后调用）。 */
    static boolean reloadConfig() {
        File configFile = new File(workDir, "config.yaml");
        try {
            JSONObject body = new JSONObject();
            body.put("path", configFile.getAbsolutePath());
            return apiPut("/configs?force=true", body.toString());
        } catch (Throwable t) {
            LogStore.get().log(TAG, "热重载失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 启动/热重载后，通过 API 把每个账号的 selector 切换到主节点。
     * 必须在 probeReady 成功后调用。主节点失败时切到备用节点（顺位）。
     *
     * 关键修复：probeReady 仅检查 /version 可用，但此时 proxy-provider 可能仍在
     * "Start initial provider" 阶段，group 引用的 provider 节点尚未加载完，
     * group 内为空 → PUT /proxies/{group} 返回 404。
     * 这里改为：先等待至少一个 provider 拉到节点，再执行切换；切换失败则短暂重试。
     */
    static void applyNodeSelection(JSONObject config) {
        if (!isRunning()) return;
        int socks5Base = config.optInt("socks5_base_port", DEFAULT_SOCKS5_BASE_PORT);
        List<Subscription> subs = parseSubscriptions(config);
        List<AccountBinding> bindings = parseBindings(config, socks5Base, subs);

        // 1. 等待 provider 节点加载完成（最长 15s）
        //    provider 节点未加载时 group 为空，PUT 切换必 404
        long deadline = System.currentTimeMillis() + 15000;
        boolean providerReady = false;
        while (System.currentTimeMillis() < deadline) {
            for (Subscription s : subs) {
                List<String> nodes = fetchNodeList(s.providerName);
                if (!nodes.isEmpty()) {
                    providerReady = true;
                    break;
                }
            }
            if (providerReady) break;
            try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
        }
        if (!providerReady) {
            LogStore.get().log(TAG, "警告: provider 节点在 15s 内未加载完成，"
                    + "切换节点可能失败（请检查订阅是否有效）");
        }

        // 2. 逐账号切换节点，失败则短暂重试（group 可能仍在创建中）
        for (AccountBinding b : bindings) {
            if (b.nodeNames.isEmpty()) continue;
            boolean switched = false;
            for (String nodeName : b.nodeNames) {
                // 重试 3 次，间隔 800ms（应对 group/provider 异步创建）
                for (int attempt = 0; attempt < 3; attempt++) {
                    if (switchNode(b.groupName, nodeName)) {
                        LogStore.get().log(TAG, "账号 " + b.accountIdentifier
                                + " → 订阅: " + (b.subscription != null ? b.subscription.name : "?")
                                + " 节点: " + nodeName);
                        switched = true;
                        break;
                    }
                    if (attempt < 2) {
                        try { Thread.sleep(800); } catch (InterruptedException ignored) { break; }
                    }
                }
                if (switched) break;
            }
            if (!switched) {
                LogStore.get().log(TAG, "警告: 账号 " + b.accountIdentifier
                        + " 切换节点全部失败");
            }
        }
    }

    // ========== 配置生成 ==========

    /**
     * 生成 mihomo config.yaml。
     * 支持多订阅：每个订阅一个 proxy-provider，每个账号的 select group 只 use
     * 该账号指定的订阅 provider。启动后通过 API 切换到主节点。
     */
    static String generateConfigYaml(List<Subscription> subs, int updateInterval,
                                     int apiPort, String secret,
                                     List<AccountBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 自动生成，请勿手动编辑\n");
        sb.append("allow-lan: false\n");
        sb.append("mode: rule\n");
        sb.append("log-level: info\n");
        sb.append("external-controller: 127.0.0.1:").append(apiPort).append("\n");
        sb.append("secret: '").append(escapeYamlSingle(secret)).append("'\n\n");

        // 多订阅源
        // 关键：用 type: file，订阅文件由 App 层（downloadSubscription）下载。
        // 借鉴 FlClash：App 层下载能自由控制 UA/重试/超时，绕过机场对 mihomo
        // 内置 http provider 的 UA 拦截（很多机场对 mihomo/clash 默认 UA 返回 403）。
        sb.append("proxy-providers:\n");
        for (Subscription sub : subs) {
            sb.append("  ").append(sub.providerName).append(":\n");
            sb.append("    type: file\n");
            sb.append("    path: ./providers/").append(sub.providerName).append(".yaml\n");
            sb.append("    health-check:\n");
            sb.append("      enable: true\n");
            sb.append("      url: http://www.gstatic.com/generate_204\n");
            sb.append("      interval: 300\n\n");
        }

        // 每账号一个 selector group，只 use 该账号指定的订阅 provider
        sb.append("proxy-groups:\n");
        for (AccountBinding b : bindings) {
            sb.append("  - name: ").append(b.groupName).append("\n");
            sb.append("    type: select\n");
            sb.append("    use: [").append(b.subscription != null ? b.subscription.providerName : "DIRECT").append("]\n\n");
        }

        // 多入站端口隔离
        sb.append("listeners:\n");
        for (int i = 0; i < bindings.size(); i++) {
            AccountBinding b = bindings.get(i);
            sb.append("  - name: inbound-acc-").append(i).append("\n");
            sb.append("    type: socks\n");
            sb.append("    port: ").append(b.socksPort).append("\n");
            sb.append("    proxy: ").append(b.groupName).append("\n");
        }

        // 基本规则
        sb.append("\nrules:\n");
        sb.append("  - MATCH,DIRECT\n");

        return sb.toString();
    }

    /**
     * 验证指定 SOCKS5 端口的代理是否可用：通过该端口访问 IP 检测服务获取出口 IP。
     * 依次尝试多个服务，任一成功即返回。失败时记录原因便于排查。
     * @param socksPort mihomo listener 端口
     * @return 出口 IP 信息字符串，失败返回 null
     */
    static String verifyProxyExit(int socksPort) {
        // 重试 3 次，间隔 1.5s：SOCKS5 端口刚监听 / group 节点切换中时可能短暂拒绝连接
        String lastErr = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            String result = verifyProxyExitOnce(socksPort);
            if (result != null) return result;
            // 记录最后一次错误用于日志
            lastErr = lastVerifyErr;
            if (attempt < 2) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { break; }
            }
        }
        LogStore.get().log(TAG, "代理验证全部失败: " + lastErr);
        return null;
    }

    private static String lastVerifyErr = "";

    private static String verifyProxyExitOnce(int socksPort) {
        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress("127.0.0.1", socksPort));
        // 多个 IP 检测服务，依次尝试（ip-api 国内可能被墙，ip.sb/ipinfo.io 备用）
        String[][] services = {
                {"http://ip-api.com/json/?fields=query,country,city,isp", "query"},
                {"https://api.ip.sb/geoip", "ip"},
                {"https://ipinfo.io/json", "ip"}
        };
        for (String[] svc : services) {
            HttpURLConnection c = null;
            try {
                URL url = new URL(svc[0]);
                c = (HttpURLConnection) url.openConnection(proxy);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = c.getResponseCode();
                if (code != 200) {
                    lastVerifyErr = svc[0] + " HTTP " + code;
                    continue;
                }
                try (InputStream in = c.getInputStream()) {
                    byte[] data = readAll(in);
                    JSONObject resp = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    String ip = resp.optString(svc[1], "?");
                    String country = resp.optString("country", resp.optString("country_code", "?"));
                    String city = resp.optString("city", "");
                    String isp = resp.optString("isp", resp.optString("org", ""));
                    String loc = city.isEmpty() ? country : country + " " + city;
                    return ip + " | " + loc + (isp.isEmpty() ? "" : " | " + isp);
                }
            } catch (Throwable t) {
                lastVerifyErr = svc[0] + " " + t.getClass().getSimpleName() + ": " + t.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    /**
     * App 层下载机场订阅到本地文件（借鉴 FlClash：由 App 自己下载，而非依赖内核 http provider）。
     * 用多个常见 clash 客户端 UA 依次尝试，绕过机场对特定 UA 的 403 拦截。
     * 成功写入 outFile（原子写），失败返回 false。
     */
    static boolean downloadSubscription(String url, File outFile, String subName) {
        // 常见 clash 客户端 UA，按优先级尝试。机场通常根据 UA 返回不同格式/拦截。
        String[] uas = new String[]{
                "clash-verge/v2.0.3",
                "ClashMetaForAndroid/2.10.4",
                "clash-verge/v1.7.7",
                "ClashforWindows/0.20.39",
                "mihomo/v1.19.0",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Clash"
        };
        for (String ua : uas) {
            try {
                byte[] data = httpDownload(url, ua, 15000);
                if (data == null || data.length == 0) continue;
                String body = new String(data, StandardCharsets.UTF_8);
                // 基本校验：clash 订阅至少含 proxies 或 proxy-providers
                if (!body.contains("proxies:") && !body.contains("proxy-providers:")
                        && !body.contains("\"proxies\"")) {
                    LogStore.get().log(TAG, "订阅 [" + subName + "] UA=" + ua
                            + " 返回内容非 clash 格式（前80字符: "
                            + body.substring(0, Math.min(80, body.length())).replace("\n", " ")
                            + "），尝试下一个 UA");
                    continue;
                }
                // 原子写
                File tmp = new File(outFile.getAbsolutePath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                    out.flush();
                    try { out.getFD().sync(); } catch (Throwable ignored) {}
                }
                if (!tmp.renameTo(outFile)) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(outFile);
                }
                LogStore.get().log(TAG, "订阅 [" + subName + "] 下载成功（UA=" + ua
                        + "，" + data.length + " 字节）");
                return true;
            } catch (Throwable t) {
                LogStore.get().log(TAG, "订阅 [" + subName + "] UA=" + ua
                        + " 下载失败: " + t.getMessage());
            }
        }
        return false;
    }

    /** HTTP GET 下载，跟随重定向，返回字节数组。 */
    private static byte[] httpDownload(String urlStr, String ua, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "*/*");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code == 200) {
                try (InputStream in = c.getInputStream()) {
                    return readAll(in);
                }
            }
            // 非 200：读错误流用于日志
            InputStream err = c.getErrorStream();
            String errMsg = err != null ? new String(readAll(err), StandardCharsets.UTF_8) : "";
            throw new java.io.IOException("HTTP " + code
                    + (errMsg.length() > 0 ? ": " + errMsg.substring(0, Math.min(120, errMsg.length())) : ""));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ========== ds2api Proxy 注入 ==========

    /**
     * 为每个账号绑定生成 ds2api 的 Proxy 条目和 ProxyID 映射。
     * 返回 JSON: { "proxies": [...], "account_proxy_map": {identifier: proxyId} }
     */
    static JSONObject buildDs2apiProxyInjection(List<AccountBinding> bindings) {
        try {
            JSONObject result = new JSONObject();
            JSONArray proxies = new JSONArray();
            JSONObject accountMap = new JSONObject();
            for (AccountBinding b : bindings) {
                JSONObject proxy = new JSONObject();
                proxy.put("id", b.proxyId);
                proxy.put("name", "mihomo-" + b.accountIdentifier);
                proxy.put("type", "socks5");
                proxy.put("host", "127.0.0.1");
                proxy.put("port", b.socksPort);
                proxies.put(proxy);
                accountMap.put(b.accountIdentifier, b.proxyId);
            }
            result.put("proxies", proxies);
            result.put("account_proxy_map", accountMap);
            return result;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ========== 内部工具 ==========

    /** 解析订阅列表，支持新格式 subscriptions 数组 + 兼容旧版单订阅字段。 */
    private static List<Subscription> parseSubscriptions(JSONObject config) {
        List<Subscription> list = new ArrayList<>();
        JSONArray arr = config.optJSONArray("subscriptions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String url = s.optString("url", "").trim();
                if (url.isEmpty()) continue;
                String name = s.optString("name", "").trim();
                if (name.isEmpty()) name = "订阅" + (i + 1);
                boolean enabled = s.optBoolean("enabled", true);
                if (!enabled) continue;
                list.add(new Subscription(name, url, i));
            }
        }
        // 兼容旧版单订阅字段
        if (list.isEmpty()) {
            String oldUrl = config.optString("subscription_url", "").trim();
            if (!oldUrl.isEmpty()) {
                list.add(new Subscription("默认订阅", oldUrl, 0));
            }
        }
        return list;
    }

    private static List<AccountBinding> parseBindings(JSONObject config, int socks5Base,
                                                       List<Subscription> subs) {
        List<AccountBinding> list = new ArrayList<>();
        JSONArray arr = config.optJSONArray("account_bindings");
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;
            String identifier = b.optString("account_identifier", "").trim();
            if (identifier.isEmpty()) continue;
            List<String> nodeNames = new ArrayList<>();
            JSONArray names = b.optJSONArray("node_names");
            if (names != null) {
                for (int j = 0; j < names.length(); j++) {
                    String n = names.optString(j, "").trim();
                    if (!n.isEmpty()) nodeNames.add(n);
                }
            }
            // 查找该账号指定的订阅
            String subName = b.optString("subscription_name", "").trim();
            Subscription sub = null;
            if (!subName.isEmpty()) {
                for (Subscription s : subs) {
                    if (s.name.equals(subName)) { sub = s; break; }
                }
            }
            // 旧版无 subscription_name：默认用第一个订阅
            if (sub == null && !subs.isEmpty()) sub = subs.get(0);

            int currentIdx = b.optInt("current_node_index", 0);
            list.add(new AccountBinding(identifier, nodeNames, currentIdx,
                    socks5Base + i, i, sub));
        }
        return list;
    }

    private static void readOutput(Process p) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                LogStore.get().raw("[" + TAG + "] " + line);
            }
        } catch (Throwable t) {
            LogStore.get().log(TAG, "日志读取结束: " + t.getMessage());
        }
    }

    private static JSONObject apiGet(String path) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://127.0.0.1:" + apiPort + path);
            c = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(5000);
            c.setRequestMethod("GET");
            if (!apiSecret.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + apiSecret);
            }
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                byte[] data = readAll(in);
                return new JSONObject(new String(data, StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 测试单个节点延迟。
     * 不用 /proxies/{name}/delay（节点名编码 404 问题），改用 healthcheck 机制：
     * 触发所有 provider healthcheck，然后遍历找到该节点的 history。
     */
    static int testNodeDelay(String nodeName) {
        if (!isRunning()) return -1;
        if (nodeName == null || nodeName.isEmpty()) return -1;

        // 触发所有 provider healthcheck
        List<String> providerNames = fetchAllProviderNames();
        for (String pn : providerNames) {
            apiGet("/providers/proxies/" + pn + "/healthcheck");
        }
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        // 遍历 provider 找到该节点，读 history
        for (String pn : providerNames) {
            JSONObject resp = apiGet("/providers/proxies/" + pn);
            if (resp == null) continue;
            JSONArray proxies = resp.optJSONArray("proxies");
            if (proxies == null) continue;
            for (int i = 0; i < proxies.length(); i++) {
                JSONObject node = proxies.optJSONObject(i);
                if (node == null) continue;
                if (nodeName.equals(node.optString("name", ""))) {
                    JSONArray history = node.optJSONArray("history");
                    if (history != null && history.length() > 0) {
                        JSONObject last = history.optJSONObject(history.length() - 1);
                        if (last != null) {
                            int delay = last.optInt("delay", 0);
                            return delay > 0 ? delay : -1;
                        }
                    }
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * 从 /proxies 读取所有节点的当前延迟（来自 health-check 历史）。
     * 返回 节点名 → 延迟ms 的映射，未测过的节点不含 delay 或为 0。
     */
    static java.util.Map<String, Integer> fetchAllDelays() {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        if (!isRunning()) return map;
        JSONObject resp = apiGet("/proxies");
        if (resp == null) return map;
        JSONObject proxies = resp.optJSONObject("proxies");
        if (proxies == null) return map;
        JSONArray names = proxies.names();
        if (names == null) return map;
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            JSONObject p = proxies.optJSONObject(name);
            if (p == null) continue;
            String type = p.optString("type", "");
            // 只关心实际代理节点，跳过策略组（Selector/URLTest/Fallback/LoadBalance/DIRECT/REJECT）
            if ("Selector".equals(type) || "URLTest".equals(type)
                    || "Fallback".equals(type) || "LoadBalance".equals(type)
                    || "DIRECT".equals(type) || "REJECT".equals(type)
                    || "Compatible".equals(type)) {
                continue;
            }
            JSONArray history = p.optJSONArray("history");
            if (history != null && history.length() > 0) {
                JSONObject last = history.optJSONObject(history.length() - 1);
                if (last != null) {
                    int delay = last.optInt("delay", 0);
                    map.put(name, delay);
                }
            }
        }
        return map;
    }

    /**
     * 对策略组内所有节点执行延迟测试。
     * 不使用 /proxies/{name}/delay（节点名含中文/emoji/特殊字符时编码后 mihomo 返回 404），
     * 改用 mihomo provider 原生 healthcheck 机制：
     * 1. GET /providers/proxies/{name}/healthcheck 触发内核批量测所有节点
     * 2. GET /providers/proxies/{name} 读每个节点 history 取延迟
     * 内核自己测自己读，不依赖节点名 URL 编码，100% 可靠。
     * 不做任何过滤（healthcheck 只测 provider 内的真实 proxy，广告伪节点本就不在 provider 里）。
     */
    static java.util.Map<String, Integer> testGroupDelay(String groupName) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        if (!isRunning()) return map;

        // 1. 获取所有 provider 名
        List<String> providerNames = fetchAllProviderNames();
        if (providerNames.isEmpty()) {
            LogStore.get().log(TAG, "组延迟测试 [" + groupName + "] 无可用 provider（订阅未加载）");
            return map;
        }

        // 2. 对每个 provider 触发 healthcheck（内核批量测延迟）
        for (String pn : providerNames) {
            apiGet("/providers/proxies/" + pn + "/healthcheck");
        }

        // 3. 轮询等待 healthcheck 完成：每 800ms 检查一次，
        // 当超过 90% 节点有 history 或达到上限 12s 即结束。
        long deadline = System.currentTimeMillis() + 12000;
        int totalNodes = 0;
        while (System.currentTimeMillis() < deadline) {
            totalNodes = 0;
            int tested = 0;
            for (String pn : providerNames) {
                JSONObject resp = apiGet("/providers/proxies/" + pn);
                if (resp == null) continue;
                JSONArray proxies = resp.optJSONArray("proxies");
                if (proxies == null) continue;
                for (int i = 0; i < proxies.length(); i++) {
                    JSONObject node = proxies.optJSONObject(i);
                    if (node == null) continue;
                    totalNodes++;
                    JSONArray h = node.optJSONArray("history");
                    if (h != null && h.length() > 0) tested++;
                }
            }
            if (totalNodes > 0 && tested >= totalNodes * 0.9) break;
            try { Thread.sleep(800); } catch (InterruptedException ignored) { break; }
        }

        // 4. 读每个 provider 的节点 history 汇总延迟
        int total = 0, ok = 0;
        for (String pn : providerNames) {
            JSONObject resp = apiGet("/providers/proxies/" + pn);
            if (resp == null) continue;
            JSONArray proxies = resp.optJSONArray("proxies");
            if (proxies == null) continue;
            for (int i = 0; i < proxies.length(); i++) {
                JSONObject node = proxies.optJSONObject(i);
                if (node == null) continue;
                String name = node.optString("name", "");
                if (name.isEmpty()) continue;
                total++;
                JSONArray history = node.optJSONArray("history");
                if (history != null && history.length() > 0) {
                    JSONObject last = history.optJSONObject(history.length() - 1);
                    if (last != null) {
                        int delay = last.optInt("delay", 0);
                        if (delay > 0) {
                            map.put(name, delay);
                            ok++;
                        }
                    }
                }
            }
        }
        LogStore.get().log(TAG, "组延迟测试 [" + groupName + "] 完成: "
                + ok + "/" + total + " 个节点可用");
        return map;
    }

    private static boolean apiPut(String path, String body) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://127.0.0.1:" + apiPort + path);
            c = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(5000);
            c.setRequestMethod("PUT");
            c.setRequestProperty("Content-Type", "application/json");
            if (!apiSecret.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + apiSecret);
            }
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            LogStore.get().log(TAG, "API PUT " + path + " 失败: " + t.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** YAML 单引号字符串转义：单引号内只有 ' 需要转义为 ''，反斜杠是字面量。 */
    private static String escapeYamlSingle(String s) {
        return s.replace("'", "''");
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }

    // ========== 数据结构 ==========

    /** 一个机场订阅。 */
    static final class Subscription {
        final String name;       // 用户可读名称
        final String url;        // 订阅 URL
        final int index;         // 在配置中的序号
        /** mihomo proxy-provider 名，用索引保证唯一且稳定。 */
        final String providerName;

        Subscription(String name, String url, int index) {
            this.name = name;
            this.url = url;
            this.index = index;
            this.providerName = "sub-" + index;
        }
    }

    static final class AccountBinding {
        final String accountIdentifier;
        final List<String> nodeNames;
        final int currentNodeIndex;
        final int socksPort;
        final int index;
        final Subscription subscription;  // 该账号使用的订阅
        /** mihomo group 名，用索引保证唯一且稳定。 */
        final String groupName;
        /** ds2api Proxy ID。 */
        final String proxyId;

        AccountBinding(String accountIdentifier, List<String> nodeNames,
                       int currentNodeIndex, int socksPort, int index,
                       Subscription subscription) {
            this.accountIdentifier = accountIdentifier;
            this.nodeNames = nodeNames;
            this.currentNodeIndex = currentNodeIndex;
            this.socksPort = socksPort;
            this.index = index;
            this.subscription = subscription;
            this.groupName = "acc-" + index;
            this.proxyId = "mihomo-" + index;
        }
    }
}
