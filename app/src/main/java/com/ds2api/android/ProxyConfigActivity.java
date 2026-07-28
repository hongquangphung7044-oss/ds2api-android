package com.ds2api.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 代理节点配置界面（浅色风格，与 MainActivity 一致）。
 *
 * 用户流程：
 * 1. 填写机场订阅地址 → 点"启动 mihomo"
 * 2. mihomo 启动后自动拉取节点列表 → 节点绑定区出现
 * 3. 为每个账号选主节点 + 备用节点 → 保存
 * 4. 回主界面启动 ds2api 服务（会自动注入 Proxy）
 *
 * mihomo 可独立于 ds2api 启动，解决"先有鸡还是先有蛋"问题。
 */
public class ProxyConfigActivity extends Activity {

    // 浅色风格配色（与 MainActivity 一致）
    private static final String COLOR_BG = "#FFFFFF";
    private static final String COLOR_TEXT = "#1F2937";
    private static final String COLOR_TEXT_LIGHT = "#475569";
    private static final String COLOR_GREEN = "#15803D";
    private static final String COLOR_GRAY = "#64748B";
    private static final String COLOR_DIVIDER = "#E5E7EB";
    private static final String COLOR_CARD_BG = "#F8FAFC";

    private EditText subUrlField;
    private CheckBox enabledCheckbox;
    private TextView nodeCountLabel;
    private TextView mihomoStatusLabel;
    private Button startMihomoBtn;
    private Button stopMihomoBtn;
    private Button refreshBtn;
    private Button saveBtn;
    private LinearLayout accountListContainer;
    private View nodeBindingSection;  // 节点绑定整段（mihomo 未运行时隐藏）

    private JSONObject config;
    private JSONObject mihomoConfig;
    private List<String> nodeList = new ArrayList<>();
    private final List<List<Spinner>> accountSpinners = new ArrayList<>();
    private final List<List<TextView>> accountDelayLabels = new ArrayList<>();
    private final List<String> accountIdentifiers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadConfig();
        buildUi();
        // 如果 mihomo 已在运行，立即拉取节点
        if (MihomoManager.isRunning()) {
            new Thread(this::fetchNodes, "node-fetcher").start();
        }
        refreshMihomoStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMihomoStatus();
        if (MihomoManager.isRunning() && nodeList.isEmpty()) {
            new Thread(this::fetchNodes, "node-fetcher").start();
        }
    }

    private void loadConfig() {
        try {
            File configFile = new File(getFilesDir(), "config.json");
            if (!configFile.exists()) {
                try (InputStream in = getAssets().open("config.default.json")) {
                    byte[] data = readAll(in);
                    config = new JSONObject(new String(data, StandardCharsets.UTF_8));
                }
            } else {
                byte[] data = readAll(new java.io.FileInputStream(configFile));
                config = new JSONObject(new String(data, StandardCharsets.UTF_8));
            }
            mihomoConfig = config.optJSONObject("mihomo");
            if (mihomoConfig == null) {
                mihomoConfig = new JSONObject();
                config.put("mihomo", mihomoConfig);
            }
        } catch (Throwable t) {
            toast("加载配置失败: " + t.getMessage());
            config = new JSONObject();
            mihomoConfig = new JSONObject();
            try { config.put("mihomo", mihomoConfig); } catch (Throwable ignored) {}
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.parseColor(COLOR_BG));

        // 标题
        TextView title = new TextView(this);
        title.setText("代理节点配置");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor(COLOR_TEXT));
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        // 启用开关
        enabledCheckbox = new CheckBox(this);
        enabledCheckbox.setText("启用 mihomo 代理桥（ds2api 启动时自动拉起）");
        enabledCheckbox.setChecked(mihomoConfig.optBoolean("enabled", false));
        enabledCheckbox.setTextColor(Color.parseColor(COLOR_TEXT));
        root.addView(enabledCheckbox);

        // 订阅地址标签
        root.addView(makeLabel("机场订阅地址"));

        // 订阅地址输入框
        subUrlField = new EditText(this);
        subUrlField.setHint("https://airport.example.com/sub");
        subUrlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        subUrlField.setText(mihomoConfig.optString("subscription_url", ""));
        subUrlField.setSingleLine(true);
        root.addView(subUrlField);

        // mihomo 状态行
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(-1, -2);
        srLp.topMargin = dp(10);
        root.addView(statusRow, srLp);

        mihomoStatusLabel = new TextView(this);
        mihomoStatusLabel.setTextSize(13);
        statusRow.addView(mihomoStatusLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        // 启动/停止 mihomo 按钮
        startMihomoBtn = makeButton("启动 mihomo", v -> doStartMihomo());
        stopMihomoBtn = makeButton("停止 mihomo", v -> doStopMihomo());
        statusRow.addView(startMihomoBtn, new LinearLayout.LayoutParams(-2, dp(36)));
        statusRow.addView(stopMihomoBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        // 更新订阅 + 节点数
        LinearLayout refreshRow = new LinearLayout(this);
        refreshRow.setOrientation(LinearLayout.HORIZONTAL);
        refreshRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rrLp = new LinearLayout.LayoutParams(-1, -2);
        rrLp.topMargin = dp(8);
        root.addView(refreshRow, rrLp);

        refreshBtn = makeButton("更新订阅", v -> doRefresh());
        nodeCountLabel = new TextView(this);
        nodeCountLabel.setPadding(dp(12), 0, 0, 0);
        nodeCountLabel.setTextSize(13);
        nodeCountLabel.setTextColor(Color.parseColor(COLOR_TEXT_LIGHT));
        refreshRow.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(36)));
        refreshRow.addView(nodeCountLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        // 分隔线
        root.addView(makeDivider());

        // 节点绑定区（整体，mihomo 未运行时隐藏）
        nodeBindingSection = makeNodeBindingSection();
        root.addView(nodeBindingSection);

        scroll.addView(root);
        setContentView(scroll);
    }

    /** 构建节点绑定区。 */
    private View makeNodeBindingSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView bindTitle = new TextView(this);
        bindTitle.setText("账号节点绑定");
        bindTitle.setTextSize(16);
        bindTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bindTitle.setTextColor(Color.parseColor(COLOR_TEXT));
        bindTitle.setPadding(0, dp(4), 0, dp(4));
        section.addView(bindTitle);

        TextView hint = new TextView(this);
        hint.setText("顺序即优先级：主节点失败时自动切到备用1、备用2。需先启动 mihomo 才能选择节点。");
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor(COLOR_GRAY));
        hint.setPadding(0, 0, 0, dp(8));
        section.addView(hint);

        accountListContainer = new LinearLayout(this);
        accountListContainer.setOrientation(LinearLayout.VERTICAL);
        section.addView(accountListContainer);
        buildAccountBindings();

        // 保存按钮
        saveBtn = new Button(this);
        saveBtn.setText("保存配置");
        saveBtn.setTextSize(13);
        saveBtn.setAllCaps(false);
        saveBtn.setOnClickListener(v -> doSave());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, -2);
        saveLp.topMargin = dp(16);
        section.addView(saveBtn, saveLp);

        return section;
    }

    private void buildAccountBindings() {
        accountListContainer.removeAllViews();
        accountSpinners.clear();
        accountIdentifiers.clear();

        JSONArray accounts = config.optJSONArray("accounts");
        if (accounts == null || accounts.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无账号。请先在管理界面 (http://127.0.0.1:5001/admin/) 添加 DeepSeek 账号。");
            empty.setTextColor(Color.parseColor(COLOR_GRAY));
            empty.setPadding(0, dp(8), 0, dp(8));
            empty.setTextSize(13);
            accountListContainer.addView(empty);
            return;
        }

        JSONArray bindings = mihomoConfig.optJSONArray("account_bindings");
        if (bindings == null) bindings = new JSONArray();

        for (int i = 0; i < accounts.length(); i++) {
            JSONObject acc = accounts.optJSONObject(i);
            if (acc == null) continue;
            String identifier = acc.optString("email", "").trim();
            if (identifier.isEmpty()) identifier = acc.optString("mobile", "").trim();
            if (identifier.isEmpty()) identifier = acc.optString("name", "").trim();
            if (identifier.isEmpty()) continue;

            JSONArray existingNodes = null;
            for (int j = 0; j < bindings.length(); j++) {
                JSONObject b = bindings.optJSONObject(j);
                if (b != null && identifier.equals(b.optString("account_identifier", ""))) {
                    existingNodes = b.optJSONArray("node_names");
                    break;
                }
            }

            accountIdentifiers.add(identifier);
            accountListContainer.addView(buildAccountCard(i, identifier, existingNodes));
        }
    }

    /** 构建单个账号卡片（浅色卡片风格）。 */
    private View buildAccountCard(int accIndex, String identifier, JSONArray existingNodes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(8);
        card.setLayoutParams(cardLp);
        card.setBackgroundColor(Color.parseColor(COLOR_CARD_BG));

        // 账号标识
        TextView accLabel = new TextView(this);
        accLabel.setText(identifier);
        accLabel.setTypeface(Typeface.DEFAULT_BOLD);
        accLabel.setTextColor(Color.parseColor(COLOR_TEXT));
        accLabel.setTextSize(14);
        card.addView(accLabel);

        List<Spinner> spinners = new ArrayList<>();
        List<TextView> delayLabels = new ArrayList<>();
        accountSpinners.add(spinners);
        accountDelayLabels.add(delayLabels);

        int nodeCount = (existingNodes != null && existingNodes.length() > 0)
                ? existingNodes.length() : 1;
        for (int n = 0; n < nodeCount; n++) {
            String preset = (existingNodes != null && n < existingNodes.length())
                    ? existingNodes.optString(n, "") : "";
            card.addView(buildNodeRow(spinners, delayLabels, n, preset, card));
        }

        // 按钮行：添加备用节点 + 测试延迟
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.topMargin = dp(6);
        btnRow.setLayoutParams(brLp);

        Button addBtn = new Button(this);
        addBtn.setText("+ 添加备用");
        addBtn.setTextSize(12);
        addBtn.setAllCaps(false);
        addBtn.setOnClickListener(v -> {
            int idx = spinners.size();
            card.addView(buildNodeRow(spinners, delayLabels, idx, "", card), card.getChildCount() - 1);
        });
        btnRow.addView(addBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        Button testBtn = new Button(this);
        testBtn.setText("测试延迟");
        testBtn.setTextSize(12);
        testBtn.setAllCaps(false);
        testBtn.setOnClickListener(v -> doTestDelay(spinners, delayLabels, testBtn));
        btnRow.addView(testBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        card.addView(btnRow);

        return card;
    }

    private View buildNodeRow(List<Spinner> spinners, List<TextView> delayLabels,
                              int nodeIndex, String preset, ViewGroup parentCard) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(6);
        row.setLayoutParams(rowLp);

        TextView label = new TextView(this);
        label.setText(nodeIndex == 0 ? "主节点" : "备用" + nodeIndex);
        label.setMinWidth(dp(70));
        label.setTextColor(Color.parseColor(COLOR_TEXT_LIGHT));
        label.setTextSize(13);
        row.addView(label);

        Spinner spinner = new Spinner(this);
        updateSpinnerAdapter(spinner);
        if (!preset.isEmpty()) {
            for (int i = 0; i < nodeList.size(); i++) {
                if (nodeList.get(i).equals(preset)) {
                    spinner.setSelection(i + 1);  // +1 因为第 0 项是"未选择"
                    break;
                }
            }
        }
        spinners.add(spinner);
        row.addView(spinner, new LinearLayout.LayoutParams(0, -2, 1f));

        // 延迟显示标签
        TextView delayLabel = new TextView(this);
        delayLabel.setTextSize(12);
        delayLabel.setTextColor(Color.parseColor(COLOR_GRAY));
        delayLabel.setMinWidth(dp(52));
        delayLabel.setGravity(Gravity.CENTER);
        delayLabels.add(delayLabel);
        row.addView(delayLabel);

        if (nodeIndex > 0) {
            Button delBtn = new Button(this);
            delBtn.setText("✕");
            delBtn.setTextSize(11);
            delBtn.setOnClickListener(v -> {
                spinners.remove(spinner);
                delayLabels.remove(delayLabel);
                parentCard.removeView(row);
                renumberNodeRows(parentCard, spinners);
            });
            row.addView(delBtn);
        }

        return row;
    }

    /** 测试该账号绑定的所有节点延迟。 */
    private void doTestDelay(List<Spinner> spinners, List<TextView> delayLabels, Button testBtn) {
        if (!MihomoManager.isRunning()) {
            toast("请先启动 mihomo");
            return;
        }
        testBtn.setEnabled(false);
        testBtn.setText("测试中...");
        // 先重置所有标签
        for (TextView l : delayLabels) {
            l.setText("...");
            l.setTextColor(Color.parseColor(COLOR_GRAY));
        }
        new Thread(() -> {
            for (int i = 0; i < spinners.size(); i++) {
                Spinner s = spinners.get(i);
                if (i >= delayLabels.size()) break;
                TextView label = delayLabels.get(i);
                Object sel = s.getSelectedItem();
                if (sel == null) {
                    runOnUiThread(() -> label.setText("-"));
                    continue;
                }
                String nodeName = sel.toString();
                if ("（未选择）".equals(nodeName) || nodeName.isEmpty()) {
                    runOnUiThread(() -> label.setText("-"));
                    continue;
                }
                int delay = MihomoManager.testNodeDelay(nodeName);
                int finalI = i;
                runOnUiThread(() -> {
                    if (delay > 0) {
                        label.setText(delay + "ms");
                        label.setTextColor(delay < 300
                                ? Color.parseColor(COLOR_GREEN)
                                : Color.parseColor("#B45309"));
                    } else {
                        label.setText("超时");
                        label.setTextColor(Color.parseColor("#DC2626"));
                    }
                });
            }
            runOnUiThread(() -> {
                testBtn.setEnabled(true);
                testBtn.setText("测试延迟");
            });
        }, "delay-test").start();
    }

    private void renumberNodeRows(ViewGroup card, List<Spinner> spinners) {
        int spinnerIdx = 0;
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                if (row.getChildCount() > 0 && row.getChildAt(0) instanceof TextView) {
                    TextView label = (TextView) row.getChildAt(0);
                    String text = label.getText().toString();
                    if (text.startsWith("主") || text.startsWith("备用")) {
                        label.setText(spinnerIdx == 0 ? "主节点" : "备用" + spinnerIdx);
                        spinnerIdx++;
                    }
                }
            }
        }
    }

    private void updateSpinnerAdapter(Spinner spinner) {
        List<String> items = new ArrayList<>();
        items.add("（未选择）");
        items.addAll(nodeList);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    // ========== 操作 ==========

    /** 独立启动 mihomo（不依赖 ds2api）。 */
    private void doStartMihomo() {
        String url = subUrlField.getText().toString().trim();
        if (url.isEmpty()) {
            toast("请先填写订阅地址");
            return;
        }
        // 先保存到 config.json（mihomo 启动需要读取）
        try {
            // 同步 checkbox 状态，避免覆盖用户选择
            mihomoConfig.put("enabled", enabledCheckbox.isChecked());
            mihomoConfig.put("subscription_url", url);
            if (!mihomoConfig.has("api_port")) {
                mihomoConfig.put("api_port", MihomoManager.DEFAULT_API_PORT);
            }
            if (!mihomoConfig.has("socks5_base_port")) {
                mihomoConfig.put("socks5_base_port", MihomoManager.DEFAULT_SOCKS5_BASE_PORT);
            }
            if (!mihomoConfig.has("subscription_update_interval")) {
                mihomoConfig.put("subscription_update_interval", 3600);
            }
            config.put("mihomo", mihomoConfig);
            writeConfig();
        } catch (Throwable t) {
            toast("保存订阅地址失败: " + t.getMessage());
            return;
        }

        startMihomoBtn.setEnabled(false);
        startMihomoBtn.setText("启动中...");
        new Thread(() -> {
            try {
                File workDir = new File(getFilesDir(), "mihomo");
                MihomoManager.start(this, workDir, mihomoConfig);
                boolean ready = MihomoManager.probeReady();
                if (ready) {
                    // 等 mihomo 拉取订阅
                    Thread.sleep(2000);
                    fetchNodes();
                }
                runOnUiThread(() -> {
                    startMihomoBtn.setEnabled(true);
                    startMihomoBtn.setText("启动 mihomo");
                    refreshMihomoStatus();
                    if (!ready) {
                        toast("mihomo 启动失败，请查看主界面日志");
                    }
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    startMihomoBtn.setEnabled(true);
                    startMihomoBtn.setText("启动 mihomo");
                    toast("启动失败: " + t.getMessage());
                });
            }
        }, "mihomo-starter").start();
    }

    private void doStopMihomo() {
        MihomoManager.stop();
        refreshMihomoStatus();
        nodeCountLabel.setText("节点数: -");
        toast("mihomo 已停止");
    }

    private void doRefresh() {
        if (!MihomoManager.isRunning()) {
            toast("请先启动 mihomo");
            return;
        }
        refreshBtn.setEnabled(false);
        refreshBtn.setText("刷新中...");
        new Thread(() -> {
            boolean ok = MihomoManager.refreshSubscription();
            if (ok) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                fetchNodes();
            }
            runOnUiThread(() -> {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("更新订阅");
                toast(ok ? "订阅已刷新" : "刷新失败，请检查日志");
            });
        }, "sub-refresh").start();
    }

    private void doSave() {
        try {
            mihomoConfig.put("enabled", enabledCheckbox.isChecked());
            mihomoConfig.put("subscription_url", subUrlField.getText().toString().trim());

            JSONArray bindings = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < accountIdentifiers.size(); i++) {
                String identifier = accountIdentifiers.get(i);
                if (!seen.add(identifier)) continue;
                List<Spinner> spinners = accountSpinners.get(i);
                List<String> nodeNames = new ArrayList<>();
                for (Spinner s : spinners) {
                    Object sel = s.getSelectedItem();
                    if (sel == null) continue;
                    String name = sel.toString();
                    if ("（未选择）".equals(name) || name.isEmpty()) continue;
                    nodeNames.add(name);
                }
                if (nodeNames.isEmpty()) continue;
                JSONObject b = new JSONObject();
                b.put("account_identifier", identifier);
                b.put("node_names", new JSONArray(nodeNames));
                b.put("current_node_index", 0);
                bindings.put(b);
            }
            mihomoConfig.put("account_bindings", bindings);
            config.put("mihomo", mihomoConfig);
            writeConfig();
            toast("配置已保存");

            if (MihomoManager.isRunning()) {
                new Thread(() -> {
                    boolean ok = MihomoManager.reloadConfig();
                    runOnUiThread(() -> toast(ok ? "mihomo 已热重载" : "热重载失败，下次启动生效"));
                }, "mihomo-reload").start();
            }
        } catch (Throwable t) {
            toast("保存失败: " + t.getMessage());
        }
    }

    // ========== 状态刷新 ==========

    private void refreshMihomoStatus() {
        if (MihomoManager.isRunning()) {
            mihomoStatusLabel.setText("● mihomo 运行中 · :" + MihomoManager.getApiPort());
            mihomoStatusLabel.setTextColor(Color.parseColor(COLOR_GREEN));
            startMihomoBtn.setEnabled(false);
            stopMihomoBtn.setEnabled(true);
            nodeBindingSection.setVisibility(View.VISIBLE);
        } else {
            mihomoStatusLabel.setText("● mihomo 未运行");
            mihomoStatusLabel.setTextColor(Color.parseColor(COLOR_GRAY));
            startMihomoBtn.setEnabled(true);
            stopMihomoBtn.setEnabled(false);
            // 未运行时仍显示绑定区（可保存，等启动后选节点）
            nodeBindingSection.setVisibility(View.VISIBLE);
        }
    }

    private void fetchNodes() {
        if (!MihomoManager.isRunning()) {
            runOnUiThread(() -> nodeCountLabel.setText("节点数: mihomo 未运行"));
            return;
        }
        List<String> nodes = MihomoManager.fetchNodeList();
        runOnUiThread(() -> {
            nodeList = nodes;
            nodeCountLabel.setText("节点数: " + nodeList.size());
            // 重建绑定区：nodeList 现在已填充，preset 能正确匹配
            buildAccountBindings();
        });
    }

    // ========== 工具 ==========

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor(COLOR_TEXT_LIGHT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View makeDivider() {
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor(COLOR_DIVIDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        div.setLayoutParams(lp);
        return div;
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void writeConfig() throws Exception {
        File configFile = new File(getFilesDir(), "config.json");
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            out.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }
}
