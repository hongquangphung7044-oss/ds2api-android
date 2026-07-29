package com.ds2api.android;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity implements LogStore.Listener {

    private TextView statusText;
    private TextView urlText;
    private TextView keyText;
    private Button startBtn;
    private Button stopBtn;
    private Button openBtn;
    private Button proxyBtn;
    private TextView proxyStatusText;
    private TextView logText;
    private ScrollView logScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 500);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        askNotificationPermission();
        requestBatteryOptimizationExemption();
        LogStore.get().addListener(this);
        handler.post(ticker);
        refreshLogs();
    }

    /** 请求电池优化豁免：让系统不限制后台运行，防止服务被杀。 */
    private void requestBatteryOptimizationExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            // 已加入白名单则不再请求
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            LogStore.get().log("APP", "请求电池优化豁免失败: " + t.getMessage());
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(10));
        root.setBackgroundColor(Color.WHITE);

        // 状态行
        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        // 地址 / 密钥信息（点击复制）
        urlText = infoRow(root, "服务地址", "http://127.0.0.1:" + ServerService.PORT);
        keyText = infoRow(root, "管理密钥", ServerService.adminKey(this));

        // 三个主按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(10);
        root.addView(btnRow, rowLp);

        startBtn = makeButton("启动服务", v -> ServerService.startServer(this));
        stopBtn = makeButton("停止服务", v -> ServerService.stopServer(this));
        openBtn = makeButton("打开网页", v -> openWeb());
        btnRow.addView(startBtn, weightLp());
        btnRow.addView(stopBtn, weightLp());
        btnRow.addView(openBtn, weightLp());

        // 代理配置行
        LinearLayout proxyRow = new LinearLayout(this);
        proxyRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams proxyRowLp = new LinearLayout.LayoutParams(-1, -2);
        proxyRowLp.topMargin = dp(8);
        root.addView(proxyRow, proxyRowLp);

        proxyBtn = makeButton("代理配置", v ->
                startActivity(new Intent(this, ProxyConfigActivity.class)));
        proxyRow.addView(proxyBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        proxyStatusText = new TextView(this);
        proxyStatusText.setTextSize(12);
        proxyStatusText.setTextColor(Color.parseColor("#64748B"));
        proxyStatusText.setPadding(dp(8), 0, 0, 0);
        proxyRow.addView(proxyStatusText, new LinearLayout.LayoutParams(0, -2, 1f));

        // 日志标题 + 复制/清空
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.topMargin = dp(10);
        root.addView(logHead, headLp);

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logHead.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        Button copyBtn = makeButton("复制日志", v -> copyLogs());
        Button clearBtn = makeButton("清空", v -> LogStore.get().clear());
        logHead.addView(copyBtn, new LinearLayout.LayoutParams(-2, dp(36)));
        logHead.addView(clearBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        // 日志区
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#0F172A"));
        logScroll.setPadding(dp(8), dp(6), dp(8), dp(6));
        logText = new TextView(this);
        logText.setTextColor(Color.parseColor("#D1FAE5"));
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMovementMethod(ScrollingMovementMethod.getInstance());
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        logLp.topMargin = dp(6);
        root.addView(logScroll, logLp);

        setContentView(root);
    }

    private TextView infoRow(LinearLayout root, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        root.addView(row, lp);

        TextView labelTv = new TextView(this);
        labelTv.setText(label + "：");
        labelTv.setTextSize(13);
        labelTv.setTextColor(Color.parseColor("#475569"));
        row.addView(labelTv, new LinearLayout.LayoutParams(-2, -2));

        TextView valueTv = new TextView(this);
        valueTv.setText(value);
        valueTv.setTextSize(13);
        valueTv.setTextColor(Color.parseColor("#1D4ED8"));
        valueTv.setTypeface(Typeface.MONOSPACE);
        valueTv.setOnClickListener(v -> {
            copyText(label, valueTv.getText().toString());
        });
        row.addView(valueTv, new LinearLayout.LayoutParams(0, -2, 1f));
        return valueTv;
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        return lp;
    }

    private void openWeb() {
        String url = "http://127.0.0.1:" + ServerService.PORT + "/admin/";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开浏览器: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, label + "已复制", Toast.LENGTH_SHORT).show();
    }

    private void copyLogs() {
        String logs = LogStore.get().snapshot();
        if (logs.isEmpty()) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show();
            return;
        }
        copyText("日志", logs);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void refreshStatus() {
        ServerService.State st = ServerService.getState();
        boolean running = ServerService.isRunning();
        switch (st) {
            case RUNNING:
                if (running) {
                    statusText.setText("● 服务运行中  ·  端口 " + ServerService.PORT
                            + "  ·  已运行 " + uptime());
                    statusText.setTextColor(Color.parseColor("#15803D"));
                } else {
                    statusText.setText("● 服务状态异常（进程已退出）");
                    statusText.setTextColor(Color.parseColor("#B45309"));
                }
                break;
            case STARTING:
                statusText.setText("● 正在启动…");
                statusText.setTextColor(Color.parseColor("#B45309"));
                break;
            default:
                statusText.setText("● 服务已停止");
                statusText.setTextColor(Color.parseColor("#64748B"));
                String err = ServerService.getLastError();
                if (!err.isEmpty()) {
                    statusText.append("\n最近错误: " + err);
                }
        }
        startBtn.setEnabled(st == ServerService.State.STOPPED);
        stopBtn.setEnabled(st == ServerService.State.RUNNING);

        // 代理状态
        if (MihomoManager.isEnabled()) {
            if (MihomoManager.isRunning()) {
                proxyStatusText.setText("● mihomo 运行中 · :" + MihomoManager.getApiPort());
                proxyStatusText.setTextColor(Color.parseColor("#15803D"));
            } else {
                proxyStatusText.setText("● mihomo 已启用但未运行");
                proxyStatusText.setTextColor(Color.parseColor("#B45309"));
            }
        } else {
            proxyStatusText.setText("代理未启用");
            proxyStatusText.setTextColor(Color.parseColor("#64748B"));
        }
    }

    private String uptime() {
        long ms = System.currentTimeMillis() - ServerService.getStartedAt();
        if (ms < 0) {
            return "-";
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private void refreshLogs() {
        logText.setText(LogStore.get().snapshot());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onAppended() {
        // 仅当用户停留在底部时自动跟随滚动，方便向上翻查历史
        View child = logScroll.getChildAt(0);
        boolean atBottom = child == null
                || logScroll.getScrollY() + logScroll.getHeight() >= child.getHeight() - dp(40);
        logText.setText(LogStore.get().snapshot());
        if (atBottom) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    protected void onDestroy() {
        LogStore.get().removeListener(this);
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}
