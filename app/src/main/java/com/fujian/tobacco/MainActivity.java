package com.fujian.tobacco;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fujian.tobacco.data.model.SupplyItem;
import com.fujian.tobacco.ui.login.LoginActivity;
import com.google.android.material.button.MaterialButton;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private AutoCompleteTextView actvTier;
    private Spinner spinnerType;
    private TextView tvStatus;
    private MaterialButton btnRefresh, btnLogin;
    private ProgressBar progress;
    private RecyclerView rvList;
    private SupplyAdapter adapter;

    private View tabQuery, tabFile, tabJson;
    private TextView tvFileName, tvFileInfo, tvJson;
    private MaterialButton btnOpenFile;

    private Map<String, Object> parsedData;
    private int selectedTier = 15;
    private String currentType = "档级投放";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Gson gson = new Gson();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        actvTier = findViewById(R.id.actv_tier);
        spinnerType = findViewById(R.id.spinner_type);
        tvStatus = findViewById(R.id.tv_status);
        progress = findViewById(R.id.progress);
        rvList = findViewById(R.id.rv_list);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnLogin = findViewById(R.id.btn_login);
        btnOpenFile = findViewById(R.id.btn_open_file);
        tvFileName = findViewById(R.id.tv_file_name);
        tvFileInfo = findViewById(R.id.tv_file_info);
        tvJson = findViewById(R.id.tv_json);
        tabQuery = findViewById(R.id.tab_query);
        tabFile = findViewById(R.id.tab_file);
        tabJson = findViewById(R.id.tab_json);

        setupTierSelector();
        setupTypeSpinner();
        setupNav();
        adapter = new SupplyAdapter();
        rvList.setLayoutManager(new LinearLayoutManager(this));
        rvList.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> checkAndDownload());
        btnLogin.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        btnOpenFile.setOnClickListener(v -> openXlsxFile());

        updateLoginUI();
        loadJsonFromAssets();
    }

    // ========== 导航 ==========
    private void setupNav() {
        findViewById(R.id.nav_query).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.nav_file).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.nav_json).setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int idx) {
        tabQuery.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        tabFile.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        tabJson.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.nav_query).setAlpha(idx == 0 ? 1f : 0.5f);
        findViewById(R.id.nav_file).setAlpha(idx == 1 ? 1f : 0.5f);
        findViewById(R.id.nav_json).setAlpha(idx == 2 ? 1f : 0.5f);
        if (idx == 1) updateFileTab();
        if (idx == 2) updateJsonTab();
    }

    // ========== 类型 ==========
    private void setupTypeSpinner() {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"档级投放", "标签投放", "雪茄投放"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerType.setAdapter(a);
        spinnerType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                currentType = new String[]{"档级投放", "标签投放", "雪茄投放"}[pos];
                findViewById(R.id.card_tier).setVisibility(
                        "标签投放".equals(currentType) ? View.GONE : View.VISIBLE);
                showTierResults();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    // ========== 挡位 ==========
    private void setupTierSelector() {
        String[] tiers = new String[30];
        for (int i = 0; i < 30; i++) tiers[i] = (i + 1) + " 档";
        actvTier.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, tiers));
        actvTier.setText("15 档", false);
        actvTier.setThreshold(0);
        actvTier.setOnClickListener(v -> actvTier.showDropDown());
        actvTier.setOnItemClickListener((p, v, pos, id) -> {
            selectedTier = pos + 1;
            showTierResults();
        });
    }

    // ========== 登录状态 ==========
    @Override protected void onResume() { super.onResume(); updateLoginUI(); updateFileTab(); }

    private void updateLoginUI() {
        SharedPreferences prefs = getSharedPreferences("tobacco", MODE_PRIVATE);
        boolean loggedIn = !prefs.getString("cookie", "").isEmpty();
        String period = parsedData != null ? (String) parsedData.getOrDefault("投放时间", "") : "";
        btnLogin.setText(loggedIn ? "已登录 ✓" : "登录");
        if (!period.isEmpty()) tvStatus.setText(period);
    }

    // ========== 加载 JSON ==========
    private void loadJsonFromAssets() {
        tvStatus.setText("正在加载数据...");
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                // 优先用缓存
                File cached = new File(getCacheDir(), "strategy.json");
                if (!cached.exists()) {
                    try (InputStream is = getAssets().open("strategy.json");
                         FileOutputStream fos = new FileOutputStream(cached)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                String json = readFile(cached);
                parsedData = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
                handler.post(() -> {
                    progress.setVisibility(View.GONE);
                    String period = (String) parsedData.getOrDefault("投放时间", "?");
                    Toast.makeText(MainActivity.this, "数据加载完成: " + period + " " + json.length() + "字", Toast.LENGTH_LONG).show();
                    showTierResults();
                });
            } catch (Exception e) {
                handler.post(() -> { tvStatus.setText("加载失败: " + e.getMessage()); progress.setVisibility(View.GONE); });
            }
        });
    }

    private String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    // ========== 渲染 ==========
    @SuppressWarnings("unchecked")
    private void showTierResults() {
        if (parsedData == null) return;
        tvStatus.setText((String) parsedData.getOrDefault("投放时间", ""));
        adapter.updateData(new ArrayList<>(), null);

        try {
            if ("标签投放".equals(currentType)) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) parsedData.get("标签投放");
                if (list == null || list.isEmpty()) { showEmpty(); rvList.setVisibility(View.GONE); return; }
                adapter.setMixedData(toItemsFromStrategies(list));
            } else if ("雪茄投放".equals(currentType)) {
                adapter.updateData(new ArrayList<>(), null);
                rvList.setVisibility(View.GONE);
                tvStatus.setText("雪茄投放 · 等待更新");
            } else {
                Map<String, Object> d = (Map<String, Object>) parsedData.get("档级投放");
                if (d == null) { showEmpty(); rvList.setVisibility(View.GONE); return; }
                Map<String, Object> sm = (Map<String, Object>) d.get("档位汇总");
                if (sm == null) { showEmpty(); rvList.setVisibility(View.GONE); return; }
                Map<String, Object> tier = (Map<String, Object>) sm.get(selectedTier + "档");
                if (tier == null) { showEmpty(); rvList.setVisibility(View.GONE); return; }
                List<Map<String, Object>> list = (List<Map<String, Object>>) tier.get("卷烟列表");
                if (list == null || list.isEmpty()) { showEmpty(); rvList.setVisibility(View.GONE); return; }
                List<SupplyItem> items = toItemsFromList(list);

                // 条件策略 → 混入列表
                List<SupplyItem> condItems = new ArrayList<>();
                List<Map<String, Object>> conds = (List<Map<String, Object>>) d.get("条件投放策略");
                if (conds != null) {
                    for (Map<String, Object> c : conds) {
                        SupplyItem ci = new SupplyItem();
                        ci.setProductCode("COND_" + c.getOrDefault("关联卷烟代码", ""));
                        ci.setBrandName((String) c.getOrDefault("关联卷烟名称", ""));
                        ci.setSupplyNote((String) c.getOrDefault("投放策略说明", ""));
                        condItems.add(ci);
                    }
                }
                adapter.updateData(items, condItems);
                Toast.makeText(MainActivity.this, selectedTier + "档 " + items.size() + "款", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) { showEmpty(); rvList.setVisibility(View.GONE); return; }
        rvList.setVisibility(View.VISIBLE);
    }

    private List<SupplyItem> toItemsFromList(List<Map<String, Object>> list) {
        List<SupplyItem> items = new ArrayList<>();
        for (Map<String, Object> o : list) {
            SupplyItem item = new SupplyItem();
            item.setProductCode(str(o, "卷烟代码"));
            item.setBrandName(str(o, "卷烟名称"));
            item.setPersonalQuota(toInt(o.get("投放数量(条)")));
            item.setTotalRetailers(toInt(o.get("投放户数")));
            item.setRegion(str(o, "区域"));
            item.setSupplyNote(str(o, "备注"));
            items.add(item);
        }
        return items;
    }

    private List<Object> toItemsFromStrategies(List<Map<String, Object>> strategies) {
        List<Object> items = new ArrayList<>();
        for (Map<String, Object> s : strategies) {
            String desc = str(s, "策略说明");
            String num = str(s, "策略编号");
            items.add("策略" + num + ": " + desc);
            List<Map<String, Object>> cigList = (List<Map<String, Object>>) s.get("卷烟列表");
            if (cigList != null) {
                for (Map<String, Object> c : cigList) {
                    SupplyItem item = new SupplyItem();
                    item.setBrandName(str(c, "卷烟名称"));
                    item.setPersonalQuota(toInt(c.get("投放数量(条)")));
                    // 档级\n订购量
                    String grade = c.get("档级") != null ? c.get("档级").toString() : "null";
                    String orderQty = c.get("订购量") != null ? c.get("订购量").toString() : "null";
                    item.setTierCategory("档级: " + grade + "\n订购量: " + orderQty);
                    items.add(item);
                }
            }
        }
        return items;
    }

    private void showEmpty() { adapter.updateData(new ArrayList<>(), null); }
    private String str(Map<String, Object> o, String k) { Object v = o.get(k); return v != null ? v.toString() : ""; }
    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return (int) Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    // ========== 文件 Tab ==========
    private void updateFileTab() {
        SharedPreferences prefs = getSharedPreferences("tobacco", MODE_PRIVATE);
        String fn = prefs.getString("cache_filename", "supply_strategy.xlsx");
        File f = new File(getCacheDir(), fn);
        if (f.exists()) {
            tvFileName.setText(fn);
            tvFileInfo.setText("📏 " + String.format("%.1f KB", f.length()/1024.0) + "  📅 " + prefs.getString("cache_period", ""));
            btnOpenFile.setEnabled(true);
        } else {
            tvFileName.setText("尚未下载策略文件");
            tvFileInfo.setText("请先登录并下载");
            btnOpenFile.setEnabled(false);
        }
    }

    private void openXlsxFile() {
        SharedPreferences prefs = getSharedPreferences("tobacco", MODE_PRIVATE);
        String fn = prefs.getString("cache_filename", "supply_strategy.xlsx");
        File f = new File(getCacheDir(), fn);
        if (!f.exists()) { Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show(); return; }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension("xlsx");
        intent.setDataAndType(uri, mime != null ? mime : "application/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(intent, "打开方式")); }
        catch (Exception e) { Toast.makeText(this, "无可用应用", Toast.LENGTH_SHORT).show(); }
    }

    // ========== JSON Tab ==========
    private void updateJsonTab() {
        if (parsedData != null)
            tvJson.setText(new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(parsedData));
        else tvJson.setText("暂无数据");
    }

    // ========== 下载（保留登录后更新功能） ==========
    private void checkAndDownload() {
        SharedPreferences prefs = getSharedPreferences("tobacco", MODE_PRIVATE);
        String cookie = prefs.getString("cookie", "");
        if (cookie.isEmpty()) { startActivity(new Intent(this, LoginActivity.class)); return; }
        btnRefresh.setEnabled(false); progress.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                // download xlsx via API, parse with JsonSupplyParser, update cache...
                // Same as before, kept for future use
                handler.post(() -> { btnRefresh.setEnabled(true); progress.setVisibility(View.GONE);
                    Toast.makeText(this, "下载功能开发中（当前使用内置JSON）", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                handler.post(() -> { btnRefresh.setEnabled(true); progress.setVisibility(View.GONE); });
            }
        });
    }
}
