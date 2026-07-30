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
    // 端口选高位段，避开 Clash/FlClash 默认的 7890/9090，降低与手机代理工具冲突概率
    public static final int DEFAULT_API_PORT = 19090;
    public static final int DEFAULT_SOCKS5_BASE_PORT = 17890;
    private static final int READY_PROBE_TIMEOUT_MS = 30_000;
    private static final int READY_PROBE_INTERVAL_MS = 1000;

    // 进程级状态
    private static volatile Process process;
    private static volatile boolean enabled;
    private static volatile int apiPort = DEFAULT_API_PORT;
    private static volatile int socks5BasePort = DEFAULT_SOCKS5_BASE_PORT;
    private static volatile String apiSecret = "";
    private static volatile File workDir;
    /** 上次退出码：-100 从未启动，-1 启动中/运行中，>=0 已退出。 */
    private static volatile int lastExitCode = -100;
    /** 上次 mihomo fatal 配置错误日志（节点 not found 等），供自动恢复解析。 */
    private static volatile String lastConfigError = null;

    private MihomoManager() {}

    public static boolean isEnabled() { return enabled; }
    public static boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }
    public static int getApiPort() { return apiPort; }
    public static int getSocks5BasePort() { return socks5BasePort; }
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
        lastConfigError = null;  // 清空上次错误，本次启动重新记录

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

        // 端口占用检测：与手机代理工具（Clash/FlClash 等）冲突时自动递增找可用端口
        // 避免启动失败或端口被抢占导致代理失效
        int accountCount = config.optJSONArray("account_bindings") != null
                ? config.optJSONArray("account_bindings").length() : 1;
        int[] adjusted = findAvailablePorts(apiPort, socks5Base, accountCount);
        if (adjusted[0] != apiPort || adjusted[1] != socks5Base) {
            LogStore.get().log(TAG, "检测到端口被占用（可能与手机代理工具冲突），"
                    + "API 端口 " + apiPort + "→" + adjusted[0]
                    + "，SOCKS5 基端口 " + socks5Base + "→" + adjusted[1]);
            apiPort = adjusted[0];
            socks5Base = adjusted[1];
            config.put("api_port", apiPort);
            config.put("socks5_base_port", socks5Base);
        }
        socks5BasePort = socks5Base;

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

        // 3. 保留所有账号绑定，不再因某个订阅失效而丢弃账号。
        //    generateConfigYaml 会自动把每个账号 group 的 use 限定为"该账号涉及
        //    且下载成功"的 provider；全部失效时回退 DIRECT，保证端口能监听、
        //    其他账号不受影响。失效订阅的节点无法切换，但配置和已选节点不丢失。
        List<AccountBinding> okBindings = bindings;
        if (bindings.isEmpty() && !okSubs.isEmpty()) {
            // 完全没有账号绑定时，无需特殊兜底（ds2api 会按无代理运行）
            LogStore.get().log(TAG, "无账号节点绑定，mihomo 仅作代理桥待命");
        }

        // 4. 只用下载成功的订阅生成 config.yaml（避免引用不存在的 provider 文件）
        //    同时用 provider 实际节点名过滤 bindings，避免机场改名/下架后引用不存在
        //    的节点名导致 mihomo 整个 config 加载失败（所有账号代理失效）
        java.util.Map<String, java.util.Set<String>> providerNodeNames =
                parseProviderNodeNames(providersDir, okSubs);
        List<AccountBinding> filteredBindings =
                filterBindingsByProviderNodes(okBindings, providerNodeNames);
        File configFile = new File(workDir, "config.yaml");
        String yaml = generateConfigYaml(okSubs, updateInterval, apiPort, apiSecret, filteredBindings);
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

    /**
     * mihomo 启动失败后尝试自动恢复：解析 fatal 日志提取"not found"的节点名，
     * 从 config 的 account_bindings 里剔除所有引用该节点名的 NodeRef，
     * 重新生成 config.yaml 并重启 mihomo 一次。
     * 仅当 lastConfigError 含 "not found" 时触发，避免无限循环。
     * synchronized：与 start/stop 互斥，避免恢复期间用户点停止导致 process 竞态。
     * @param ctx Context（用于 ensureBinary 释放/复制 libmihomo.so）
     * @param config mihomo 配置 JSON（含 subscriptions + account_bindings）
     * @return true 表示已尝试恢复（调用方应再次 probeReady）
     */
    static synchronized boolean attemptAutoRecover(Context ctx, JSONObject config) {
        String err = lastConfigError;
        if (err == null || !err.contains("not found") || workDir == null) {
            return false;
        }
        // 提取 fatal 日志里的节点名：格式 "...: '节点名' not found" 或 "...: \"节点名\" not found"
        String badNode = extractQuotedBefore(err, "not found");
        if (badNode == null || badNode.isEmpty()) {
            return false;
        }
        LogStore.get().log(TAG, "检测到 mihomo 因节点 [" + badNode + "] not found 致命退出，"
                + "自动从账号绑定中剔除该节点并重试");
        // 从 config 的 account_bindings 剔除该节点名
        // 注意：节点名 JSON 字段是 "name"（与 parseBindings/doSave 一致），不是 "node_name"
        JSONArray bindings = config.optJSONArray("account_bindings");
        if (bindings == null) return false;
        // 备份原 bindings：恢复失败（仍 ready=false）时回滚，避免内存配置渐进式丢节点
        final JSONArray bindingsBackup;
        int removed = 0;
        try {
            bindingsBackup = new JSONArray(bindings.toString());
            for (int i = 0; i < bindings.length(); i++) {
                JSONObject b = bindings.optJSONObject(i);
                if (b == null) continue;
                JSONArray nodes = b.optJSONArray("nodes");
                if (nodes == null) continue;
                JSONArray kept = new JSONArray();
                for (int j = 0; j < nodes.length(); j++) {
                    JSONObject n = nodes.optJSONObject(j);
                    if (n == null) { kept.put(n); continue; }
                    String nn = n.optString("name", "");
                    if (!badNode.equals(nn)) {
                        kept.put(n);
                    } else {
                        removed++;
                    }
                }
                b.put("nodes", kept);
            }
        } catch (org.json.JSONException je) {
            LogStore.get().log(TAG, "剔除节点 [" + badNode + "] 时 JSON 异常: " + je.getMessage());
            return false;
        }
        if (removed == 0) {
            LogStore.get().log(TAG, "未在 account_bindings 找到节点 [" + badNode + "]，放弃恢复");
            return false;
        }
        LogStore.get().log(TAG, "已从 account_bindings 剔除 " + removed + " 个引用 ["
                + badNode + "] 的节点");
        // 重新生成 config.yaml 并重启
        try {
            List<Subscription> subs = parseSubscriptions(config);
            File providersDir = new File(workDir, "providers");
            int socks5Base = config.optInt("socks5_base_port", socks5BasePort);
            int updateInterval = config.optInt("subscription_update_interval", 3600);
            List<AccountBinding> okBindings = parseBindings(config, socks5Base, subs);
            // 只用已存在的 provider 文件（不重新下载，订阅没变）
            List<Subscription> okSubs = new ArrayList<>();
            for (Subscription sub : subs) {
                if (new File(providersDir, sub.providerName + ".yaml").exists()) {
                    okSubs.add(sub);
                }
            }
            java.util.Map<String, java.util.Set<String>> providerNodeNames =
                    parseProviderNodeNames(providersDir, okSubs);
            List<AccountBinding> filteredBindings =
                    filterBindingsByProviderNodes(okBindings, providerNodeNames);
            String yaml = generateConfigYaml(okSubs, updateInterval, apiPort, apiSecret, filteredBindings);
            File configFile = new File(workDir, "config.yaml");
            try (OutputStream out = new FileOutputStream(configFile)) {
                out.write(yaml.getBytes(StandardCharsets.UTF_8));
            }
            // 清空错误标记，重启前先销毁可能存活的旧进程（fatal 后通常已退出，但兜底防端口冲突）
            lastConfigError = null;
            lastExitCode = -1;
            Process stale = process;
            if (stale != null && stale.isAlive()) {
                try { stale.destroy(); stale.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Throwable ignored) {}
                try { if (stale.isAlive()) stale.destroyForcibly(); } catch (Throwable ignored) {}
            }
            process = null;
            // 复用 start 的进程启动逻辑（不重新下载订阅）
            restartProcessOnly(ctx);
            // 探测就绪：失败则回滚内存中的 bindings 剔除，避免下次启动用被修剪的配置
            // （磁盘未被调用方落盘，但内存 mihomoConfig 已被改，必须还原）
            if (!probeReady()) {
                LogStore.get().log(TAG, "自动恢复后仍就绪失败，回滚 account_bindings 改动");
                config.put("account_bindings", bindingsBackup);
                return false;
            }
            return true;
        } catch (Throwable t) {
            LogStore.get().log(TAG, "自动恢复失败: " + t.getMessage());
            // 异常时也回滚，防止内存配置被部分修剪
            try { config.put("account_bindings", bindingsBackup); } catch (Throwable ignored) {}
            return false;
        }
    }

    /** 从 fatal 日志提取 'not found' 前引号内的节点名。 */
    private static String extractQuotedBefore(String line, String marker) {
        int idx = line.indexOf(marker);
        if (idx <= 0) return null;
        // 往前找最近的引号对：'...' 或 "..."
        String before = line.substring(0, idx);
        int q2 = before.lastIndexOf('\'');
        int dq2 = before.lastIndexOf('"');
        int end = Math.max(q2, dq2);
        if (end <= 0) return null;
        char qChar = (q2 > dq2) ? '\'' : '"';
        int start = before.lastIndexOf(qChar, end - 1);
        if (start < 0 || start >= end) return null;
        return before.substring(start + 1, end);
    }

    /** 仅重启 mihomo 子进程（不重新下载订阅/不重算端口），用于自动恢复。 */
    private static void restartProcessOnly(Context ctx) throws Exception {
        File configFile = new File(workDir, "config.yaml");
        String binPath = ensureBinary(ctx);
        ProcessBuilder pb = new ProcessBuilder(binPath, "-d", workDir.getAbsolutePath(),
                "-f", configFile.getAbsolutePath());
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        process = p;
        final Process proc = p;
        lastExitCode = -1;
        LogStore.get().log(TAG, "（自动恢复）进程已重启，等待 API 就绪...");
        Thread reader = new Thread(() -> readOutput(proc), "mihomo-log-reader");
        reader.setDaemon(true);
        reader.start();
        Thread waiter = new Thread(() -> {
            int code;
            try { code = proc.waitFor(); }
            catch (InterruptedException e) { code = -1; }
            LogStore.get().log(TAG, "进程已退出，exit code = " + code);
            if (process == proc) {
                lastExitCode = code;
                process = null;
            }
        }, "mihomo-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /** 停止 mihomo 子进程。同步等待进程退出（最长 3s），避免快速重启时端口冲突。 */
    static synchronized void stop() {
        Process p = process;
        if (p != null) {
            LogStore.get().log(TAG, "正在停止 mihomo...");
            p.destroy();
            // 同步等待进程退出，最长 3 秒；超时后强制 kill
            // 修复 C3：原异步 destroyForcibly 导致 doSave 快速重启时端口仍被占用
            boolean exited = false;
            try {
                exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            if (!exited) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                LogStore.get().log(TAG, "mihomo 3 秒内未退出，已强制终止");
            }
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
     * App 层重新下载所有订阅文件到 providers/ 目录，重新生成 config.yaml 并热重载。
     * 用于"更新订阅"按钮：file provider 只能通过替换文件 + 重生成 config + reload 刷新。
     * 修复 C1：原版只 reload 不重生成 config.yaml，导致 fallback group 的 proxies 列表
     *         引用过期节点名（机场改名/下架后），mihomo reload 失败或静默丢节点。
     * 修复 C5：重生成时用实际拉取的节点列表过滤掉失效节点名，避免引用不存在的节点。
     * @param config mihomo 配置 JSON（含 subscriptions + account_bindings）
     * @return 成功下载的订阅数
     */
    static int redownloadAllSubscriptions(JSONObject config) {
        if (workDir == null) return 0;
        List<Subscription> subs = parseSubscriptions(config);
        File providersDir = new File(workDir, "providers");
        providersDir.mkdirs();
        // 重新下载所有订阅文件
        List<Subscription> okSubs = new ArrayList<>();
        for (Subscription sub : subs) {
            File subFile = new File(providersDir, sub.providerName + ".yaml");
            if (downloadSubscription(sub.url, subFile, sub.name)) {
                okSubs.add(sub);
            }
        }
        LogStore.get().log(TAG, "重新下载订阅: " + okSubs.size() + "/" + subs.size() + " 成功");
        if (okSubs.isEmpty()) {
            LogStore.get().log(TAG, "更新订阅失败：所有订阅下载失败，保持旧配置不变");
            return 0;
        }
        // 重新生成 config.yaml（用实际拉取的节点列表过滤失效节点名，C5）
        try {
            int socks5Base = config.optInt("socks5_base_port", socks5BasePort);
            int updateInterval = config.optInt("subscription_update_interval", 3600);
            List<AccountBinding> bindings = parseBindings(config, socks5Base, subs);
            // 用 provider 实际节点名过滤 bindings（机场改名/下架后旧节点名过期）
            java.util.Map<String, java.util.Set<String>> providerNodeNames =
                    parseProviderNodeNames(providersDir, okSubs);
            List<AccountBinding> filteredBindings =
                    filterBindingsByProviderNodes(bindings, providerNodeNames);
            String yaml = generateConfigYaml(okSubs, updateInterval, apiPort, apiSecret, filteredBindings);
            File configFile = new File(workDir, "config.yaml");
            try (OutputStream out = new FileOutputStream(configFile)) {
                out.write(yaml.getBytes(StandardCharsets.UTF_8));
            }
            LogStore.get().log(TAG, "更新订阅后已重新生成 config.yaml");
        } catch (Throwable t) {
            LogStore.get().log(TAG, "重新生成 config.yaml 失败: " + t.getMessage());
            return okSubs.size();
        }
        // 热重载
        boolean reloaded = reloadConfig();
        if (!reloaded) {
            LogStore.get().log(TAG, "警告: 热重载失败，mihomo 仍跑旧配置");
        }
        return okSubs.size();
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

    /** 切换 group 的当前节点（fallback group 用于固定首选，失败由 fallback 自动兜底）。 */
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
     * 启动/热重载后，尝试把每个账号切换到用户选定的主节点。
     *
     * group 现为 fallback 类型：内核按 proxies 列表顺序自动选第一个可用节点，
     * 当前节点连接失败时自动即时切换到下一个（内核级故障转移）。
     * 这里仍尝试 PUT 切换到用户选的主节点（nodes[0]）以固定首选；
     * 若 mihomo fallback 不支持固定选择，PUT 失败无害——fallback 自动管理。
     * fallback 的 lazy healthcheck 在对话时探活，主节点恢复后自动切回。
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
                    + "fallback 将在节点加载后自动选择（请检查订阅是否有效）");
        }

        // 2. 逐账号尝试切换到用户选的主节点（nodes[0]）
        //    fallback group 自动管理节点选择，这里只尝试固定首选；
        //    mihomo fallback 通常不支持手动 PUT 切换，故只试 1 次不重试，避免浪费启动时间
        for (AccountBinding b : bindings) {
            if (b.nodes.isEmpty()) continue;
            NodeRef primary = b.nodes.get(0);
            if (switchNode(b.groupName, primary.nodeName)) {
                LogStore.get().log(TAG, "账号 " + b.accountIdentifier
                        + " → 主节点: " + primary.nodeName
                        + "（fallback 故障转移已启用，主节点失效自动切备用）");
            } else {
                // PUT 失败不影响功能：fallback 自动按 proxies 顺序选第一个可用节点
                LogStore.get().log(TAG, "账号 " + b.accountIdentifier
                        + " 主节点固定未生效（fallback 将自动选择可用节点）");
            }
        }
    }

    // ========== 配置生成 ==========

    /**
     * 解析已下载的 provider yaml 文件，提取每个订阅的实际节点名集合。
     * 用于过滤 account_bindings 中因机场改名/下架而过期的节点名，避免
     * generateConfigYaml 写入不存在的节点名导致 mihomo 整个 config 加载失败。
     * 非 YAML 格式（base64 share link）的 provider 文件返回空集合（不过滤）。
     */
    private static java.util.Map<String, java.util.Set<String>> parseProviderNodeNames(
            File providersDir, List<Subscription> subs) {
        java.util.Map<String, java.util.Set<String>> map = new java.util.HashMap<>();
        for (Subscription sub : subs) {
            File f = new File(providersDir, sub.providerName + ".yaml");
            map.put(sub.name, parseProxyNamesFromFile(f));
        }
        return map;
    }

    /**
     * 从 mihomo proxy-provider yaml 文件提取节点名（扫描 proxies: 段下的 - name: 行）。
     * 仅解析 clash YAML 格式（FlClash/clash UA 拿到的标准格式，节点名完整可靠）。
     * 非 YAML 格式（base64/share link）返回空集合 → filterBindingsByProviderNodes
     * 走"保留全部"分支，不做过滤（避免本地提取不完整误删有效节点导致代理静默失效）；
     * 此类订阅的过期节点由 attemptAutoRecover 解析 mihomo 真实 fatal 错误后兜底剔除。
     */
    private static java.util.Set<String> parseProxyNamesFromFile(File f) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (!f.exists()) return names;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            boolean inProxies = false;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.equals("proxies:")) { inProxies = true; continue; }
                if (inProxies) {
                    // proxies 段结束：遇到非缩进的非空行且不是列表项
                    if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("-")) {
                        inProxies = false;
                        continue;
                    }
                    if (trimmed.startsWith("- name:")) {
                        String val = trimmed.substring("- name:".length()).trim();
                        // 去引号
                        if (val.length() >= 2
                                && ((val.startsWith("\"") && val.endsWith("\""))
                                || (val.startsWith("'") && val.endsWith("'")))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        if (!val.isEmpty()) names.add(val);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return names;
    }

    /**
     * 用 provider 实际节点名过滤 account_bindings 里的节点引用。
     * 节点名不在 provider 实际列表中的被剔除；provider 文件非 YAML（空集合）则保留（让 mihomo 处理）。
     */
    private static List<AccountBinding> filterBindingsByProviderNodes(
            List<AccountBinding> bindings,
            java.util.Map<String, java.util.Set<String>> providerNodeNames) {
        List<AccountBinding> filtered = new ArrayList<>();
        for (AccountBinding b : bindings) {
            List<NodeRef> validNodes = new ArrayList<>();
            for (NodeRef ref : b.nodes) {
                java.util.Set<String> names = providerNodeNames.get(ref.subscriptionName);
                if (names == null || names.isEmpty()) {
                    // provider 文件非 YAML 或解析失败，保留节点（让 mihomo 自己处理）
                    validNodes.add(ref);
                } else if (names.contains(ref.nodeName)) {
                    validNodes.add(ref);
                }
                // 节点名不在 provider 实际列表 → 过滤掉（机场改名/下架）
            }
            filtered.add(new AccountBinding(b.accountIdentifier, validNodes,
                    b.currentNodeIndex, b.socksPort, b.index));
        }
        return filtered;
    }

    /**
     * 生成 mihomo config.yaml。
     * 支持多订阅：每个订阅一个 proxy-provider；每个账号的 select group use
     * 该账号节点涉及的所有订阅 provider（已下载成功的），从而支持一个账号
     * 跨多个订阅选备用节点。失效订阅的 provider 不在 okSubs 中，会被自动跳过，
     * 不会因引用不存在的 provider 导致配置加载失败。
     */
    static String generateConfigYaml(List<Subscription> subs, int updateInterval,
                                     int apiPort, String secret,
                                     List<AccountBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 自动生成，请勿手动编辑\n");
        sb.append("allow-lan: false\n");
        sb.append("mode: rule\n");
        // log-level: warning 减少日志 IO 耗电（info 级每请求都有日志行）
        sb.append("log-level: warning\n");
        sb.append("external-controller: 127.0.0.1:").append(apiPort).append("\n");
        sb.append("secret: '").append(escapeYamlSingle(secret)).append("'\n\n");

        // 多订阅源
        // 关键：用 type: file，订阅文件由 App 层（downloadSubscription）下载。
        // 借鉴 FlClash：App 层下载能自由控制 UA/重试/超时，绕过机场对 mihomo
        // 内置 http provider 的 UA 拦截（很多机场对 mihomo/clash 默认 UA 返回 403）。
        // provider health-check interval 设 86400（24h）：几乎不自动定时探活省电；
        // 手动"测试延迟"调用 /providers/proxies/{name}/healthcheck 是强制触发，不受 interval 影响。
        sb.append("proxy-providers:\n");
        for (Subscription sub : subs) {
            sb.append("  ").append(sub.providerName).append(":\n");
            sb.append("    type: file\n");
            sb.append("    path: ./providers/").append(sub.providerName).append(".yaml\n");
            sb.append("    health-check:\n");
            sb.append("      enable: true\n");
            sb.append("      url: http://www.gstatic.com/generate_204\n");
            sb.append("      interval: 86400\n\n");
        }

        // 订阅名 → provider 名映射，便于按账号节点涉及的订阅查找 provider
        java.util.Map<String, String> subNameToProvider = new java.util.HashMap<>();
        for (Subscription sub : subs) {
            subNameToProvider.put(sub.name, sub.providerName);
        }

        // 每账号一个 fallback group：
        // - type: fallback 按节点列表顺序使用，当前节点连接失败时自动即时切换到下一个（内核级故障转移）
        // - proxies: 用户选的节点名按优先级（主→备用1→备用2），跨订阅混合
        // - health-check lazy: true 只在 group 有流量经过时才探活（对话时探测，息屏零探活省电）
        // - interval: 600 lazy 模式下每 10 分钟最多探活一次
        // - 主节点恢复后 fallback 自动切回主节点
        // 容错：过滤掉所属订阅下载失败的节点（否则 mihomo 因节点不存在报错导致整个 config 加载失败）；
        //       过滤后无任何可用节点则回退 proxies: [DIRECT]
        sb.append("proxy-groups:\n");
        for (AccountBinding b : bindings) {
            sb.append("  - name: ").append(b.groupName).append("\n");
            sb.append("    type: fallback\n");
            // 只保留所属订阅在 okSubs（已下载成功）中的节点
            List<NodeRef> validNodes = new ArrayList<>();
            for (NodeRef ref : b.nodes) {
                if (subNameToProvider.containsKey(ref.subscriptionName)) {
                    validNodes.add(ref);
                }
            }
            if (!validNodes.isEmpty()) {
                // 用 proxies 明确引用用户选的节点（按优先级），跨订阅混合
                // 节点名来自 provider，mihomo 加载 provider 后节点名全局可见
                sb.append("    proxies: [");
                for (int k = 0; k < validNodes.size(); k++) {
                    if (k > 0) sb.append(", ");
                    sb.append("'").append(escapeYamlSingle(validNodes.get(k).nodeName)).append("'");
                }
                sb.append("]\n");
            } else {
                // 无有效节点：用 DIRECT 兜底，保证配置能加载、端口能监听
                sb.append("    proxies: [DIRECT]\n");
            }
            // lazy healthcheck：只在 group 被使用（有流量）时探活，息屏无对话不探活
            sb.append("    health-check:\n");
            sb.append("      enable: true\n");
            sb.append("      lazy: true\n");
            sb.append("      url: http://www.gstatic.com/generate_204\n");
            sb.append("      interval: 600\n");
            sb.append("\n");
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
        // UA 策略：先试 clash/mihomo 客户端 UA（机场会返回原生 clash YAML，最佳），
        // 再试标准浏览器 UA（部分机场对 clash UA 返回 403，但对浏览器 UA 放行 base64 订阅，
        // mihomo 内核原生支持解析 base64 编码的 share link 订阅）。
        String[] uas = new String[]{
                "clash-verge/v2.2.0",
                "ClashMetaForAndroid/2.10.4",
                "clash.meta",
                "mihomo/v1.19.0",
                "FlClash/0.8.7",
                "clash-nyanpasu/v2.0.0",
                "clash-verge/v1.7.7",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        };
        for (String ua : uas) {
            try {
                byte[] data = httpDownload(url, ua, 15000);
                if (data == null || data.length == 0) continue;
                String body = new String(data, StandardCharsets.UTF_8);
                // 格式校验：mihomo proxy-provider 支持三种格式——
                // 1. clash YAML（含 proxies:/proxy-providers:）
                // 2. base64 编码的 share link 列表（解码后含 ss:// vmess:// trojan:// 等）
                // 3. 明文 share link 列表（直接含 ss:// vmess:// 等）
                if (!isValidSubscriptionContent(body)) {
                    LogStore.get().log(TAG, "订阅 [" + subName + "] UA=" + ua
                            + " 返回内容非订阅格式（前80字符: "
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

    /**
     * 校验订阅内容是否可被 mihomo 解析。支持三种格式：
     * 1. clash YAML（含 proxies: 或 proxy-providers:）
     * 2. base64 编码的 share link 列表（解码后含协议头）
     * 3. 明文 share link 列表（直接含 ss:// vmess:// trojan:// vless:// hysteria2:// 等）
     */
    private static boolean isValidSubscriptionContent(String body) {
        if (body == null || body.isEmpty()) return false;
        // 1. clash YAML 格式
        if (body.contains("proxies:") || body.contains("proxy-providers:")
                || body.contains("\"proxies\"")) {
            return true;
        }
        // 3. 明文 share link（含 hy2:// 短协议头，H1 修复）
        if (body.contains("ss://") || body.contains("vmess://") || body.contains("trojan://")
                || body.contains("vless://") || body.contains("hysteria") || body.contains("hy2://")
                || body.contains("tuic://")) {
            return true;
        }
        // 2. base64 编码：尝试解码看是否含 share link
        // H2 修复：先试标准 decoder，失败再试 URL-safe decoder（部分机场用 -/_ 替代 +//）
        String trimmed = body.trim().replaceAll("\\s+", "");
        String decodedStr = null;
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(trimmed);
            decodedStr = new String(decoded, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 标准 decoder 失败，尝试 URL-safe decoder（容忍 -/_ 和无 padding）
            try {
                byte[] decoded = java.util.Base64.getUrlDecoder().decode(trimmed);
                decodedStr = new String(decoded, StandardCharsets.UTF_8);
            } catch (Throwable ignored2) {
                // 两种 decoder 都失败，非 base64
                return false;
            }
        }
        return decodedStr.contains("ss://") || decodedStr.contains("vmess://")
                || decodedStr.contains("trojan://") || decodedStr.contains("vless://")
                || decodedStr.contains("hysteria") || decodedStr.contains("hy2://")
                || decodedStr.contains("tuic://");
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

    /**
     * 把 mihomo 的账号 SOCKS5 端口作为 Proxy 条目注入 ds2api 的 config.json，
     * 并设置各账号的 proxy_id。原子写入，避免与 UI 保存并发时写坏文件。
     *
     * 用于：
     * - ServerService 启动 mihomo 后注入代理（首次启动）
     * - ProxyConfigActivity.doSave 重启 mihomo 后重新同步 config.json
     *   修复 C4：mihomo 重启可能因端口冲突递增调整 SOCKS5 端口，旧 config.json
     *   里的代理端口会失效，必须重新注入。否则 ds2api 仍指向旧端口 → ECONNREFUSED。
     *
     * @param configFile ds2api config.json
     * @param mihomoConfig mihomo 配置 JSON（含 account_bindings + socks5_base_port）
     * @return 注入的代理条目数；0 表示无绑定或写入失败
     */
    static int injectProxiesIntoConfig(File configFile, JSONObject mihomoConfig) {
        try {
            if (!configFile.exists()) return 0;
            int socks5Base = mihomoConfig.optInt("socks5_base_port", socks5BasePort);
            JSONArray bindings = mihomoConfig.optJSONArray("account_bindings");
            if (bindings == null || bindings.length() == 0) {
                LogStore.get().log(TAG, "无账号节点绑定，跳过 Proxy 注入");
                // 仍清理旧条目，避免残留死代理指向已不监听的端口
                clearProxiesFromConfig(configFile);
                return 0;
            }
            byte[] data = readAll(new java.io.FileInputStream(configFile));
            JSONObject cfg = new JSONObject(new String(data, StandardCharsets.UTF_8));

            // 移除旧的 mihomo-* 代理（避免重复注入堆积）
            JSONArray proxies = cfg.optJSONArray("proxies");
            if (proxies == null) proxies = new JSONArray();
            JSONArray cleaned = new JSONArray();
            for (int i = 0; i < proxies.length(); i++) {
                JSONObject p = proxies.optJSONObject(i);
                if (p != null && !p.optString("id", "").startsWith("mihomo-")) {
                    cleaned.put(p);
                }
            }
            proxies = cleaned;

            // 为每个绑定生成 Proxy 条目
            JSONObject accountProxyMap = new JSONObject();
            for (int i = 0; i < bindings.length(); i++) {
                JSONObject b = bindings.optJSONObject(i);
                if (b == null) continue;
                String identifier = b.optString("account_identifier", "").trim();
                if (identifier.isEmpty()) continue;
                String proxyId = "mihomo-" + i;
                int port = socks5Base + i;
                JSONObject proxy = new JSONObject();
                proxy.put("id", proxyId);
                proxy.put("name", "mihomo-" + identifier);
                proxy.put("type", "socks5");
                proxy.put("host", "127.0.0.1");
                proxy.put("port", port);
                proxies.put(proxy);
                accountProxyMap.put(identifier, proxyId);
                LogStore.get().log(TAG, "注入 Proxy: " + proxyId + " → 127.0.0.1:" + port
                        + " (账号: " + identifier + ")");
            }
            cfg.put("proxies", proxies);

            // 设置 accounts 的 proxy_id
            JSONArray accounts = cfg.optJSONArray("accounts");
            if (accounts != null) {
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject acc = accounts.optJSONObject(i);
                    if (acc == null) continue;
                    String email = acc.optString("email", "").trim();
                    String mobile = acc.optString("mobile", "").trim();
                    String name = acc.optString("name", "").trim();
                    String identifier = !email.isEmpty() ? email
                            : (!mobile.isEmpty() ? mobile : name);
                    if (accountProxyMap.has(identifier)) {
                        acc.put("proxy_id", accountProxyMap.getString(identifier));
                    }
                }
            }
            atomicWrite(configFile, cfg.toString(2).getBytes(StandardCharsets.UTF_8));
            LogStore.get().log(TAG, "Proxy 注入完成，已写回 config.json");
            return bindings.length();
        } catch (Throwable t) {
            LogStore.get().log(TAG, "Proxy 注入失败: " + t.getMessage());
            return 0;
        }
    }

    /**
     * 从 config.json 移除所有 mihomo-* 代理条目，并清空 accounts 里对应的 proxy_id。
     * 用于 mihomo 启动失败/未就绪时降级（修复 M3/C6）：
     * 避免 ds2api 指向已死的 SOCKS5 端口导致全部请求 ECONNREFUSED。
     */
    static void clearProxiesFromConfig(File configFile) {
        try {
            if (!configFile.exists()) return;
            byte[] data = readAll(new java.io.FileInputStream(configFile));
            JSONObject cfg = new JSONObject(new String(data, StandardCharsets.UTF_8));

            JSONArray proxies = cfg.optJSONArray("proxies");
            if (proxies != null) {
                JSONArray cleaned = new JSONArray();
                for (int i = 0; i < proxies.length(); i++) {
                    JSONObject p = proxies.optJSONObject(i);
                    if (p != null && !p.optString("id", "").startsWith("mihomo-")) {
                        cleaned.put(p);
                    }
                }
                cfg.put("proxies", cleaned);
            }
            JSONArray accounts = cfg.optJSONArray("accounts");
            if (accounts != null) {
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject acc = accounts.optJSONObject(i);
                    if (acc == null) continue;
                    String pid = acc.optString("proxy_id", "");
                    if (pid.startsWith("mihomo-")) {
                        acc.remove("proxy_id");
                    }
                }
            }
            atomicWrite(configFile, cfg.toString(2).getBytes(StandardCharsets.UTF_8));
            LogStore.get().log(TAG, "已清理 config.json 中的 mihomo 代理条目");
        } catch (Throwable t) {
            LogStore.get().log(TAG, "清理 mihomo 代理条目失败: " + t.getMessage());
        }
    }

    /** 原子写入：临时文件 + fsync + rename，避免并发写或进程被杀导致配置损坏。 */
    private static void atomicWrite(File target, byte[] data) throws Exception {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            try { out.getFD().sync(); } catch (Throwable ignored) {}
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(target);
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

            List<NodeRef> nodes = new ArrayList<>();
            // 新格式：nodes 数组，每项 {subscription, name}，支持跨订阅备用
            JSONArray nodesArr = b.optJSONArray("nodes");
            if (nodesArr != null) {
                for (int j = 0; j < nodesArr.length(); j++) {
                    JSONObject n = nodesArr.optJSONObject(j);
                    if (n == null) continue;
                    String subName = n.optString("subscription", "").trim();
                    String nodeName = n.optString("name", "").trim();
                    if (!nodeName.isEmpty()) nodes.add(new NodeRef(subName, nodeName));
                }
            } else {
                // 兼容旧格式：node_names[] + subscription_name（单订阅）
                String subName = b.optString("subscription_name", "").trim();
                JSONArray names = b.optJSONArray("node_names");
                if (names != null) {
                    for (int j = 0; j < names.length(); j++) {
                        String n = names.optString(j, "").trim();
                        if (!n.isEmpty()) nodes.add(new NodeRef(subName, n));
                    }
                }
            }

            int currentIdx = b.optInt("current_node_index", 0);
            list.add(new AccountBinding(identifier, nodes, currentIdx,
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
                // 检测 fatal 配置错误（节点 not found 等），供 probeReady 失败后自动恢复
                if (line.contains("level=fatal") && line.contains("Parse config error")) {
                    lastConfigError = line;
                }
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
            if (code != 200) {
                // 非 200 记日志便于排障（401=secret 错，404=provider/group 不存在）
                if (code == 401 || code == 403) {
                    LogStore.get().log(TAG, "apiGet " + path + " 鉴权失败(" + code
                            + ")，可能 secret 不一致");
                }
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                byte[] data = readAll(in);
                return new JSONObject(new String(data, StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            // H3 修复：网络异常打日志，避免静默失败导致排障困难
            // 只对非 ConnectException（连接拒绝，mihomo 未运行时的正常情况）打日志
            if (!(t instanceof java.net.ConnectException)) {
                LogStore.get().log(TAG, "apiGet " + path + " 异常: " + t.getMessage());
            }
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
     *
     * @param groupName    策略组名（仅用于日志）
     * @param providerName 该组绑定的订阅 provider 名（sub-{index}），只测此 provider 的节点；
     *                     为 null/空则回退测所有 provider（向后兼容）
     */
    static java.util.Map<String, Integer> testGroupDelay(String groupName, String providerName) {
        List<String> providerNames;
        if (providerName != null && !providerName.isEmpty()) {
            providerNames = new ArrayList<>();
            providerNames.add(providerName);
        } else {
            providerNames = fetchAllProviderNames();
        }
        return testProvidersDelay(groupName, providerNames);
    }

    /**
     * 对多个订阅 provider 的所有节点批量执行延迟测试（一个账号跨多订阅选备用时用）。
     * 只测传入的 provider 列表，不会测到账号未涉及的机场节点。
     */
    static java.util.Map<String, Integer> testProvidersDelay(String groupName,
                                                              List<String> providerNames) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        if (!isRunning()) return map;
        if (providerNames == null || providerNames.isEmpty()) {
            LogStore.get().log(TAG, "组延迟测试 [" + groupName + "] 无可用 provider（订阅未加载）");
            return map;
        }

        // 1. 对每个 provider 触发 healthcheck（内核批量测延迟）
        for (String pn : providerNames) {
            apiGet("/providers/proxies/" + pn + "/healthcheck");
        }

        // 2. 轮询等待 healthcheck 完成：每 800ms 检查一次，
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

        // 3. 读每个 provider 的节点 history 汇总延迟
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

    /**
     * 检测 API 端口和 N 个 SOCKS5 端口是否可用，被占用则整体递增找可用段。
     * @return {apiPort, socks5Base} 调整后的端口
     */
    private static int[] findAvailablePorts(int apiPort, int socks5Base, int accountCount) {
        // 最多尝试 50 次递增，避免无限循环
        for (int i = 0; i < 50; i++) {
            int tryApi = apiPort + i;
            int trySocks = socks5Base + i;
            boolean allFree = isPortAvailable(tryApi);
            if (allFree) {
                for (int j = 0; j < accountCount; j++) {
                    if (!isPortAvailable(trySocks + j)) {
                        allFree = false;
                        break;
                    }
                }
            }
            if (allFree) {
                return new int[]{tryApi, trySocks};
            }
        }
        // 兜底：返回原值，让 mihomo 自己报错
        return new int[]{apiPort, socks5Base};
    }

    /** 检测本地端口是否可绑定（未被占用）。 */
    private static boolean isPortAvailable(int port) {
        java.net.ServerSocket ss = null;
        try {
            ss = new java.net.ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1"));
            ss.setReuseAddress(true);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (ss != null) {
                try { ss.close(); } catch (Throwable ignored) {}
            }
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

    /** 一个节点引用：节点名 + 它所属的订阅名。支持一个账号跨多个订阅选备用节点。 */
    static final class NodeRef {
        final String subscriptionName;  // 订阅名（与 Subscription.name 对应）
        final String nodeName;          // 节点名
        NodeRef(String subscriptionName, String nodeName) {
            this.subscriptionName = subscriptionName == null ? "" : subscriptionName;
            this.nodeName = nodeName == null ? "" : nodeName;
        }
    }

    static final class AccountBinding {
        final String accountIdentifier;
        /** 该账号的节点列表（顺序即优先级），每个节点可来自不同订阅。 */
        final List<NodeRef> nodes;
        final int currentNodeIndex;
        final int socksPort;
        final int index;
        /** 该账号涉及的所有订阅名（从 nodes 自动推导），用于生成 group 的 use 列表。 */
        final java.util.Set<String> subscriptionNames;
        /** mihomo group 名，用索引保证唯一且稳定。 */
        final String groupName;
        /** ds2api Proxy ID。 */
        final String proxyId;

        AccountBinding(String accountIdentifier, List<NodeRef> nodes,
                       int currentNodeIndex, int socksPort, int index) {
            this.accountIdentifier = accountIdentifier;
            this.nodes = nodes == null ? new ArrayList<>() : nodes;
            this.currentNodeIndex = currentNodeIndex;
            this.socksPort = socksPort;
            this.index = index;
            this.subscriptionNames = new java.util.LinkedHashSet<>();
            for (NodeRef n : this.nodes) {
                if (!n.subscriptionName.isEmpty()) this.subscriptionNames.add(n.subscriptionName);
            }
            this.groupName = "acc-" + index;
            this.proxyId = "mihomo-" + index;
        }
    }
}
