package com.ds2api.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
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

    // Material3 风格配色（浅色 tonal palette）
    private static final String COLOR_BG = "#F8FAFC";            // background
    private static final String COLOR_SURFACE = "#FFFFFF";       // surface
    private static final String COLOR_TEXT = "#1F2937";          // on-surface
    private static final String COLOR_TEXT_LIGHT = "#475569";    // on-surface-variant
    private static final String COLOR_GREEN = "#15803D";
    private static final String COLOR_RED = "#DC2626";
    private static final String COLOR_GRAY = "#64748B";
    private static final String COLOR_DIVIDER = "#E2E8F0";
    private static final String COLOR_CARD_BG = "#FFFFFF";       // 卡片用纯白 + 阴影，更 M3
    private static final String COLOR_PRIMARY = "#2563EB";
    private static final String COLOR_PRIMARY_DARK = "#1D4ED8";
    private static final String COLOR_PRIMARY_CONTAINER = "#DBEAFE";   // primary container
    private static final String COLOR_ON_PRIMARY_CONTAINER = "#1E40AF";
    private static final String COLOR_BTN_SECONDARY = "#F1F5F9";
    private static final String COLOR_BTN_SECONDARY_BORDER = "#CBD5E1";

    private CheckBox enabledCheckbox;
    private TextView mihomoStatusLabel;
    private Button startMihomoBtn;
    private Button stopMihomoBtn;
    private Button saveBtn;
    private LinearLayout subscriptionListContainer;   // 订阅列表容器
    private LinearLayout accountListContainer;
    private View nodeBindingSection;

    private JSONObject config;
    private JSONObject mihomoConfig;
    /** 订阅名 → 节点列表缓存（线程安全，后台 fetchNodes 与主线程 UI 读并发访问） */
    private final java.util.Map<String, List<String>> subNodeCache = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    /** 订阅名 → provider 名（sub-{index}）映射，测延迟时按订阅隔离（线程安全） */
    private final java.util.Map<String, String> subNameToProvider = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    /** 订阅名列表（按添加顺序） */
    private final List<String> subscriptionNames = new ArrayList<>();
    /** 每个账号的节点行（每行含订阅Spinner+节点Spinner+延迟徽章），支持跨订阅选备用 */
    private final List<List<NodeRow>> accountNodeRows = new ArrayList<>();
    private final List<String> accountIdentifiers = new ArrayList<>();

    /** 一个节点选择行：订阅Spinner + 节点Spinner + 延迟徽章 + 容器视图。 */
    private static final class NodeRow {
        Spinner subSpinner;
        Spinner nodeSpinner;
        TextView delayLabel;
        LinearLayout container;
        /** 防止 setSelection 同步触发 onItemSelected 导致递归刷新节点列表 */
        boolean suppressNodeRefresh = false;
    }

    /** fetchNodes 并发保护：onCreate 与 onResume 可能同时触发，避免两个线程并发改缓存导致崩溃 */
    private final java.util.concurrent.atomic.AtomicBoolean fetchNodesRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            loadConfig();
            buildUi();
        } catch (Throwable t) {
            // 加载配置/构建 UI 异常时，显示错误信息而非闪退
            android.util.Log.e("ProxyConfig", "onCreate 失败", t);
            TextView err = new TextView(this);
            err.setPadding(dp(20), dp(40), dp(20), dp(20));
            err.setText("代理配置加载失败: " + t.getMessage()
                    + "\n\n请尝试：停止服务 → 删除 mihomo_config.json → 重新进入。\n\n"
                    + "异常: " + t.getClass().getName());
            err.setTextSize(13);
            setContentView(err);
            return;
        }
        // 如果 mihomo 已在运行，立即拉取节点
        if (MihomoManager.isRunning()) {
            new Thread(this::fetchNodes, "node-fetcher").start();
        }
        try {
            refreshMihomoStatus();
        } catch (Throwable t) {
            android.util.Log.e("ProxyConfig", "refreshMihomoStatus 失败", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            refreshMihomoStatus();
        } catch (Throwable t) {
            android.util.Log.e("ProxyConfig", "onResume refreshMihomoStatus 失败", t);
        }
        // subNodeCache.isEmpty() 在 synchronizedMap 上是线程安全单次调用
        if (MihomoManager.isRunning() && subNodeCache.isEmpty()) {
            new Thread(this::fetchNodes, "node-fetcher").start();
        }
    }

    private void loadConfig() {
        try {
            // 主配置（账号列表等）从 config.json 读取
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

            // mihomo 配置从独立文件读取，避免被 ds2api Go 服务端写回 config.json 时覆盖
            File mihomoFile = new File(getFilesDir(), "mihomo_config.json");
            if (mihomoFile.exists()) {
                byte[] data = readAll(new java.io.FileInputStream(mihomoFile));
                mihomoConfig = new JSONObject(new String(data, StandardCharsets.UTF_8));
            } else {
                // 迁移：旧版本把 mihomo 配置存在 config.json 里
                JSONObject old = config.optJSONObject("mihomo");
                if (old != null) {
                    mihomoConfig = old;
                    config.remove("mihomo");
                } else {
                    mihomoConfig = new JSONObject();
                }
                // 立即写入新文件，并从 config.json 移除
                writeMihomoConfig();
            }

            // 迁移旧端口：旧版本默认 7890/9090，与 Clash/FlClash 冲突，迁移到新默认值
            if (mihomoConfig.optInt("socks5_base_port", 0) == 7890) {
                mihomoConfig.put("socks5_base_port", MihomoManager.DEFAULT_SOCKS5_BASE_PORT);
            }
            if (mihomoConfig.optInt("api_port", 0) == 9090) {
                mihomoConfig.put("api_port", MihomoManager.DEFAULT_API_PORT);
            }
        } catch (Throwable t) {
            toast("加载配置失败: " + t.getMessage());
            config = new JSONObject();
            mihomoConfig = new JSONObject();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.parseColor(COLOR_BG));

        // 标题（M3 headline-small）
        TextView title = new TextView(this);
        title.setText("代理节点配置");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor(COLOR_TEXT));
        title.setLetterSpacing(0.01f);
        title.setPadding(0, dp(4), 0, dp(14));
        root.addView(title);

        // 启用开关
        enabledCheckbox = new CheckBox(this);
        enabledCheckbox.setText("启用 mihomo 代理桥（ds2api 启动时自动拉起）");
        enabledCheckbox.setChecked(mihomoConfig.optBoolean("enabled", false));
        enabledCheckbox.setTextColor(Color.parseColor(COLOR_TEXT));
        root.addView(enabledCheckbox);

        // ===== 订阅管理区 =====
        root.addView(makeSectionTitle("机场订阅"));

        subscriptionListContainer = new LinearLayout(this);
        subscriptionListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(subscriptionListContainer);
        rebuildSubscriptionList();

        // 添加订阅按钮
        Button addSubBtn = makeSecondaryButton("+ 添加订阅", v -> addSubscriptionRow("", "", true));
        LinearLayout.LayoutParams addSubLp = new LinearLayout.LayoutParams(-2, dp(38));
        addSubLp.topMargin = dp(6);
        root.addView(addSubBtn, addSubLp);

        // 分隔线
        root.addView(makeDivider());

        // mihomo 状态行
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(-1, -2);
        srLp.topMargin = dp(4);
        root.addView(statusRow, srLp);

        mihomoStatusLabel = new TextView(this);
        mihomoStatusLabel.setTextSize(13);
        statusRow.addView(mihomoStatusLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        Button refreshSubBtn = makeSecondaryButton("更新订阅", v -> doRefresh());
        statusRow.addView(refreshSubBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        startMihomoBtn = makePrimaryButton("启动 mihomo", v -> doStartMihomo());
        stopMihomoBtn = makeSecondaryButton("停止", v -> doStopMihomo());
        statusRow.addView(startMihomoBtn, new LinearLayout.LayoutParams(-2, dp(40)));
        statusRow.addView(stopMihomoBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        // 分隔线
        root.addView(makeDivider());

        // 节点绑定区
        nodeBindingSection = makeNodeBindingSection();
        root.addView(nodeBindingSection);

        scroll.addView(root);
        setContentView(scroll);
    }

    /** 重建订阅列表 UI（从 mihomoConfig.subscriptions 读取）。 */
    private void rebuildSubscriptionList() {
        subscriptionListContainer.removeAllViews();
        subscriptionNames.clear();

        JSONArray subs = mihomoConfig.optJSONArray("subscriptions");
        // 兼容旧版单订阅
        if (subs == null) {
            String oldUrl = mihomoConfig.optString("subscription_url", "").trim();
            if (!oldUrl.isEmpty()) {
                subs = new JSONArray();
                try {
                    JSONObject s = new JSONObject();
                    s.put("name", "默认订阅");
                    s.put("url", oldUrl);
                    s.put("enabled", true);
                    subs.put(s);
                    mihomoConfig.put("subscriptions", subs);
                    mihomoConfig.remove("subscription_url");
                } catch (Throwable ignored) {}
            }
        }
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s == null) continue;
                addSubscriptionRow(s.optString("name", ""), s.optString("url", ""),
                        s.optBoolean("enabled", true));
            }
        }
    }

    /** 添加一行订阅输入（名称 + URL + 启用 + 删除）。 */
    private void addSubscriptionRow(String name, String url, boolean enabled) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);
        row.setBackground(cardBackground());
        row.setElevation(dp(2));

        // 第一行：名称 + 启用 + 删除
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        EditText nameField = new EditText(this);
        nameField.setHint("订阅名称");
        nameField.setText(name);
        nameField.setTextSize(13);
        nameField.setSingleLine(true);
        nameField.setTextColor(Color.parseColor(COLOR_TEXT));
        nameField.setHintTextColor(Color.parseColor(COLOR_GRAY));
        nameField.setPadding(dp(8), dp(6), dp(8), dp(6));
        nameField.setBackground(roundedBackground("#FFFFFF", COLOR_DIVIDER, dp(6)));
        header.addView(nameField, new LinearLayout.LayoutParams(0, -2, 1f));

        CheckBox subEnabled = new CheckBox(this);
        subEnabled.setChecked(enabled);
        subEnabled.setText("启用");
        subEnabled.setTextSize(12);
        subEnabled.setTextColor(Color.parseColor(COLOR_TEXT_LIGHT));
        header.addView(subEnabled, new LinearLayout.LayoutParams(-2, -2));

        Button delBtn = new Button(this);
        delBtn.setText("✕");
        delBtn.setTextSize(12);
        delBtn.setAllCaps(false);
        delBtn.setTextColor(Color.parseColor(COLOR_RED));
        delBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        delBtn.setBackground(roundedBackground("#FEF2F2", "#FECACA", dp(6)));
        delBtn.setOnClickListener(v -> {
            subscriptionListContainer.removeView(row);
            // 订阅列表变化后，刷新所有账号卡片的订阅选择下拉
            refreshAccountSubSpinners();
        });
        header.addView(delBtn, new LinearLayout.LayoutParams(-2, dp(32)));

        row.addView(header);

        // 第二行：URL
        EditText urlField = new EditText(this);
        urlField.setHint("https://airport.example.com/sub");
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setText(url);
        urlField.setTextSize(13);
        urlField.setSingleLine(true);
        urlField.setTextColor(Color.parseColor(COLOR_TEXT));
        urlField.setHintTextColor(Color.parseColor(COLOR_GRAY));
        urlField.setPadding(dp(8), dp(6), dp(8), dp(6));
        urlField.setBackground(roundedBackground("#FFFFFF", COLOR_DIVIDER, dp(6)));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(-1, -2);
        urlLp.topMargin = dp(4);
        row.addView(urlField, urlLp);

        // 用 tag 存储控件引用，保存时遍历读取
        row.setTag(new Object[]{nameField, urlField, subEnabled});

        subscriptionListContainer.addView(row);
        // 新增订阅后，立即刷新所有账号卡片的订阅选择下拉（修复"新加订阅不刷新"）
        refreshAccountSubSpinners();
        // 订阅名编辑后也实时同步到账号下拉，避免保存前显示旧名
        nameField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) refreshAccountSubSpinners();
        });
    }

    /**
     * 订阅列表变化（新增/删除/改名）后，刷新所有节点行的订阅 Spinner 选项。
     * 保留各行当前已选订阅名与节点名（节点名作为自定义项保留，防止订阅暂时失效丢失选择）。
     */
    private void refreshAccountSubSpinners() {
        List<String> subNames = getSubscriptionNames();
        for (List<NodeRow> rows : accountNodeRows) {
            for (NodeRow row : rows) {
                String currentSub = row.subSpinner.getSelectedItemPosition() > 0
                        ? String.valueOf(row.subSpinner.getSelectedItem()) : "";
                List<String> options = new ArrayList<>();
                options.add("（未选择）");
                options.addAll(subNames);
                // 当前已选订阅若不在列表里（被改名/删除），追加为自定义项保留
                if (!currentSub.isEmpty() && !options.contains(currentSub)) {
                    options.add(currentSub);
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, options);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                row.subSpinner.setAdapter(adapter);
                int sel = 0;
                for (int k = 0; k < options.size(); k++) {
                    if (options.get(k).equals(currentSub)) { sel = k; break; }
                }
                row.subSpinner.setSelection(sel);
                // 同步刷新该行节点选项，保留当前已选节点
                String currentNode = (row.nodeSpinner.getSelectedItem() != null)
                        ? row.nodeSpinner.getSelectedItem().toString() : "";
                updateNodeSpinnerOptions(row, currentNode);
            }
        }
    }

    /** 从 UI 收集订阅列表写入 mihomoConfig。 */
    private void collectSubscriptionsFromUi() throws Exception {
        JSONArray subs = new JSONArray();
        for (int i = 0; i < subscriptionListContainer.getChildCount(); i++) {
            View child = subscriptionListContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                Object tagObj = child.getTag();
                if (!(tagObj instanceof Object[])) continue;
                Object[] tags = (Object[]) tagObj;
                if (tags.length < 3) continue;
                EditText nameField = (EditText) tags[0];
                EditText urlField = (EditText) tags[1];
                CheckBox subEnabled = (CheckBox) tags[2];
                String n = nameField.getText().toString().trim();
                String u = urlField.getText().toString().trim();
                if (u.isEmpty()) continue;
                if (n.isEmpty()) n = "订阅" + (i + 1);
                JSONObject s = new JSONObject();
                s.put("name", n);
                s.put("url", u);
                s.put("enabled", subEnabled.isChecked());
                subs.put(s);
            }
        }
        mihomoConfig.put("subscriptions", subs);
        mihomoConfig.remove("subscription_url");  // 清理旧字段
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

        // 保存按钮（主操作）
        saveBtn = makePrimaryButton("保存配置", v -> doSave());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, -2);
        saveLp.topMargin = dp(16);
        section.addView(saveBtn, saveLp);

        return section;
    }

    private void buildAccountBindings() {
        accountListContainer.removeAllViews();
        accountNodeRows.clear();
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

        // 收集所有订阅名供选择
        List<String> subNames = getSubscriptionNames();

        for (int i = 0; i < accounts.length(); i++) {
            JSONObject acc = accounts.optJSONObject(i);
            if (acc == null) continue;
            String identifier = acc.optString("email", "").trim();
            if (identifier.isEmpty()) identifier = acc.optString("mobile", "").trim();
            if (identifier.isEmpty()) identifier = acc.optString("name", "").trim();
            if (identifier.isEmpty()) continue;

            // 收集该账号已保存的节点：优先新格式 nodes[]，兼容旧格式 node_names[]+subscription_name
            List<String[]> existingNodes = new ArrayList<>();  // 每项 {subName, nodeName}
            for (int j = 0; j < bindings.length(); j++) {
                JSONObject b = bindings.optJSONObject(j);
                if (b == null || !identifier.equals(b.optString("account_identifier", ""))) continue;
                JSONArray nodesArr = b.optJSONArray("nodes");
                if (nodesArr != null) {
                    for (int k = 0; k < nodesArr.length(); k++) {
                        JSONObject n = nodesArr.optJSONObject(k);
                        if (n == null) continue;
                        String sn = n.optString("subscription", "").trim();
                        String nm = n.optString("name", "").trim();
                        if (!nm.isEmpty()) existingNodes.add(new String[]{sn, nm});
                    }
                } else {
                    String sn = b.optString("subscription_name", "").trim();
                    JSONArray names = b.optJSONArray("node_names");
                    if (names != null) {
                        for (int k = 0; k < names.length(); k++) {
                            String nm = names.optString(k, "").trim();
                            if (!nm.isEmpty()) existingNodes.add(new String[]{sn, nm});
                        }
                    }
                }
                break;
            }

            accountIdentifiers.add(identifier);
            accountListContainer.addView(buildAccountCard(i, identifier, existingNodes, subNames));
        }
    }

    /** 从 UI 收集当前所有订阅名（已填了名称的）。 */
    private List<String> getSubscriptionNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < subscriptionListContainer.getChildCount(); i++) {
            View child = subscriptionListContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                Object tagObj = child.getTag();
                if (!(tagObj instanceof Object[])) continue;
                Object[] tags = (Object[]) tagObj;
                if (tags.length < 3) continue;
                EditText nameField = (EditText) tags[0];
                EditText urlField = (EditText) tags[1];
                CheckBox subEnabled = (CheckBox) tags[2];
                String u = urlField.getText().toString().trim();
                if (u.isEmpty() || !subEnabled.isChecked()) continue;
                String n = nameField.getText().toString().trim();
                if (n.isEmpty()) n = "订阅" + (i + 1);
                names.add(n);
            }
        }
        return names;
    }

    /** 构建单个账号卡片。每个节点行独立选择订阅+节点，支持跨订阅备用。 */
    private View buildAccountCard(int accIndex, String identifier,
                                   List<String[]> existingNodes, List<String> subNames) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(10);
        card.setLayoutParams(cardLp);
        card.setBackground(cardBackground());
        card.setElevation(dp(2));

        // 账号标识
        TextView accLabel = new TextView(this);
        accLabel.setText(identifier);
        accLabel.setTypeface(Typeface.DEFAULT_BOLD);
        accLabel.setTextColor(Color.parseColor(COLOR_TEXT));
        accLabel.setTextSize(14);
        card.addView(accLabel);

        List<NodeRow> nodeRows = new ArrayList<>();
        accountNodeRows.add(nodeRows);

        int nodeCount = Math.max(1, existingNodes.size());
        for (int n = 0; n < nodeCount; n++) {
            String presetSub = (n < existingNodes.size()) ? existingNodes.get(n)[0] : "";
            String presetNode = (n < existingNodes.size()) ? existingNodes.get(n)[1] : "";
            card.addView(buildNodeRow(nodeRows, n, presetSub, presetNode, card, subNames));
        }

        // 按钮行：添加备用节点 + 测试全部延迟
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.topMargin = dp(6);
        btnRow.setLayoutParams(brLp);

        // 代理验证结果显示（先创建，按钮 lambda 需引用）
        final TextView verifyLabel = new TextView(this);
        verifyLabel.setTextSize(12);
        verifyLabel.setTextColor(Color.parseColor(COLOR_GRAY));
        verifyLabel.setPadding(0, dp(4), 0, 0);

        Button addBtn = makeSecondaryButton("+ 添加备用", v -> {
            int idx = nodeRows.size();
            // 插到按钮行之前（btnRow 是倒数第二个子视图，delayListScroll 是最后一个）
            int insertAt = card.indexOfChild(btnRow);
            card.addView(buildNodeRow(nodeRows, idx, "", "", card, subNames), insertAt);
        });
        btnRow.addView(addBtn, new LinearLayout.LayoutParams(-2, dp(38)));

        Button testBtn = makeSecondaryButton("测试延迟", v -> {});
        testBtn.setOnClickListener(v -> doTestDelay(nodeRows, testBtn, card, accIndex));
        btnRow.addView(testBtn, new LinearLayout.LayoutParams(-2, dp(38)));

        Button verifyBtn = makeSecondaryButton("验证代理", v -> doVerifyProxy(accIndex, verifyLabel));
        btnRow.addView(verifyBtn, new LinearLayout.LayoutParams(-2, dp(38)));

        card.addView(btnRow);
        card.addView(verifyLabel);

        // 全节点延迟列表区域（测延迟后显示所有节点+延迟，可滚动）
        ScrollView delayListScroll = new ScrollView(this);
        LinearLayout.LayoutParams dlsLp = new LinearLayout.LayoutParams(-1, dp(180));
        dlsLp.topMargin = dp(6);
        delayListScroll.setLayoutParams(dlsLp);
        delayListScroll.setBackgroundColor(Color.parseColor("#F8FAFC"));
        delayListScroll.setVisibility(View.GONE); // 默认隐藏，测延迟后显示
        TextView delayListText = new TextView(this);
        delayListText.setTextSize(11);
        delayListText.setTypeface(Typeface.MONOSPACE);
        delayListText.setPadding(dp(8), dp(8), dp(8), dp(8));
        delayListText.setTextColor(Color.parseColor(COLOR_TEXT));
        delayListScroll.addView(delayListText);
        // 修复嵌套 ScrollView 滑动冲突：触摸内层延迟列表时禁止外层 ScrollView 拦截事件
        delayListScroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        card.addView(delayListScroll);
        // 保存引用供 doTestDelay 使用（用包装数组做 tag，避免 setTag(int) 要求 app 资源 ID）
        card.setTag(new Object[]{delayListScroll, delayListText});

        return card;
    }

    /**
     * 构建一个节点选择行：订阅Spinner + 节点Spinner（级联）。
     * 关键容错：presetNode 即使不在当前订阅节点缓存里（订阅失效/未加载），
     * 也作为自定义项加入节点Spinner并选中，保证已选节点不丢失。
     */
    private View buildNodeRow(List<NodeRow> nodeRows, int nodeIndex,
                              String presetSub, String presetNode,
                              ViewGroup parentCard, List<String> subNames) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(-1, -2);
        containerLp.topMargin = dp(8);
        container.setLayoutParams(containerLp);

        final NodeRow row = new NodeRow();
        row.container = container;

        // —— 订阅 Spinner ——
        Spinner subSpinner = new Spinner(this);
        List<String> subOptions = new ArrayList<>();
        subOptions.add("（未选择）");
        subOptions.addAll(subNames);
        // 若 presetSub 不在订阅列表里（订阅被删除/改名），追加为自定义项保留选择
        if (!presetSub.isEmpty() && !subNames.contains(presetSub)) {
            subOptions.add(presetSub);
        }
        ArrayAdapter<String> subAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, subOptions);
        subAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        subSpinner.setAdapter(subAdapter);
        if (!presetSub.isEmpty()) {
            for (int k = 0; k < subOptions.size(); k++) {
                if (subOptions.get(k).equals(presetSub)) {
                    subSpinner.setSelection(k);
                    break;
                }
            }
        }
        row.subSpinner = subSpinner;

        // —— 节点 Spinner ——
        Spinner nodeSpinner = new Spinner(this);
        row.nodeSpinner = nodeSpinner;
        // 先按 presetSub 填充节点选项，并保留 presetNode
        updateNodeSpinnerOptions(row, presetNode);

        // 切换订阅时刷新该行节点选项（保留当前已选节点名）
        subSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (row.suppressNodeRefresh) return;  // 程序内 setSelection 触发，跳过递归刷新
                // 用户切换订阅后，保留当前节点选择尽量不变（若新订阅也有同名节点则保持，否则清空）
                String currentNode = "";
                if (nodeSpinner.getSelectedItem() != null) {
                    currentNode = nodeSpinner.getSelectedItem().toString();
                }
                updateNodeSpinnerOptions(row, currentNode);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // 延迟徽章
        TextView delayLabel = new TextView(this);
        delayLabel.setTextSize(12);
        delayLabel.setTextColor(Color.parseColor(COLOR_GRAY));
        delayLabel.setGravity(Gravity.CENTER);
        delayLabel.setPadding(dp(10), dp(2), dp(10), dp(2));
        delayLabel.setText("—");
        delayLabel.setBackground(roundedBackground("#F1F5F9", COLOR_DIVIDER, dp(10)));
        row.delayLabel = delayLabel;
        nodeRows.add(row);

        // 第一行：标签 + 延迟徽章 + 删除按钮
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(nodeIndex == 0 ? "主节点" : "备用 " + nodeIndex);
        label.setTextColor(Color.parseColor(COLOR_TEXT_LIGHT));
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(label, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams delayLp = new LinearLayout.LayoutParams(-2, -2);
        delayLp.leftMargin = dp(8);
        headerRow.addView(delayLabel, delayLp);

        View spacer = new View(this);
        headerRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        if (nodeIndex > 0) {
            Button delBtn = new Button(this);
            delBtn.setText("✕");
            delBtn.setTextSize(12);
            delBtn.setAllCaps(false);
            delBtn.setTextColor(Color.parseColor(COLOR_RED));
            delBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
            delBtn.setBackground(roundedBackground("#FEF2F2", "#FECACA", dp(6)));
            delBtn.setOnClickListener(v -> {
                nodeRows.remove(row);
                parentCard.removeView(container);
                renumberNodeRows(parentCard, nodeRows);
            });
            headerRow.addView(delBtn, new LinearLayout.LayoutParams(-2, dp(32)));
        }

        container.addView(headerRow);

        // 第二行：订阅 Spinner
        LinearLayout.LayoutParams subSpinLp = new LinearLayout.LayoutParams(-1, -2);
        subSpinLp.topMargin = dp(2);
        container.addView(subSpinner, subSpinLp);

        // 第三行：节点 Spinner
        LinearLayout.LayoutParams nodeSpinLp = new LinearLayout.LayoutParams(-1, -2);
        nodeSpinLp.topMargin = dp(2);
        container.addView(nodeSpinner, nodeSpinLp);

        return container;
    }

    /**
     * 按 NodeRow 当前选中的订阅，刷新该行节点 Spinner 的选项。
     * 关键容错：keepNode 即使不在订阅节点缓存里（订阅失效/未加载），也作为自定义项
     * 追加并选中，保证用户已选节点不丢失、保存后不会"瞬间变回未选择"。
     */
    private void updateNodeSpinnerOptions(NodeRow row, String keepNode) {
        String subName = row.subSpinner.getSelectedItemPosition() == 0 ? ""
                : row.subSpinner.getSelectedItem().toString();
        // synchronizedMap 遍历需在外部同步，避免并发修改异常
        List<String> nodes;
        if (subName.isEmpty()) {
            nodes = new ArrayList<>();
        } else {
            synchronized (subNodeCache) {
                List<String> cached = subNodeCache.get(subName);
                nodes = cached != null ? new ArrayList<>(cached) : new ArrayList<>();
            }
        }

        List<String> items = new ArrayList<>();
        items.add("（未选择）");
        items.addAll(nodes);
        // 保留当前已选节点：若不在节点列表中（订阅失效/未加载完成），追加为自定义项
        if (keepNode != null && !keepNode.isEmpty()
                && !"（未选择）".equals(keepNode) && !items.contains(keepNode)) {
            items.add(keepNode);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // 防止 setAdapter/setSelection 同步触发 onItemSelected → 递归调本方法
        row.suppressNodeRefresh = true;
        row.nodeSpinner.setAdapter(adapter);
        // 恢复选择
        int sel = 0;
        if (keepNode != null && !keepNode.isEmpty()) {
            for (int k = 0; k < items.size(); k++) {
                if (items.get(k).equals(keepNode)) { sel = k; break; }
            }
        }
        row.nodeSpinner.setSelection(sel);
        row.suppressNodeRefresh = false;
    }

    /**
     * 测试该账号所有节点行涉及订阅的全部节点延迟（healthcheck 批量测），
     * 然后更新已选节点行的延迟徽章。一个账号跨多订阅时，合并所有涉及订阅的 provider
     * 一起测，只测这些 provider，不会测到账号未涉及的其他机场节点。
     */
    private void doTestDelay(List<NodeRow> nodeRows, Button testBtn, LinearLayout card, int accIndex) {
        if (!MihomoManager.isRunning()) {
            toast("请先启动 mihomo");
            return;
        }
        // 收集该账号所有节点行选中的订阅名（去重），并映射到 provider 名
        // 只测这些 provider，避免测到账号未涉及的其他机场节点
        java.util.Set<String> subNames = new java.util.LinkedHashSet<>();
        for (NodeRow row : nodeRows) {
            if (row.subSpinner.getSelectedItemPosition() > 0) {
                subNames.add(row.subSpinner.getSelectedItem().toString());
            }
        }
        if (subNames.isEmpty()) {
            toast("请先为至少一个节点选择订阅");
            return;
        }
        final List<String> providerNames = new ArrayList<>();
        for (String sn : subNames) {
            String pn = subNameToProvider.get(sn);
            // 兜底：映射缺失时从 mihomoConfig 重建
            if (pn == null) {
                JSONArray subs = mihomoConfig.optJSONArray("subscriptions");
                if (subs != null) {
                    for (int i = 0; i < subs.length(); i++) {
                        JSONObject s = subs.optJSONObject(i);
                        if (s != null && sn.equals(s.optString("name", "订阅" + (i + 1)))) {
                            pn = "sub-" + i;
                            break;
                        }
                    }
                }
            }
            if (pn != null && !providerNames.contains(pn)) providerNames.add(pn);
        }
        final String groupName = "acc-" + accIndex;

        // 在 UI 线程上先快照每行的已选节点名 + 延迟徽章引用，
        // 避免后台线程访问 Spinner（非线程安全）和遍历 nodeRows（用户可能同时增删行）
        final List<String[]> rowSnapshots = new ArrayList<>();  // 每项 {nodeName, rowIdentity}
        final List<TextView> delayLabels = new ArrayList<>();
        for (NodeRow r : nodeRows) {
            String nodeName = "";
            if (r.nodeSpinner.getSelectedItem() != null) {
                nodeName = r.nodeSpinner.getSelectedItem().toString();
            }
            rowSnapshots.add(new String[]{nodeName, String.valueOf(System.identityHashCode(r))});
            delayLabels.add(r.delayLabel);
            // 重置徽章
            r.delayLabel.setText("···");
            r.delayLabel.setTextColor(Color.parseColor(COLOR_GRAY));
            r.delayLabel.setBackground(roundedBackground("#F1F5F9", COLOR_DIVIDER, dp(10)));
        }

        testBtn.setEnabled(false);
        testBtn.setText("测试中...");

        new Thread(() -> {
            // 批量测试该账号涉及的所有订阅 provider 的节点延迟
            java.util.Map<String, Integer> delayMap = MihomoManager.testProvidersDelay(groupName, providerNames);
            LogStore.get().log("UI", "延迟测试完成，delayMap 大小=" + delayMap.size()
                    + "，将更新 " + rowSnapshots.size() + " 个已选节点徽章");
            // 如果 healthcheck 没拿到任何数据，回退到逐个测试已选节点
            if (delayMap.isEmpty()) {
                for (int i = 0; i < rowSnapshots.size(); i++) {
                    String nodeName = rowSnapshots.get(i)[0];
                    if ("（未选择）".equals(nodeName) || nodeName.isEmpty()) continue;
                    int delay = MihomoManager.testNodeDelay(nodeName);
                    final TextView lbl = delayLabels.get(i);
                    runOnUiThread(() -> updateDelayBadge(lbl, delay));
                }
            } else {
                // 更新已选节点的延迟徽章（用快照的节点名，不碰 Spinner）
                int matched = 0;
                for (int i = 0; i < rowSnapshots.size(); i++) {
                    String nodeName = rowSnapshots.get(i)[0];
                    final TextView lbl = delayLabels.get(i);
                    if ("（未选择）".equals(nodeName) || nodeName.isEmpty()) {
                        runOnUiThread(() -> lbl.setText("—"));
                        continue;
                    }
                    Integer delay = delayMap.get(nodeName);
                    if (delay != null) matched++;
                    int delayVal = delay != null ? delay : -1;
                    runOnUiThread(() -> updateDelayBadge(lbl, delayVal));
                }
                LogStore.get().log("UI", "延迟徽章更新完成，匹配 " + matched + "/" + rowSnapshots.size()
                        + " 个已选节点");
            }
            // 填充全节点延迟列表（按延迟升序，不可用节点排最后）
            if (!delayMap.isEmpty() && card != null) {
                Object tag = card.getTag();
                if (tag instanceof Object[]) {
                    Object[] arr = (Object[]) tag;
                    if (arr.length >= 2 && arr[0] instanceof ScrollView && arr[1] instanceof TextView) {
                        final ScrollView delayListScroll = (ScrollView) arr[0];
                        final TextView delayListText = (TextView) arr[1];
                        List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(delayMap.entrySet());
                        java.util.Collections.sort(sorted, (a, b) -> Integer.compare(a.getValue(), b.getValue()));
                        StringBuilder sb = new StringBuilder();
                        sb.append("节点延迟列表（").append(delayMap.size()).append("可用）\n\n");
                        for (java.util.Map.Entry<String, Integer> e : sorted) {
                            sb.append("● ").append(String.format("%5dms", e.getValue()))
                              .append("  ").append(e.getKey()).append("\n");
                        }
                        runOnUiThread(() -> {
                            delayListText.setText(sb.toString());
                            delayListScroll.setVisibility(View.VISIBLE);
                        });
                    }
                }
            }
            runOnUiThread(() -> {
                testBtn.setEnabled(true);
                testBtn.setText("测试全部延迟");
            });
        }, "delay-test").start();
    }

    /** 验证该账号的代理是否可用：通过 mihomo SOCKS5 端口获取出口 IP。 */
    private void doVerifyProxy(int accIndex, TextView verifyLabel) {
        if (!MihomoManager.isRunning()) {
            toast("请先启动 mihomo");
            return;
        }
        int socksPort = MihomoManager.getSocks5BasePort() + accIndex;
        verifyLabel.setText("验证中...");
        verifyLabel.setTextColor(Color.parseColor(COLOR_GRAY));
        new Thread(() -> {
            String result = MihomoManager.verifyProxyExit(socksPort);
            runOnUiThread(() -> {
                if (result != null) {
                    verifyLabel.setText("出口 IP: " + result);
                    verifyLabel.setTextColor(Color.parseColor(COLOR_GREEN));
                } else {
                    verifyLabel.setText("代理不可用（无法通过 SOCKS5 端口 " + socksPort + " 访问外网）");
                    verifyLabel.setTextColor(Color.parseColor(COLOR_RED));
                }
            });
        }, "proxy-verify").start();
    }

    /** 更新延迟徽章显示。 */
    private void updateDelayBadge(TextView label, int delay) {
        if (delay > 0) {
            label.setText(delay + "ms");
            if (delay < 300) {
                label.setTextColor(Color.parseColor(COLOR_GREEN));
                label.setBackground(roundedBackground("#DCFCE7", "#BBF7D0", dp(10)));
            } else {
                label.setTextColor(Color.parseColor("#B45309"));
                label.setBackground(roundedBackground("#FEF3C7", "#FDE68A", dp(10)));
            }
        } else {
            label.setText("超时");
            label.setTextColor(Color.parseColor(COLOR_RED));
            label.setBackground(roundedBackground("#FEE2E2", "#FECACA", dp(10)));
        }
    }

    /** 删除节点行后重新编号主/备用标签。 */
    private void renumberNodeRows(ViewGroup card, List<NodeRow> nodeRows) {
        int spinnerIdx = 0;
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            // 节点行容器(垂直) → headerRow(水平) → label(TextView)
            if (child instanceof LinearLayout) {
                LinearLayout container = (LinearLayout) child;
                if (container.getChildCount() > 0
                        && container.getChildAt(0) instanceof LinearLayout) {
                    LinearLayout headerRow = (LinearLayout) container.getChildAt(0);
                    if (headerRow.getChildCount() > 0
                            && headerRow.getChildAt(0) instanceof TextView) {
                        TextView label = (TextView) headerRow.getChildAt(0);
                        label.setText(spinnerIdx == 0 ? "主节点" : "备用 " + spinnerIdx);
                        spinnerIdx++;
                    }
                }
            }
        }
    }

    // ========== 操作 ==========

    /** 独立启动 mihomo（不依赖 ds2api）。 */
    private void doStartMihomo() {
        // 先从 UI 收集订阅信息
        try {
            collectSubscriptionsFromUi();
        } catch (Throwable t) {
            toast("收集订阅失败: " + t.getMessage());
            return;
        }
        JSONArray subs = mihomoConfig.optJSONArray("subscriptions");
        if (subs == null || subs.length() == 0) {
            toast("请至少添加一个订阅");
            return;
        }
        // 先保存到 mihomo_config.json
        try {
            mihomoConfig.put("enabled", enabledCheckbox.isChecked());
            if (!mihomoConfig.has("api_port")) {
                mihomoConfig.put("api_port", MihomoManager.DEFAULT_API_PORT);
            }
            if (!mihomoConfig.has("socks5_base_port")) {
                mihomoConfig.put("socks5_base_port", MihomoManager.DEFAULT_SOCKS5_BASE_PORT);
            }
            if (!mihomoConfig.has("subscription_update_interval")) {
                mihomoConfig.put("subscription_update_interval", 3600);
            }
            writeMihomoConfig();
        } catch (Throwable t) {
            toast("保存配置失败: " + t.getMessage());
            return;
        }

        startMihomoBtn.setEnabled(false);
        startMihomoBtn.setText("启动中...");
        new Thread(() -> {
            try {
                File workDir = new File(getFilesDir(), "mihomo");
                MihomoManager.start(this, workDir, mihomoConfig);
                // 修复 C2：start() 可能因端口冲突递增 socks5_base_port/api_port 或新生成
                // api_secret，并写回 mihomoConfig 对象。必须落盘，否则下次启动读到旧值，
                // 导致端口错位或 secret 不匹配(API 401)。与 doSave/ServerService 保持一致。
                writeMihomoConfig();
                boolean ready = MihomoManager.probeReady();
                if (ready) {
                    MihomoManager.applyNodeSelection(mihomoConfig);
                    // 修复 C4 一致性：独立启动 mihomo 后也同步 config.json 代理条目，
                    // 用当前实际 SOCKS5 端口。这样无论 ds2api 是否已运行，config.json
                    // 都指向正确端口，下次 ds2api 启动即可用。无绑定时该方法会清理旧条目。
                    File cfgFile = new File(getFilesDir(), "config.json");
                    MihomoManager.injectProxiesIntoConfig(cfgFile, mihomoConfig);
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
        // stop() 内部 synchronized + waitFor(3s) 同步等进程退出，在主线程调用会阻塞 UI
        // 触发 ANR。改为子线程执行 stop + 清理，UI 先禁用按钮显示"停止中"。
        stopMihomoBtn.setEnabled(false);
        stopMihomoBtn.setText("停止中...");
        new Thread(() -> {
            MihomoManager.stop();
            // 停止后 SOCKS5 端口不再监听，必须清理 config.json 的 mihomo-* 代理条目，
            // 否则 ds2api 仍指向死端口导致全部请求 ECONNREFUSED。
            try {
                MihomoManager.clearProxiesFromConfig(new File(getFilesDir(), "config.json"));
            } catch (Throwable ignored) {}
            runOnUiThread(() -> {
                if (isFinishing()) return;
                refreshMihomoStatus();
                subNodeCache.clear();
                stopMihomoBtn.setEnabled(true);
                stopMihomoBtn.setText("停止");
                toast("mihomo 已停止");
            });
        }, "mihomo-stopper").start();
    }

    private void doRefresh() {
        if (!MihomoManager.isRunning()) {
            toast("请先启动 mihomo");
            return;
        }
        toast("正在重新下载订阅...");
        new Thread(() -> {
            // file provider 无法通过 API 刷新，必须 App 层重新下载订阅文件 + 热重载
            int ok = MihomoManager.redownloadAllSubscriptions(mihomoConfig);
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            fetchNodes();
            runOnUiThread(() -> toast(ok > 0
                    ? "订阅已刷新（" + ok + " 个成功）"
                    : "刷新失败，请检查日志（可能是 UA 被拦）"));
        }, "sub-refresh").start();
    }

    private void doSave() {
        try {
            // 先收集订阅
            collectSubscriptionsFromUi();
            mihomoConfig.put("enabled", enabledCheckbox.isChecked());

            // 收集账号绑定：每个节点带所属订阅名（支持跨订阅备用）
            JSONArray bindings = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < accountIdentifiers.size(); i++) {
                String identifier = accountIdentifiers.get(i);
                if (!seen.add(identifier)) continue;
                List<NodeRow> rows = accountNodeRows.get(i);
                JSONArray nodesArr = new JSONArray();
                for (NodeRow row : rows) {
                    String subName = "";
                    if (row.subSpinner.getSelectedItemPosition() > 0) {
                        subName = row.subSpinner.getSelectedItem().toString().trim();
                    }
                    Object sel = row.nodeSpinner.getSelectedItem();
                    if (sel == null) continue;
                    String nodeName = sel.toString().trim();
                    if ("（未选择）".equals(nodeName) || nodeName.isEmpty()) continue;
                    JSONObject n = new JSONObject();
                    n.put("subscription", subName);
                    n.put("name", nodeName);
                    nodesArr.put(n);
                }
                if (nodesArr.length() == 0) continue;
                JSONObject b = new JSONObject();
                b.put("account_identifier", identifier);
                b.put("nodes", nodesArr);
                b.put("current_node_index", 0);
                bindings.put(b);
            }
            mihomoConfig.put("account_bindings", bindings);
            writeMihomoConfig();
            toast("配置已保存，正在重启 mihomo 使新端口生效...");

            // 关键：热重载（reloadConfig）不会重新绑定 listeners 端口，
            // 新增账号对应的 SOCKS5 端口（7891/7892...）无法监听 → ECONNREFUSED。
            // 因此保存配置后必须重启 mihomo 子进程，让新端口绑定生效。
            if (MihomoManager.isRunning()) {
                new Thread(() -> {
                    try {
                        MihomoManager.stop();
                        Thread.sleep(800); // 等待端口释放
                        File workDir = new File(getFilesDir(), "mihomo");
                        MihomoManager.start(this, workDir, mihomoConfig);
                        // start() 可能因端口冲突递增 socks5_base_port/api_port，或新生成
                        // api_secret，并写回 mihomoConfig 对象。必须落盘，否则下次启动读到旧值。
                        // 修复 C2：原版 start() 生成的 secret/调整的端口只进内存不持久化。
                        writeMihomoConfig();
                        boolean ready = MihomoManager.probeReady();
                        if (ready) {
                            MihomoManager.applyNodeSelection(mihomoConfig);
                            // 修复 C4：重启后 mihomo 的 SOCKS5 端口可能已变（端口避让），
                            // 必须重新注入 Proxy 到 config.json，否则 ds2api 仍指向旧端口。
                            // 即使 ds2api 当前未运行，下次启动 ServerService 会读到正确的代理端口。
                            File cfgFile = new File(getFilesDir(), "config.json");
                            MihomoManager.injectProxiesIntoConfig(cfgFile, mihomoConfig);
                            fetchNodes();
                        } else {
                            // 未就绪：清理死代理，避免 config.json 残留指向未监听端口的条目
                            File cfgFile = new File(getFilesDir(), "config.json");
                            MihomoManager.clearProxiesFromConfig(cfgFile);
                        }
                        final boolean finalReady = ready;
                        runOnUiThread(() -> {
                            refreshMihomoStatus();
                            toast(finalReady ? "已保存并重启 mihomo" : "重启失败，请查看日志");
                        });
                    } catch (Throwable t2) {
                        runOnUiThread(() -> toast("重启失败: " + t2.getMessage()));
                    }
                }, "mihomo-restart").start();
            } else {
                // mihomo 未运行时保存：若 enabled 被取消勾选或此前注入过代理，
                // 必须清理 config.json 的 mihomo-* 死代理条目，否则 ds2api 下次启动
                // 仍指向未监听的 SOCKS5 端口导致全部 ECONNREFUSED。
                new Thread(() -> {
                    try {
                        MihomoManager.clearProxiesFromConfig(new File(getFilesDir(), "config.json"));
                    } catch (Throwable ignored) {}
                }, "proxy-cleanup").start();
            }
        } catch (Throwable t) {
            toast("保存失败: " + t.getMessage());
        }
    }

    // ========== 状态刷新 ==========

    private void refreshMihomoStatus() {
        int exitCode = MihomoManager.getLastExitCode();
        if (MihomoManager.isRunning()) {
            mihomoStatusLabel.setText("● 运行中 · 控制端口 :" + MihomoManager.getApiPort());
            mihomoStatusLabel.setTextColor(Color.parseColor(COLOR_GREEN));
            startMihomoBtn.setEnabled(false);
            stopMihomoBtn.setEnabled(true);
        } else if (exitCode >= 0) {
            // 进程曾启动但已退出（通常是配置错误导致 crash）
            mihomoStatusLabel.setText("● 启动失败 · 进程已退出 (code=" + exitCode + ")，请查看日志");
            mihomoStatusLabel.setTextColor(Color.parseColor("#DC2626"));
            startMihomoBtn.setEnabled(true);
            stopMihomoBtn.setEnabled(false);
        } else {
            mihomoStatusLabel.setText("● 未运行");
            mihomoStatusLabel.setTextColor(Color.parseColor(COLOR_GRAY));
            startMihomoBtn.setEnabled(true);
            stopMihomoBtn.setEnabled(false);
        }
        nodeBindingSection.setVisibility(View.VISIBLE);
    }

    private void fetchNodes() {
        // 并发保护：onCreate 与 onResume 可能同时触发，避免两个线程并发 clear/put 缓存
        // 导致 LinkedHashMap 内部链表损坏（ConcurrentModificationException）→ 进程崩溃且无日志
        // AtomicBoolean.compareAndSet 原子操作，消除 check-then-set 竞态
        if (!fetchNodesRunning.compareAndSet(false, true)) return;
        try {
            if (!MihomoManager.isRunning()) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    try { buildAccountBindings(); } catch (Throwable t) {
                        android.util.Log.e("ProxyConfig", "重建绑定区失败", t);
                    }
                });
                return;
            }
            // 构建订阅名 → providerName 映射。先构建到局部临时 Map，再整体替换字段，
            // 缩短字段被锁的时间，减少与主线程读的竞争。
            java.util.Map<String, String> tmpMap = new java.util.LinkedHashMap<>();
            JSONArray subs = mihomoConfig.optJSONArray("subscriptions");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject s = subs.optJSONObject(i);
                    if (s == null) continue;
                    String name = s.optString("name", "订阅" + (i + 1));
                    tmpMap.put(name, "sub-" + i);
                }
            }
            // 拉取每个订阅的节点列表到临时 Map
            java.util.Map<String, List<String>> tmpCache = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> e : tmpMap.entrySet()) {
                // provider 不存在（订阅失效）时返回空列表，不抛异常
                List<String> nodes = MihomoManager.fetchNodeList(e.getValue());
                tmpCache.put(e.getKey(), nodes);
            }
            // 整体替换字段（synchronizedMap 自带同步）
            subNameToProvider.clear();
            subNameToProvider.putAll(tmpMap);
            subNodeCache.clear();
            subNodeCache.putAll(tmpCache);

            runOnUiThread(() -> {
                if (isFinishing()) return;
                // 重建绑定区：已选节点靠 preset 保留机制恢复（即使订阅暂时失效也不丢失）
                try {
                    buildAccountBindings();
                } catch (Throwable t) {
                    android.util.Log.e("ProxyConfig", "重建绑定区失败", t);
                    toast("加载节点绑定失败: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            // 后台线程未捕获异常会导致整个进程崩溃且无应用日志，必须兜住
            android.util.Log.e("ProxyConfig", "fetchNodes 失败", t);
            LogStore.get().log("UI", "拉取节点失败: " + t.getMessage());
        } finally {
            fetchNodesRunning.set(false);
        }
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

    /** 区块标题：M3 title-medium 风格，左侧带主色色条。返回包含色条+文字的容器。 */
    private View makeSectionTitle(String text) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        wrap.setLayoutParams(lp);

        View bar = new View(this);
        bar.setBackgroundColor(Color.parseColor(COLOR_PRIMARY));
        wrap.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(16), 0));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor(COLOR_TEXT));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(-2, -2);
        tvLp.leftMargin = dp(8);
        wrap.addView(tv, tvLp);
        return wrap;
    }

    private View makeDivider() {
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor(COLOR_DIVIDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(4);
        div.setLayoutParams(lp);
        return div;
    }

    private Button makeButton(String text, View.OnClickListener l) {
        return makeSecondaryButton(text, l);
    }

    /** 主操作按钮：M3 filled —— 主色填充 + 白色文字 + 较大圆角。 */
    private Button makePrimaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setOnClickListener(l);
        b.setPadding(dp(24), dp(10), dp(24), dp(10));
        b.setBackground(roundedBackground(COLOR_PRIMARY, COLOR_PRIMARY_DARK, dp(12)));
        b.setElevation(dp(2));
        return b;
    }

    /** 次操作按钮：M3 outlined —— 透明底 + 边框 + 主色文字 + 圆角。 */
    private Button makeSecondaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(COLOR_PRIMARY_DARK));
        b.setOnClickListener(l);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setBackground(roundedBackground("#FFFFFF", COLOR_BTN_SECONDARY_BORDER, dp(12)));
        return b;
    }

    /** 圆角背景：填充色 + 边框色 + 圆角半径。 */
    private GradientDrawable roundedBackground(String fillColor, String strokeColor, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radius);
        d.setColor(Color.parseColor(fillColor));
        d.setStroke(dp(1), Color.parseColor(strokeColor));
        return d;
    }

    /** 卡片背景：M3 surface —— 纯白 + 细边框 + 16dp 圆角 + 轻阴影。 */
    private GradientDrawable cardBackground() {
        return roundedBackground(COLOR_CARD_BG, COLOR_DIVIDER, dp(16));
    }

    private void writeConfig() throws Exception {
        atomicWrite(new File(getFilesDir(), "config.json"),
                config.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    /** 将 mihomo 配置写入独立文件 mihomo_config.json，避免被 Go 服务端覆盖。 */
    private void writeMihomoConfig() throws Exception {
        atomicWrite(new File(getFilesDir(), "mihomo_config.json"),
                mihomoConfig.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 原子写入：先写 .tmp 临时文件并 fsync，再 rename 覆盖目标文件。
     * 避免进程被杀/中途崩溃时配置文件被写一半导致损坏丢失。
     */
    private void atomicWrite(File target, byte[] data) throws Exception {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            try { out.getFD().sync(); } catch (Throwable ignored) {}
        }
        // rename 原子替换；若失败则尝试删除目标再重命名兜底
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(target);
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
