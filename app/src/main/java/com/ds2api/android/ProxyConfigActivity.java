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
    /** 订阅名 → 节点列表缓存 */
    private final java.util.Map<String, List<String>> subNodeCache = new java.util.LinkedHashMap<>();
    /** 订阅名 → provider 名（sub-{index}）映射，测延迟时只测选中订阅的节点 */
    private final java.util.Map<String, String> subNameToProvider = new java.util.LinkedHashMap<>();
    /** 订阅名列表（按添加顺序） */
    private final List<String> subscriptionNames = new ArrayList<>();
    /** 每个账号的 Spinner 组 */
    private final List<List<Spinner>> accountSpinners = new ArrayList<>();
    private final List<List<TextView>> accountDelayLabels = new ArrayList<>();
    private final List<String> accountIdentifiers = new ArrayList<>();
    /** 每个账号的订阅选择 Spinner */
    private final List<Spinner> accountSubSpinners = new ArrayList<>();

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
        refreshMihomoStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMihomoStatus();
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
     * 刷新所有账号卡片的"订阅选择"Spinner 选项，保留各账号当前已选订阅名。
     * 用于：新增/删除订阅、订阅改名后保持账号下拉与订阅列表一致。
     */
    private void refreshAccountSubSpinners() {
        List<String> subNames = getSubscriptionNames();
        for (Spinner sp : accountSubSpinners) {
            String current = sp.getSelectedItemPosition() > 0
                    ? String.valueOf(sp.getSelectedItem()) : "";
            List<String> options = new ArrayList<>();
            options.add("（未选择）");
            options.addAll(subNames);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(adapter);
            int sel = 0;
            for (int k = 0; k < options.size(); k++) {
                if (options.get(k).equals(current)) { sel = k; break; }
            }
            sp.setSelection(sel);
        }
    }

    /** 从 UI 收集订阅列表写入 mihomoConfig。 */
    private void collectSubscriptionsFromUi() throws Exception {
        JSONArray subs = new JSONArray();
        for (int i = 0; i < subscriptionListContainer.getChildCount(); i++) {
            View child = subscriptionListContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                Object[] tags = (Object[]) child.getTag();
                if (tags == null || tags.length < 3) continue;
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
        accountSpinners.clear();
        accountDelayLabels.clear();
        accountIdentifiers.clear();
        accountSubSpinners.clear();

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

            JSONArray existingNodes = null;
            String existingSubName = "";
            for (int j = 0; j < bindings.length(); j++) {
                JSONObject b = bindings.optJSONObject(j);
                if (b != null && identifier.equals(b.optString("account_identifier", ""))) {
                    existingNodes = b.optJSONArray("node_names");
                    existingSubName = b.optString("subscription_name", "");
                    break;
                }
            }

            accountIdentifiers.add(identifier);
            accountListContainer.addView(buildAccountCard(i, identifier, existingNodes,
                    existingSubName, subNames));
        }
    }

    /** 从 UI 收集当前所有订阅名（已填了名称的）。 */
    private List<String> getSubscriptionNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < subscriptionListContainer.getChildCount(); i++) {
            View child = subscriptionListContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                Object[] tags = (Object[]) child.getTag();
                if (tags == null || tags.length < 3) continue;
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

    /** 构建单个账号卡片。 */
    private View buildAccountCard(int accIndex, String identifier, JSONArray existingNodes,
                                   String presetSubName, List<String> subNames) {
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

        // 订阅选择行
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams subRowLp = new LinearLayout.LayoutParams(-1, -2);
        subRowLp.topMargin = dp(6);
        subRow.setLayoutParams(subRowLp);

        TextView subLabel = new TextView(this);
        subLabel.setText("订阅");
        subLabel.setTextColor(Color.parseColor(COLOR_TEXT_LIGHT));
        subLabel.setTextSize(13);
        subLabel.setMinWidth(dp(50));
        subRow.addView(subLabel);

        Spinner subSpinner = new Spinner(this);
        List<String> subOptions = new ArrayList<>();
        subOptions.add("（未选择）");
        subOptions.addAll(subNames);
        ArrayAdapter<String> subAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, subOptions);
        subAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        subSpinner.setAdapter(subAdapter);
        if (!presetSubName.isEmpty()) {
            for (int k = 0; k < subOptions.size(); k++) {
                if (subOptions.get(k).equals(presetSubName)) {
                    subSpinner.setSelection(k);
                    break;
                }
            }
        }
        accountSubSpinners.add(subSpinner);
        subRow.addView(subSpinner, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(subRow);

        List<Spinner> spinners = new ArrayList<>();
        List<TextView> delayLabels = new ArrayList<>();
        accountSpinners.add(spinners);
        accountDelayLabels.add(delayLabels);

        // 切换订阅时刷新该卡片所有节点 Spinner 选项
        subSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                refreshNodeOptionsForCard(card, spinners);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        int nodeCount = (existingNodes != null && existingNodes.length() > 0)
                ? existingNodes.length() : 1;
        for (int n = 0; n < nodeCount; n++) {
            String preset = (existingNodes != null && n < existingNodes.length())
                    ? existingNodes.optString(n, "") : "";
            card.addView(buildNodeRow(spinners, delayLabels, n, preset, card));
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
            int idx = spinners.size();
            card.addView(buildNodeRow(spinners, delayLabels, idx, "", card), card.getChildCount() - 1);
        });
        btnRow.addView(addBtn, new LinearLayout.LayoutParams(-2, dp(38)));

        Button testBtn = makeSecondaryButton("测试延迟", v -> {});
        testBtn.setOnClickListener(v -> doTestDelay(spinners, delayLabels, testBtn, subSpinner, card));
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

    /** 根据账号卡片中选中的订阅，刷新该卡片所有节点 Spinner 的选项。 */
    private void refreshNodeOptionsForCard(LinearLayout card, List<Spinner> spinners) {
        int subSpinnerIdx = -1;
        for (int i = 0; i < accountListContainer.getChildCount(); i++) {
            if (accountListContainer.getChildAt(i) == card) {
                subSpinnerIdx = i;
                break;
            }
        }
        if (subSpinnerIdx < 0 || subSpinnerIdx >= accountSubSpinners.size()) return;
        Spinner subSpinner = accountSubSpinners.get(subSpinnerIdx);
        String subName = subSpinner.getSelectedItemPosition() == 0 ? ""
                : subSpinner.getSelectedItem().toString();
        List<String> nodes = subName.isEmpty() ? new ArrayList<>() : subNodeCache.get(subName);
        if (nodes == null) nodes = new ArrayList<>();
        for (Spinner s : spinners) {
            updateSpinnerAdapterWithNodes(s, nodes);
        }
    }

    private View buildNodeRow(List<Spinner> spinners, List<TextView> delayLabels,
                              int nodeIndex, String preset, ViewGroup parentCard) {
        // 容器：垂直两行布局，避免横向拥挤导致延迟标签被遮挡
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(-1, -2);
        containerLp.topMargin = dp(8);
        container.setLayoutParams(containerLp);

        // 先创建 Spinner（删除按钮的 lambda 需要引用它）
        Spinner spinner = new Spinner(this);
        updateSpinnerAdapter(spinner);
        if (!preset.isEmpty()) {
            // 在所有订阅节点中查找匹配的预设节点名
            for (List<String> nodes : subNodeCache.values()) {
                for (int i = 0; i < nodes.size(); i++) {
                    if (nodes.get(i).equals(preset)) {
                        spinner.setSelection(i + 1);  // +1 因为第 0 项是"未选择"
                        break;
                    }
                }
                if (spinner.getSelectedItem() != null
                        && spinner.getSelectedItem().toString().equals(preset)) {
                    break;
                }
            }
        }
        spinners.add(spinner);

        // 延迟徽章（带圆角背景，醒目可见）
        TextView delayLabel = new TextView(this);
        delayLabel.setTextSize(12);
        delayLabel.setTextColor(Color.parseColor(COLOR_GRAY));
        delayLabel.setGravity(Gravity.CENTER);
        delayLabel.setPadding(dp(10), dp(2), dp(10), dp(2));
        delayLabel.setText("—");
        delayLabel.setBackground(roundedBackground("#F1F5F9", COLOR_DIVIDER, dp(10)));
        delayLabels.add(delayLabel);

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

        // 弹性占位，把删除按钮推到右边
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
                spinners.remove(spinner);
                delayLabels.remove(delayLabel);
                parentCard.removeView(container);
                renumberNodeRows(parentCard, spinners);
            });
            headerRow.addView(delBtn, new LinearLayout.LayoutParams(-2, dp(32)));
        }

        container.addView(headerRow);

        // 第二行：Spinner 占满整行，有足够空间显示节点名
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(-1, -2);
        spinLp.topMargin = dp(2);
        container.addView(spinner, spinLp);

        return container;
    }

    /**
     * 测试该账号所选订阅的全部节点延迟（用 group delay API 批量测试），
     * 然后更新已选节点行的延迟徽章。
     */
    private void doTestDelay(List<Spinner> spinners, List<TextView> delayLabels,
                             Button testBtn, Spinner subSpinner, LinearLayout card) {
        if (!MihomoManager.isRunning()) {
            toast("请先启动 mihomo");
            return;
        }
        // 获取该账号选中的订阅
        String subName = "";
        if (subSpinner.getSelectedItemPosition() > 0) {
            subName = subSpinner.getSelectedItem().toString();
        }
        if (subName.isEmpty()) {
            toast("请先选择订阅");
            return;
        }

        // 找到该订阅对应的 group 名（acc-{index}）
        int accIdx = accountSubSpinners.indexOf(subSpinner);
        String groupName = "acc-" + accIdx;
        // 查找该订阅对应的 provider 名（sub-{index}），只测此订阅节点，避免测到其他机场
        String providerName = subNameToProvider.get(subName);
        // 兜底：若映射缺失（如订阅改名后未重启 mihomo），从 mihomoConfig 重建
        if (providerName == null) {
            JSONArray subs = mihomoConfig.optJSONArray("subscriptions");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject s = subs.optJSONObject(i);
                    if (s != null && subName.equals(s.optString("name", "订阅" + (i + 1)))) {
                        providerName = "sub-" + i;
                        break;
                    }
                }
            }
        }
        // lambda 要求 effectively final，赋值给 final 副本
        final String finalProviderName = providerName;

        testBtn.setEnabled(false);
        testBtn.setText("测试中...");
        // 先重置所有徽章
        for (TextView l : delayLabels) {
            l.setText("···");
            l.setTextColor(Color.parseColor(COLOR_GRAY));
            l.setBackground(roundedBackground("#F1F5F9", COLOR_DIVIDER, dp(10)));
        }

        new Thread(() -> {
            // 用 healthcheck 机制批量测试该订阅所有节点延迟（只测选中订阅，不测其他机场）
            java.util.Map<String, Integer> delayMap = MihomoManager.testGroupDelay(groupName, finalProviderName);
            LogStore.get().log("UI", "延迟测试完成，delayMap 大小=" + delayMap.size()
                    + "，将更新 " + spinners.size() + " 个已选节点徽章");
            // 如果 healthcheck 没拿到任何数据，回退到逐个测试已选节点
            if (delayMap.isEmpty()) {
                for (int i = 0; i < spinners.size(); i++) {
                    Spinner s = spinners.get(i);
                    if (i >= delayLabels.size()) break;
                    TextView label = delayLabels.get(i);
                    Object sel = s.getSelectedItem();
                    if (sel == null) { continue; }
                    String nodeName = sel.toString();
                    if ("（未选择）".equals(nodeName) || nodeName.isEmpty()) { continue; }
                    int delay = MihomoManager.testNodeDelay(nodeName);
                    int finalI = i;
                    runOnUiThread(() -> updateDelayBadge(delayLabels.get(finalI), delay));
                }
            } else {
                // 更新已选节点的延迟徽章
                int matched = 0;
                for (int i = 0; i < spinners.size(); i++) {
                    Spinner s = spinners.get(i);
                    if (i >= delayLabels.size()) break;
                    TextView label = delayLabels.get(i);
                    Object sel = s.getSelectedItem();
                    if (sel == null) { continue; }
                    String nodeName = sel.toString();
                    if ("（未选择）".equals(nodeName) || nodeName.isEmpty()) {
                        runOnUiThread(() -> label.setText("—"));
                        continue;
                    }
                    Integer delay = delayMap.get(nodeName);
                    if (delay != null) matched++;
                    int finalI = i;
                    int delayVal = delay != null ? delay : -1;
                    runOnUiThread(() -> updateDelayBadge(delayLabels.get(finalI), delayVal));
                }
                LogStore.get().log("UI", "延迟徽章更新完成，匹配 " + matched + "/" + spinners.size()
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

    private void renumberNodeRows(ViewGroup card, List<Spinner> spinners) {
        int spinnerIdx = 0;
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            // 新布局：container(垂直) → headerRow(水平) → label(TextView)
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

    private void updateSpinnerAdapter(Spinner spinner) {
        updateSpinnerAdapterWithNodes(spinner, null);
    }

    /** 用指定节点列表更新 Spinner，nodes 为 null 时使用第一个订阅的节点。 */
    private void updateSpinnerAdapterWithNodes(Spinner spinner, List<String> nodes) {
        if (nodes == null) {
            // 默认用缓存中第一个订阅的节点
            if (!subNodeCache.isEmpty()) {
                nodes = subNodeCache.values().iterator().next();
            } else {
                nodes = new ArrayList<>();
            }
        }
        // 保留当前选择
        String current = null;
        if (spinner.getAdapter() != null && spinner.getSelectedItem() != null) {
            current = spinner.getSelectedItem().toString();
        }
        List<String> items = new ArrayList<>();
        items.add("（未选择）");
        items.addAll(nodes);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // 恢复选择
        if (current != null) {
            for (int k = 0; k < items.size(); k++) {
                if (items.get(k).equals(current)) {
                    spinner.setSelection(k);
                    break;
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
                boolean ready = MihomoManager.probeReady();
                if (ready) {
                    MihomoManager.applyNodeSelection(mihomoConfig);
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
        subNodeCache.clear();
        toast("mihomo 已停止");
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

            // 收集账号绑定（含订阅名）
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
                // 获取该账号选中的订阅名
                String subName = "";
                if (i < accountSubSpinners.size()) {
                    Spinner subSpinner = accountSubSpinners.get(i);
                    if (subSpinner.getSelectedItemPosition() > 0) {
                        subName = subSpinner.getSelectedItem().toString();
                    }
                }
                if (nodeNames.isEmpty() && subName.isEmpty()) continue;
                JSONObject b = new JSONObject();
                b.put("account_identifier", identifier);
                b.put("subscription_name", subName);
                b.put("node_names", new JSONArray(nodeNames));
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
                        boolean ready = MihomoManager.probeReady();
                        if (ready) {
                            MihomoManager.applyNodeSelection(mihomoConfig);
                            fetchNodes();
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
        if (!MihomoManager.isRunning()) {
            runOnUiThread(() -> buildAccountBindings());
            return;
        }
        // 获取所有 provider 名，逐个拉取节点
        List<String> providers = MihomoManager.fetchAllProviderNames();
        // 构建订阅名 → providerName 映射（存入字段，测延迟时按订阅隔离）
        subNameToProvider.clear();
        JSONArray subs = mihomoConfig.optJSONArray("subscriptions");
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s == null) continue;
                String name = s.optString("name", "订阅" + (i + 1));
                String providerName = "sub-" + i;
                subNameToProvider.put(name, providerName);
            }
        }

        subNodeCache.clear();
        int totalNodes = 0;
        for (String subName : subNameToProvider.keySet()) {
            String providerName = subNameToProvider.get(subName);
            List<String> nodes = MihomoManager.fetchNodeList(providerName);
            subNodeCache.put(subName, nodes);
            totalNodes += nodes.size();
        }

        runOnUiThread(() -> {
            // 重建绑定区：节点列表已就绪，preset 能正确匹配
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
