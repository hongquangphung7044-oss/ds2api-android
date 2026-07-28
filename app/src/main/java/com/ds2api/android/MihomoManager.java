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

    private MihomoManager() {}

    public static boolean isEnabled() { return enabled; }
    public static boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }
    public static int getApiPort() { return apiPort; }
    public static String getApiSecret() { return apiSecret; }

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
        File providersDir = new File(workDir, "providers");
        providersDir.mkdirs();

        // 解析配置（只要有订阅地址即可启动，不强制要求 enabled=true）
        enabled = true;
        String subscriptionUrl = config.optString("subscription_url", "").trim();
        if (subscriptionUrl.isEmpty()) {
            throw new IllegalStateException("未配置订阅地址");
        }
        int updateInterval = config.optInt("subscription_update_interval", 3600);
        apiPort = config.optInt("api_port", DEFAULT_API_PORT);
        apiSecret = config.optString("api_secret", "").trim();
        if (apiSecret.isEmpty()) {
            apiSecret = UUID.randomUUID().toString().replace("-", "");
            config.put("api_secret", apiSecret);
        }
        int socks5Base = config.optInt("socks5_base_port", DEFAULT_SOCKS5_BASE_PORT);

        // 读取账号绑定
        List<AccountBinding> bindings = parseBindings(config, socks5Base);

        // 生成 mihomo config.yaml
        File configFile = new File(workDir, "config.yaml");
        String yaml = generateConfigYaml(subscriptionUrl, updateInterval, apiPort,
                apiSecret, bindings);
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
            process = null;
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
        enabled = false;
    }

    // ========== mihomo API 封装 ==========

    /** 获取订阅中的节点名列表。 */
    static List<String> fetchNodeList() {
        List<String> names = new ArrayList<>();
        JSONObject resp = apiGet("/providers/proxies/airport");
        if (resp == null) return names;
        JSONArray proxies = resp.optJSONArray("proxies");
        if (proxies == null) return names;
        for (int i = 0; i < proxies.length(); i++) {
            JSONObject node = proxies.optJSONObject(i);
            if (node != null) {
                String name = node.optString("name", "");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 强制刷新订阅。 */
    static boolean refreshSubscription() {
        return apiPut("/providers/proxies/airport", null);
    }

    /** 切换 selector group 的当前节点。 */
    static boolean switchNode(String groupName, String nodeName) {
        try {
            JSONObject body = new JSONObject();
            body.put("name", nodeName);
            return apiPut("/proxies/" + groupName, body.toString());
        } catch (Throwable t) {
            LogStore.get().log(TAG, "切换节点失败: " + t.getMessage());
            return false;
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

    // ========== 配置生成 ==========

    /**
     * 生成 mihomo config.yaml。
     * 使用 listeners 多入站端口隔离，每账号一个 fallback group。
     */
    static String generateConfigYaml(String subscriptionUrl, int updateInterval,
                                     int apiPort, String secret,
                                     List<AccountBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 自动生成，请勿手动编辑\n");
        sb.append("allow-lan: false\n");
        sb.append("mode: rule\n");
        sb.append("log-level: info\n");
        sb.append("external-controller: 127.0.0.1:").append(apiPort).append("\n");
        sb.append("secret: \"").append(escapeYaml(secret)).append("\"\n\n");

        // 订阅源
        sb.append("proxy-providers:\n");
        sb.append("  airport:\n");
        sb.append("    type: http\n");
        sb.append("    url: \"").append(escapeYaml(subscriptionUrl)).append("\"\n");
        sb.append("    interval: ").append(updateInterval).append("\n");
        sb.append("    path: ./providers/airport.yaml\n");
        sb.append("    health-check:\n");
        sb.append("      enable: true\n");
        sb.append("      url: https://chat.deepseek.com/\n");
        sb.append("      interval: 300\n\n");

        // 每账号一个 fallback group
        sb.append("proxy-groups:\n");
        for (AccountBinding b : bindings) {
            sb.append("  - name: ").append(b.groupName).append("\n");
            sb.append("    type: fallback\n");
            sb.append("    use: [airport]\n");
            sb.append("    filter: \"").append(b.filterRegex()).append("\"\n");
            sb.append("    url: https://chat.deepseek.com/\n");
            sb.append("    interval: 300\n\n");
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

        // 基本规则：未匹配的流量直连（listeners 已隔离账号流量，这里兜底）
        sb.append("\nrules:\n");
        sb.append("  - MATCH,DIRECT\n");

        return sb.toString();
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

    private static List<AccountBinding> parseBindings(JSONObject config, int socks5Base) {
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
            int currentIdx = b.optInt("current_node_index", 0);
            list.add(new AccountBinding(identifier, nodeNames, currentIdx,
                    socks5Base + i, i));
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

    private static String escapeYaml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    // ========== 账号绑定数据结构 ==========

    static final class AccountBinding {
        final String accountIdentifier;
        final List<String> nodeNames;
        final int currentNodeIndex;
        final int socksPort;
        final int index;
        /** mihomo group 名，用索引保证唯一且稳定。 */
        final String groupName;
        /** ds2api Proxy ID。 */
        final String proxyId;

        AccountBinding(String accountIdentifier, List<String> nodeNames,
                       int currentNodeIndex, int socksPort, int index) {
            this.accountIdentifier = accountIdentifier;
            this.nodeNames = nodeNames;
            this.currentNodeIndex = currentNodeIndex;
            this.socksPort = socksPort;
            this.index = index;
            this.groupName = "acc-" + index;
            this.proxyId = "mihomo-" + index;
        }

        /** fallback group 的 filter 正则，匹配用户选定的节点名。 */
        String filterRegex() {
            if (nodeNames.isEmpty()) return ".*";
            StringBuilder sb = new StringBuilder("^(");
            for (int i = 0; i < nodeNames.size(); i++) {
                if (i > 0) sb.append("|");
                sb.append(java.util.regex.Pattern.quote(nodeNames.get(i)));
            }
            sb.append(")$");
            return sb.toString();
        }
    }
}
