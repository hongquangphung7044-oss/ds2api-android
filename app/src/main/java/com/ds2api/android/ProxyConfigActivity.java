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
 * 代理节点配置界面。
 *
 * 用户在此界面：
 * - 输入/编辑机场订阅地址
 * - 启用/禁用 mihomo 代理桥
 * - 为每个 DeepSeek 账号选择主节点和备用节点（顺序即优先级）
 * - 更新订阅（从 mihomo 拉取最新节点列表）
 *
 * 节点列表来自 mihomo 运行时的 /providers/proxies/airport API。
 * 如果 mihomo 未运行，节点下拉框为空，需先启动服务再配置。
 */
public class ProxyConfigActivity extends Activity {

    private EditText subUrlField;
    private CheckBox enabledCheckbox;
    private TextView nodeCountLabel;
    private LinearLayout accountListContainer;
    private Button refreshBtn;
    private Button saveBtn;

    private JSONObject config;          // 完整 config.json
    private JSONObject mihomoConfig;    // config.json 的 mihomo 段
    private List<String> nodeList = new ArrayList<>();  // 从 mihomo 拉取的节点名列表

    // 每个账号的节点 Spinner 列表（用于保存时读取）
    private final List<List<Spinner>> accountSpinners = new ArrayList<>();
    private final List<String> accountIdentifiers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadConfig();
        buildUi();
        // 异步拉取节点列表
        new Thread(this::fetchNodes, "node-fetcher").start();
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
            // 读取或创建 mihomo 段
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
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // 标题
        TextView title = new TextView(this);
        title.setText("代理节点配置");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        // 启用开关
        enabledCheckbox = new CheckBox(this);
        enabledCheckbox.setText("启用 mihomo 代理桥");
        enabledCheckbox.setChecked(mihomoConfig.optBoolean("enabled", false));
        root.addView(enabledCheckbox);

        // 订阅地址
        TextView subLabel = new TextView(this);
        subLabel.setText("机场订阅地址");
        subLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(subLabel);

        subUrlField = new EditText(this);
        subUrlField.setHint("https://airport.example.com/sub");
        subUrlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        subUrlField.setText(mihomoConfig.optString("subscription_url", ""));
        subUrlField.setSingleLine(true);
        root.addView(subUrlField);

        // 更新订阅按钮 + 节点数
        LinearLayout refreshRow = new LinearLayout(this);
        refreshRow.setOrientation(LinearLayout.HORIZONTAL);
        refreshRow.setGravity(Gravity.CENTER_VERTICAL);
        refreshBtn = new Button(this);
        refreshBtn.setText("更新订阅");
        refreshBtn.setOnClickListener(v -> doRefresh());
        nodeCountLabel = new TextView(this);
        nodeCountLabel.setPadding(dp(12), 0, 0, 0);
        nodeCountLabel.setText("节点数: -");
        refreshRow.addView(refreshBtn);
        refreshRow.addView(nodeCountLabel);
        root.addView(refreshRow);

        // 提示
        TextView hint = new TextView(this);
        hint.setText("提示：节点列表从 mihomo 运行时获取。请先在主界面启动服务，再回到此页面选择节点。");
        hint.setTextSize(12);
        hint.setPadding(0, dp(8), 0, dp(12));
        hint.setTextColor(Color.GRAY);
        root.addView(hint);

        // 分隔线
        root.addView(makeDivider());

        // 账号节点绑定标题
        TextView bindTitle = new TextView(this);
        bindTitle.setText("账号节点绑定");
        bindTitle.setTextSize(16);
        bindTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bindTitle.setPadding(0, dp(8), 0, dp(8));
        root.addView(bindTitle);

        accountListContainer = new LinearLayout(this);
        accountListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(accountListContainer);
        buildAccountBindings();

        // 保存按钮
        saveBtn = new Button(this);
        saveBtn.setText("保存配置");
        saveBtn.setOnClickListener(v -> doSave());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(20);
        root.addView(saveBtn, saveLp);

        scroll.addView(root);
        setContentView(scroll);
    }

    /** 构建账号绑定区域。 */
    private void buildAccountBindings() {
        accountListContainer.removeAllViews();
        accountSpinners.clear();
        accountIdentifiers.clear();

        // 从 config.json 的 accounts 数组读取账号列表
        JSONArray accounts = config.optJSONArray("accounts");
        if (accounts == null || accounts.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无账号。请先在管理界面 (http://127.0.0.1:5001/admin/) 添加 DeepSeek 账号。");
            empty.setTextColor(Color.GRAY);
            empty.setPadding(0, dp(8), 0, dp(8));
            accountListContainer.addView(empty);
            return;
        }

        // 读取已有的 mihomo 绑定
        JSONArray bindings = mihomoConfig.optJSONArray("account_bindings");
        if (bindings == null) bindings = new JSONArray();

        for (int i = 0; i < accounts.length(); i++) {
            JSONObject acc = accounts.optJSONObject(i);
            if (acc == null) continue;
            String identifier = acc.optString("email", "").trim();
            if (identifier.isEmpty()) identifier = acc.optString("mobile", "").trim();
            if (identifier.isEmpty()) identifier = acc.optString("name", "").trim();
            if (identifier.isEmpty()) continue;

            // 查找已有绑定
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

    /** 构建单个账号的卡片。 */
    private View buildAccountCard(int accIndex, String identifier, JSONArray existingNodes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(8);
        card.setLayoutParams(cardLp);
        card.setBackgroundColor(Color.parseColor("#1A1A2E"));

        // 账号标识
        TextView accLabel = new TextView(this);
        accLabel.setText(identifier);
        accLabel.setTypeface(Typeface.DEFAULT_BOLD);
        accLabel.setTextColor(Color.parseColor("#E0E0E0"));
        card.addView(accLabel);

        // 节点 Spinner 列表
        List<Spinner> spinners = new ArrayList<>();
        accountSpinners.add(spinners);

        // 已有节点或默认 1 个主节点
        int nodeCount = (existingNodes != null && existingNodes.length() > 0)
                ? existingNodes.length() : 1;
        for (int n = 0; n < nodeCount; n++) {
            String preset = (existingNodes != null && n < existingNodes.length())
                    ? existingNodes.optString(n, "") : "";
            card.addView(buildNodeRow(spinners, accIndex, n, preset, card));
        }

        // 添加备用节点按钮
        Button addBtn = new Button(this);
        addBtn.setText("+ 添加备用节点");
        addBtn.setOnClickListener(v -> {
            int idx = spinners.size();
            card.addView(buildNodeRow(spinners, accIndex, idx, "", card),
                    card.getChildCount() - 1);  // 插在"添加"按钮前面
        });
        card.addView(addBtn);

        return card;
    }

    /** 构建单行节点选择（Spinner + 删除按钮）。 */
    private View buildNodeRow(List<Spinner> spinners, int accIndex, int nodeIndex,
                              String preset, ViewGroup parentCard) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        row.setLayoutParams(rowLp);

        // 标签：主节点 / 备用N
        TextView label = new TextView(this);
        label.setText(nodeIndex == 0 ? "主节点" : "备用" + nodeIndex);
        label.setMinWidth(dp(90));
        row.addView(label);

        // 节点下拉
        Spinner spinner = new Spinner(this);
        updateSpinnerAdapter(spinner);
        if (!preset.isEmpty()) {
            for (int i = 0; i < nodeList.size(); i++) {
                if (nodeList.get(i).equals(preset)) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }
        spinners.add(spinner);
        row.addView(spinner, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 删除按钮（主节点不可删）
        if (nodeIndex > 0) {
            Button delBtn = new Button(this);
            delBtn.setText("✕");
            delBtn.setOnClickListener(v -> {
                spinners.remove(spinner);
                parentCard.removeView(row);
                // 重新编号后续行的标签
                renumberNodeRows(parentCard, spinners);
            });
            row.addView(delBtn);
        }

        return row;
    }

    /** 重新编号节点行的标签。 */
    private void renumberNodeRows(ViewGroup card, List<Spinner> spinners) {
        int spinnerIdx = 0;
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                if (row.getChildCount() > 0 && row.getChildAt(0) instanceof TextView) {
                    TextView label = (TextView) row.getChildAt(0);
                    if (label.getText().toString().startsWith("主")
                            || label.getText().toString().startsWith("备用")) {
                        label.setText(spinnerIdx == 0 ? "主节点" : "备用" + spinnerIdx);
                        spinnerIdx++;
                    }
                }
            }
        }
    }

    /** 更新 Spinner 的下拉选项。 */
    private void updateSpinnerAdapter(Spinner spinner) {
        List<String> items = new ArrayList<>();
        items.add("（未选择）");
        items.addAll(nodeList);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    /** 从 mihomo 拉取节点列表（后台线程）。 */
    private void fetchNodes() {
        if (!MihomoManager.isRunning()) {
            runOnUiThread(() -> nodeCountLabel.setText("节点数: mihomo 未运行"));
            return;
        }
        List<String> nodes = MihomoManager.fetchNodeList();
        runOnUiThread(() -> {
            nodeList = nodes;
            nodeCountLabel.setText("节点数: " + nodeList.size());
            // 刷新所有 Spinner
            for (List<Spinner> spinners : accountSpinners) {
                for (Spinner s : spinners) {
                    // 记住当前选择
                    String current = (String) s.getSelectedItem();
                    updateSpinnerAdapter(s);
                    // 恢复选择
                    if (current != null) {
                        ArrayAdapter<?> adapter = (ArrayAdapter<?>) s.getAdapter();
                        for (int i = 0; i < adapter.getCount(); i++) {
                            if (current.equals(adapter.getItem(i))) {
                                s.setSelection(i);
                                break;
                            }
                        }
                    }
                }
            }
        });
    }

    /** 点击"更新订阅"。 */
    private void doRefresh() {
        if (!MihomoManager.isRunning()) {
            toast("mihomo 未运行，请先启动服务");
            return;
        }
        refreshBtn.setEnabled(false);
        refreshBtn.setText("刷新中...");
        new Thread(() -> {
            boolean ok = MihomoManager.refreshSubscription();
            if (ok) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                fetchNodes();
            }
            runOnUiThread(() -> {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("更新订阅");
                toast(ok ? "订阅已刷新" : "刷新失败，请检查日志");
            });
        }, "sub-refresh").start();
    }

    /** 保存配置。 */
    private void doSave() {
        try {
            // 更新 mihomo 段
            mihomoConfig.put("enabled", enabledCheckbox.isChecked());
            mihomoConfig.put("subscription_url", subUrlField.getText().toString().trim());
            if (!mihomoConfig.has("api_port")) {
                mihomoConfig.put("api_port", MihomoManager.DEFAULT_API_PORT);
            }
            if (!mihomoConfig.has("socks5_base_port")) {
                mihomoConfig.put("socks5_base_port", MihomoManager.DEFAULT_SOCKS5_BASE_PORT);
            }
            if (!mihomoConfig.has("subscription_update_interval")) {
                mihomoConfig.put("subscription_update_interval", 3600);
            }

            // 构建账号绑定
            JSONArray bindings = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < accountIdentifiers.size(); i++) {
                String identifier = accountIdentifiers.get(i);
                if (!seen.add(identifier)) continue;  // 去重
                List<Spinner> spinners = accountSpinners.get(i);
                List<String> nodeNames = new ArrayList<>();
                for (Spinner s : spinners) {
                    Object sel = s.getSelectedItem();
                    if (sel == null) continue;
                    String name = sel.toString();
                    if ("（未选择）".equals(name) || name.isEmpty()) continue;
                    nodeNames.add(name);
                }
                if (nodeNames.isEmpty()) continue;  // 没选节点则不绑定
                JSONObject b = new JSONObject();
                b.put("account_identifier", identifier);
                b.put("node_names", new JSONArray(nodeNames));
                b.put("current_node_index", 0);
                bindings.put(b);
            }
            mihomoConfig.put("account_bindings", bindings);
            config.put("mihomo", mihomoConfig);

            // 写回 config.json
            File configFile = new File(getFilesDir(), "config.json");
            try (FileOutputStream out = new FileOutputStream(configFile)) {
                out.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            toast("配置已保存");

            // 如果 mihomo 正在运行，热重载
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

    // ========== 工具方法 ==========

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private View makeDivider() {
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        div.setLayoutParams(lp);
        return div;
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
