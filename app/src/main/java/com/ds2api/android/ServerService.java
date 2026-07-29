package com.ds2api.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 前台服务：以子进程方式运行打包在 jniLibs 中的 ds2api Go 服务端，
 * 捕获其 stdout/stderr 到 LogStore，供界面实时展示。
 */
public class ServerService extends Service {

    public static final String ACTION_START = "com.ds2api.android.START";
    public static final String ACTION_STOP = "com.ds2api.android.STOP";

    public static final int PORT = 5001;
    private static final String CHANNEL_ID = "ds2api_server";
    private static final int NOTIF_ID = 1001;
    private static final String PREFS = "ds2api";

    public enum State {STOPPED, STARTING, RUNNING}

    // 进程级单例状态（与 Activity 同进程，直接静态共享）
    private static volatile State state = State.STOPPED;
    private static volatile Process process;
    private static volatile long startedAt = 0L;
    private static volatile String lastError = "";

    private PowerManager.WakeLock wakeLock;

    public static State getState() {
        return state;
    }

    public static long getStartedAt() {
        return startedAt;
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean isRunning() {
        Process p = process;
        return state == State.RUNNING && p != null && p.isAlive();
    }

    public static String adminKey(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String key = sp.getString("admin_key", null);
        if (key == null || key.trim().isEmpty()) {
            key = UUID.randomUUID().toString().replace("-", "");
            sp.edit().putString("admin_key", key).apply();
        }
        return key;
    }

    public static void startServer(Context ctx) {
        Intent i = new Intent(ctx, ServerService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stopServer(Context ctx) {
        Intent i = new Intent(ctx, ServerService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopInternal("用户手动停止");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startForegroundWithNotification();
            if (isRunning() || state == State.STARTING) {
                LogStore.get().log("APP", "服务已在运行，忽略重复的启动请求");
            } else {
                startInternal();
            }
            return START_STICKY;
        }
        // 系统回收后重启但进程已不在：直接停掉，避免“假前台”
        if (!isRunning()) {
            stopSelf();
        }
        return START_STICKY;
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "DS2API 服务", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("DS2API 服务运行中")
                .setContentText("本地端口 " + PORT + "，点按返回应用")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startInternal() {
        state = State.STARTING;
        lastError = "";
        LogStore.get().log("APP", "========== 正在启动 ds2api 服务 ==========");
        new Thread(this::doStart, "ds2api-starter").start();
    }

    private void doStart() {
        try {
            File filesDir = getFilesDir();
            File configFile = new File(filesDir, "config.json");
            ensureConfig(configFile);
            File staticDir = new File(filesDir, "static/admin");
            ensureWebUi(staticDir);

            // 启动 mihomo 代理桥（如果已启用），并注入 Proxy 到 config.json
            startMihomoIfNeeded(configFile);

            String binPath = getApplicationInfo().nativeLibraryDir + "/libds2api.so";
            File bin = new File(binPath);
            if (!bin.exists()) {
                throw new IllegalStateException("未找到原生服务端程序: " + binPath
                        + "（当前 CPU 架构可能不受支持，本应用仅内置 arm64-v8a）");
            }
            try {
                Os.chmod(binPath, 0755);
            } catch (Throwable t) {
                LogStore.get().log("APP", "chmod 失败（通常可忽略）: " + t.getMessage());
            }
            if (!bin.canExecute()) {
                // 部分机型不允许直接执行 nativeLibraryDir，复制到私有目录兜底
                File local = new File(filesDir, "bin/libds2api");
                local.getParentFile().mkdirs();
                try (InputStream in = new java.io.FileInputStream(bin);
                     OutputStream out = new FileOutputStream(local)) {
                    copy(in, out);
                }
                Os.chmod(local.getAbsolutePath(), 0755);
                binPath = local.getAbsolutePath();
                LogStore.get().log("APP", "nativeLibraryDir 不可执行，已改用 " + binPath);
            }

            ProcessBuilder pb = new ProcessBuilder(binPath);
            pb.directory(filesDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PORT", String.valueOf(PORT));
            env.put("LOG_LEVEL", "INFO");
            env.put("HOME", filesDir.getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("DS2API_CONFIG_PATH", configFile.getAbsolutePath());
            env.put("DS2API_STATIC_ADMIN_DIR", staticDir.getAbsolutePath());
            env.put("DS2API_AUTO_BUILD_WEBUI", "0");
            env.put("DS2API_ADMIN_KEY", adminKey(this));

            LogStore.get().log("APP", "工作目录: " + filesDir.getAbsolutePath());
            LogStore.get().log("APP", "配置文件: " + configFile.getAbsolutePath());
            LogStore.get().log("APP", "监听端口: " + PORT);

            Process p;
            try {
                p = pb.start();
            } catch (java.io.IOException ioe) {
                // 个别 ROM 禁止直接执行 nativeLibraryDir，复制到私有目录重试
                String msg = String.valueOf(ioe.getMessage());
                if (!msg.contains("Permission denied") && !msg.contains("error=13")) {
                    throw ioe;
                }
                LogStore.get().log("APP", "直接执行被拒绝（" + msg + "），复制到私有目录后重试");
                File local = new File(filesDir, "bin/libds2api");
                local.getParentFile().mkdirs();
                try (InputStream in = new java.io.FileInputStream(bin);
                     OutputStream out = new FileOutputStream(local)) {
                    copy(in, out);
                }
                Os.chmod(local.getAbsolutePath(), 0755);
                pb.command(local.getAbsolutePath());
                p = pb.start();
            }
            final Process proc = p;
            process = proc;
            startedAt = System.currentTimeMillis();
            state = State.RUNNING;
            acquireWakeLock();
            LogStore.get().log("APP", "进程已启动，等待服务就绪...");

            // 日志读取线程
            Thread reader = new Thread(() -> readOutput(proc), "ds2api-log-reader");
            reader.setDaemon(true);
            reader.start();

            // 就绪探测线程
            Thread probe = new Thread(this::probeReady, "ds2api-ready-probe");
            probe.setDaemon(true);
            probe.start();

            // 退出监听线程
            Thread waiter = new Thread(() -> {
                int code;
                try {
                    code = proc.waitFor();
                } catch (InterruptedException e) {
                    code = -1;
                }
                LogStore.get().log("APP", "服务进程已退出，exit code = " + code);
                state = State.STOPPED;
                process = null;
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            }, "ds2api-waiter");
            waiter.setDaemon(true);
            waiter.start();
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
            LogStore.get().log("APP", "启动失败: " + t);
            state = State.STOPPED;
            process = null;
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    private void readOutput(Process p) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                LogStore.get().raw(line);
            }
        } catch (Throwable t) {
            LogStore.get().log("APP", "日志读取结束: " + t.getMessage());
        }
    }

    private void probeReady() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) {
                return;
            }
            try {
                // 必须绕过系统代理/VPN，否则开启代理时 127.0.0.1 探测会失败造成误报
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + PORT + "/v1/models")
                        .openConnection(java.net.Proxy.NO_PROXY);
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                int code = c.getResponseCode();
                c.disconnect();
                if (code > 0) {
                    LogStore.get().log("APP", "服务就绪 ✓  管理界面: http://127.0.0.1:" + PORT + "/admin/"
                            + "  API: http://127.0.0.1:" + PORT + "/v1");
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        LogStore.get().log("APP", "警告: 30 秒内未检测到服务就绪，请检查上方日志排查");
    }

    private void stopInternal(String reason) {
        LogStore.get().log("APP", "========== 停止服务: " + reason + " ==========");
        // 同时停止 mihomo 代理桥
        MihomoManager.stop();
        Process p = process;
        if (p != null) {
            p.destroy();
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
                try {
                    p.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }, "ds2api-killer").start();
        } else {
            state = State.STOPPED;
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    private void ensureConfig(File configFile) throws Exception {
        if (configFile.exists()) {
            LogStore.get().log("APP", "使用已有配置 config.json（可在管理界面修改）");
            return;
        }
        try (InputStream in = getAssets().open("config.default.json");
             OutputStream out = new FileOutputStream(configFile)) {
            copy(in, out);
        }
        LogStore.get().log("APP", "首次运行：已写入默认配置 config.json");
    }

    /**
     * 从 mihomo_config.json 读取 mihomo 配置，如果 enabled=true 则启动 mihomo 子进程
     * 并注入 SOCKS5 Proxy 条目到 config.json，设置各账号的 proxy_id。
     *
     * mihomo 配置存独立文件，避免被 ds2api Go 服务端写回 config.json 时覆盖丢失。
     */
    private void startMihomoIfNeeded(File configFile) throws Exception {
        File mihomoFile = new File(getFilesDir(), "mihomo_config.json");
        JSONObject mihomo = null;
        if (mihomoFile.exists()) {
            byte[] data = readAll(new java.io.FileInputStream(mihomoFile));
            mihomo = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } else {
            // 兼容迁移：旧版本 mihomo 配置存在 config.json 里
            byte[] data = readAll(new java.io.FileInputStream(configFile));
            JSONObject cfg = new JSONObject(new String(data, StandardCharsets.UTF_8));
            mihomo = cfg.optJSONObject("mihomo");
        }
        if (mihomo == null || !mihomo.optBoolean("enabled", false)) {
            LogStore.get().log("APP", "mihomo 代理桥未启用，跳过");
            return;
        }
        // 检查是否有可用的订阅（支持新格式 subscriptions 数组 + 旧格式 subscription_url）
        boolean hasSub = false;
        JSONArray subs = mihomo.optJSONArray("subscriptions");
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s != null && s.optBoolean("enabled", true)
                        && !s.optString("url", "").trim().isEmpty()) {
                    hasSub = true;
                    break;
                }
            }
        }
        if (!hasSub && !mihomo.optString("subscription_url", "").trim().isEmpty()) {
            hasSub = true;
        }
        if (!hasSub) {
            LogStore.get().log("APP", "mihomo 已启用但未配置订阅地址，跳过");
            return;
        }

        LogStore.get().log("APP", "========== 启动 mihomo 代理桥 ==========");
        File mihomoWorkDir = new File(getFilesDir(), "mihomo");
        MihomoManager.start(this, mihomoWorkDir, mihomo);

        // 等待 mihomo API 就绪
        if (!MihomoManager.probeReady()) {
            LogStore.get().log("APP", "警告: mihomo API 未就绪，代理可能不可用");
        } else {
            // 把每个账号的 selector 切换到用户选定的主节点
            // applyNodeSelection 内部会等待 provider 节点加载完成，无需额外 sleep
            MihomoManager.applyNodeSelection(mihomo);
        }

        // 注入 Proxy 条目到 config.json
        byte[] data = readAll(new java.io.FileInputStream(configFile));
        JSONObject cfg = new JSONObject(new String(data, StandardCharsets.UTF_8));
        injectProxies(cfg, configFile, mihomo);
    }

    /**
     * 为每个 mihomo 账号绑定生成 ds2api Proxy 条目，设置 accounts 的 proxy_id，
     * 写回 config.json。同时移除旧的 mihomo-* 代理条目避免堆积。
     */
    private void injectProxies(JSONObject cfg, File configFile, JSONObject mihomo) throws Exception {
        int socks5Base = mihomo.optInt("socks5_base_port", MihomoManager.DEFAULT_SOCKS5_BASE_PORT);
        JSONArray bindings = mihomo.optJSONArray("account_bindings");
        if (bindings == null || bindings.length() == 0) {
            LogStore.get().log("APP", "无账号节点绑定，跳过 Proxy 注入");
            return;
        }

        // 读取现有 proxies 数组（可能不存在）
        JSONArray proxies = cfg.optJSONArray("proxies");
        if (proxies == null) proxies = new JSONArray();

        // 移除旧的 mihomo-* 代理（避免重复注入时堆积）
        JSONArray cleaned = new JSONArray();
        for (int i = 0; i < proxies.length(); i++) {
            JSONObject p = proxies.optJSONObject(i);
            if (p != null) {
                String id = p.optString("id", "");
                if (!id.startsWith("mihomo-")) {
                    cleaned.put(p);
                }
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
            LogStore.get().log("APP", "注入 Proxy: " + proxyId + " → 127.0.0.1:" + port
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
                String identifier = !email.isEmpty() ? email : (!mobile.isEmpty() ? mobile : name);
                if (accountProxyMap.has(identifier)) {
                    acc.put("proxy_id", accountProxyMap.getString(identifier));
                }
            }
        }

        // 写回 config.json（原子写：临时文件 + rename，避免与 UI 保存并发时写坏文件）
        atomicWrite(configFile, cfg.toString(2).getBytes(StandardCharsets.UTF_8));
        LogStore.get().log("APP", "Proxy 注入完成，已写回 config.json");
        // 验证第一个账号的代理出口 IP（确认链路可用）。
        // 延迟 + 重试：mihomo 刚启动时节点可能还没就绪，立即验证会误报失败。
        if (bindings.length() > 0) {
            final int firstPort = socks5Base;
            new Thread(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
                String exit = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    exit = MihomoManager.verifyProxyExit(firstPort);
                    if (exit != null) break;
                    LogStore.get().log("APP", "代理验证第 " + attempt + " 次失败，"
                            + (attempt < 3 ? "3秒后重试..." : "已重试3次仍失败"));
                    if (attempt < 3) {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
                    }
                }
                if (exit != null) {
                    LogStore.get().log("APP", "代理验证成功 ✓ 出口: " + exit);
                } else {
                    LogStore.get().log("APP", "代理验证失败 ✗ 端口 " + firstPort
                            + " 无法访问外网（请检查节点是否可用）");
                }
            }, "proxy-verify").start();
        }
    }

    /** 释放内置 WebUI 静态资源；版本变化时重新释放。 */
    private void ensureWebUi(File staticDir) throws Exception {
        File stamp = new File(getFilesDir(), ".webui_version");
        String current = String.valueOf(BuildConfig.VERSION_CODE);
        if (staticDir.isDirectory() && stamp.isFile()) {
            String v = new String(readAll(new java.io.FileInputStream(stamp)), StandardCharsets.UTF_8).trim();
            if (current.equals(v)) {
                return;
            }
        }
        deleteRecursively(staticDir);
        staticDir.mkdirs();
        copyAssetTree(getAssets(), "webui", staticDir);
        try (FileOutputStream out = new FileOutputStream(stamp)) {
            out.write(current.getBytes(StandardCharsets.UTF_8));
        }
        LogStore.get().log("APP", "已释放内置管理界面资源到 " + staticDir.getAbsolutePath());
    }

    private static void copyAssetTree(AssetManager am, String assetPath, File outDir) throws Exception {
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            // 文件
            File out = new File(outDir, new File(assetPath).getName());
            try (InputStream in = am.open(assetPath);
                 OutputStream os = new FileOutputStream(out)) {
                copy(in, os);
            }
            return;
        }
        for (String child : children) {
            File sub = new File(outDir, child);
            String childPath = assetPath + "/" + child;
            String[] grand = am.list(childPath);
            if (grand != null && grand.length > 0) {
                sub.mkdirs();
                copyAssetTree(am, childPath, sub);
            } else {
                try (InputStream in = am.open(childPath);
                     OutputStream os = new FileOutputStream(sub)) {
                    copy(in, os);
                }
            }
        }
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
        in.close();
        return bos.toByteArray();
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

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ds2api:server");
                wakeLock.setReferenceCounted(false);
            }
            // 只短暂持有 30 秒，确保进程启动 + 就绪探测期间 CPU 不休眠。
            // 服务运行期间靠前台服务（startForeground）保活，不再长期持锁，
            // 让 CPU 可正常休眠省电。请求到来时内核会唤醒 socket，无需 WakeLock。
            wakeLock.acquire(30 * 1000L);
            // 30 秒后自动释放
            new Thread(() -> {
                try { Thread.sleep(31 * 1000L); } catch (InterruptedException ignored) {}
                releaseWakeLock();
            }, "wakeLock-release").start();
        } catch (Throwable t) {
            LogStore.get().log("APP", "WakeLock 获取失败: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        Process p = process;
        if (p != null) {
            p.destroy();
            process = null;
        }
        MihomoManager.stop();
        state = State.STOPPED;
        releaseWakeLock();
        super.onDestroy();
    }
}
