package com.fujian.tobacco;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fujian.tobacco.data.model.SupplyItem;
import com.fujian.tobacco.data.parser.ExcelParser;
import com.fujian.tobacco.ui.login.LoginActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.textview.MaterialTextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 福建烟草订货助手 — 唯一主页
 */
public class MainActivity extends AppCompatActivity {

    private AutoCompleteTextView actvTier;
    private MaterialButton btnRefresh, btnLogin;
    private MaterialTextView tvStatus;
    private Chip chipCount;
    private ProgressBar progress;
    private RecyclerView rvList;
    private SupplyAdapter adapter;

    private final List<SupplyItem> allItems = new ArrayList<>();
    private int selectedTier = 15;
    private File cachedXlsxFile;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupTierSelector();
        setupRecyclerView();

        btnRefresh.setOnClickListener(v -> downloadAndParse());
        btnLogin.setOnClickListener(v -> startLogin());
        tvStatus.setOnClickListener(v -> showFileInfo());

        scheduleWorker();
        updateLoginUI();
        initFromAssets();
    }

    private void bindViews() {
        actvTier = findViewById(R.id.actv_tier);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnLogin = findViewById(R.id.btn_login);
        tvStatus = findViewById(R.id.tv_status);
        chipCount = findViewById(R.id.chip_count);
        progress = findViewById(R.id.progress);
        rvList = findViewById(R.id.rv_list);
    }

    // ========== 登录状态 ==========

    private void startLogin() {
        startActivity(new Intent(this, LoginActivity.class));
    }

    private void updateLoginUI() {
        SharedPreferences prefs = getSharedPreferences("tobacco", MODE_PRIVATE);
        String cookie = prefs.getString("cookie", "");
        boolean loggedIn = !cookie.isEmpty();

        String cacheDate = prefs.getString("cache_date", "");
        if (loggedIn) {
            btnLogin.setText("已登录 ✓");
            btnLogin.setTextColor(getColor(R.color.on_primary));
        } else {
            btnLogin.setText("登录");
            btnLogin.setTextColor(getColor(R.color.on_primary));
        }
        if (!cacheDate.isEmpty()) {
            String period = prefs.getString("cache_period", cacheDate);
            tvStatus.setText("数据日期: " + period);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLoginUI();
        // 每次回来检查缓存是否有更新
        SharedPreferences prefs2 = getSharedPreferences("tobacco", MODE_PRIVATE);
        String filename = prefs2.getString("cache_filename", "supply_strategy.xlsx");
        File f = new File(getCacheDir(), filename);
        if (f.exists() && !f.getAbsolutePath().equals(
                cachedXlsxFile != null ? cachedXlsxFile.getAbsolutePath() : "")) {
            cachedXlsxFile = f;
            reparseForTier();
        }
    }

    // ========== 挡位 ==========

    private void setupTierSelector() {
        String[] tiers = new String[30];
        for (int i = 0; i < 30; i++) tiers[i] = (i + 1) + " 档";
        actvTier.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, tiers));
        actvTier.setText(selectedTier + " 档", false);
        actvTier.setOnItemClickListener((parent, view, pos, id) -> {
            selectedTier = pos + 1;
            reparseForTier();
        });
    }

    // ========== RecyclerView ==========

    private void setupRecyclerView() {
        adapter = new SupplyAdapter(new ArrayList<>());
        rvList.setLayoutManager(new LinearLayoutManager(this));
        rvList.setAdapter(adapter);
    }

    private void scheduleWorker() {}

    private void showFileInfo() {
        SharedPreferences prefs = getSharedPreferences("tobacco", MODE_PRIVATE);
        String period = prefs.getString("cache_period", "未知");
        String date = prefs.getString("cache_date", "未知");
        long loginTime = prefs.getLong("login_time", 0);
        String loginInfo = loginTime > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(loginTime))
                : "未登录";

        String filename = prefs.getString("cache_filename", "supply_strategy.xlsx");
        File f = new File(getCacheDir(), filename);
        String size = f.exists() ? String.format("%.1f KB", f.length() / 1024.0) : "无文件";

        String msg = "📁 文件: " + filename + "\n"
                + "📏 大小: " + size + "\n"
                + "📅 周期: " + period + "\n"
                + "🕐 更新: " + date + "\n"
                + "🔑 登录: " + loginInfo + "\n"
                + "📂 路径: " + f.getAbsolutePath();

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("当前数据文件")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    // ========== 数据加载 ==========

    private void initFromAssets() {
        String fname = getSharedPreferences("tobacco", MODE_PRIVATE)
                .getString("cache_filename", "supply_strategy.xlsx");
        cachedXlsxFile = new File(getCacheDir(), fname);
        if (cachedXlsxFile.exists()) {
            tvStatus.setText("数据就绪");
            reparseForTier();
            return;
        }

        tvStatus.setText("正在准备数据...");
        progress.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try (InputStream is = getAssets().open("supply_strategy.xlsx");
                 FileOutputStream fos = new FileOutputStream(cachedXlsxFile)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            } catch (Exception e) {
                handler.post(() -> {
                    tvStatus.setText("加载失败: " + e.getMessage());
                    progress.setVisibility(View.GONE);
                });
                return;
            }
            // 本地解析兜底
            getSharedPreferences("tobacco", MODE_PRIVATE)
                    .edit().putString("cache_date", "2026-06-20")
                    .putString("cache_period", "2026年6月21日-2026年6月26日")
                    .apply();
            doParse(cachedXlsxFile);
        });
    }

    private void reparseForTier() {
        if (cachedXlsxFile == null || !cachedXlsxFile.exists()) return;
        executor.execute(() -> doParse(cachedXlsxFile));
    }

    private void doParse(File file) {
        try {
            String path = file.getAbsolutePath();
            long size = file.length();
            android.util.Log.d("MAIN", "解析: " + path + " " + size + "bytes tier=" + selectedTier);

            // 验证是否为有效 ZIP (xlsx=ZIP)
            byte[] header = new byte[4];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                fis.read(header);
            }
            boolean isZip = header[0]==0x50 && header[1]==0x4B;
            if (!isZip) {
                handler.post(() -> tvStatus.setText("文件不是有效的xlsx(ZIP)"));
                return;
            }

            List<SupplyItem> items = ExcelParser.parse(
                    file.getAbsolutePath(), selectedTier);
            allItems.clear();
            allItems.addAll(items);
            android.util.Log.d("MAIN", "解析完成: " + items.size() + " 条");
            handler.post(() -> {
                showResults(items);
            });
        } catch (Exception e) {
            android.util.Log.e("MAIN", "解析失败", e);
            handler.post(() -> {
                tvStatus.setText("解析失败: " + e.getMessage());
                progress.setVisibility(View.GONE);
            });
        }
    }

    private void showResults(List<SupplyItem> items) {
        progress.setVisibility(View.GONE);

        List<SupplyItem> withQuota = new ArrayList<>();
        for (SupplyItem item : items) {
            if (item.getPersonalQuota() > 0) withQuota.add(item);
        }
        if (withQuota.isEmpty()) withQuota = items;

        android.util.Log.d("MAIN", "显示: " + withQuota.size() + " 款 (共" + items.size() + "条)");

        adapter.updateData(withQuota);
        chipCount.setText("共 " + withQuota.size() + " 款");
        chipCount.setVisibility(View.VISIBLE);

        String period = getSharedPreferences("tobacco", MODE_PRIVATE)
                .getString("cache_period", "");
        if (withQuota.isEmpty()) {
            tvStatus.setText(selectedTier + " 档无可订 · " + period);
            tvStatus.setVisibility(View.VISIBLE);
            rvList.setVisibility(View.GONE);
        } else {
            tvStatus.setText(selectedTier + " 档 · " + withQuota.size() + " 款 · " + period + "  ⊙");
            tvStatus.setVisibility(View.VISIBLE);
            rvList.setVisibility(View.VISIBLE);
        }
    }

    private static final String PARSED_JSON_URL =
            "https://raw.githubusercontent.com/Caibospoem/tobacco/main/data/parsed_strategy.json";

    // ========== 后台刷新下载 ==========

    private void downloadAndParse() {
        SharedPreferences prefs = getSharedPreferences("tobacco", MODE_PRIVATE);
        String cookie = prefs.getString("cookie", "");
        if (cookie.isEmpty()) {
            tvStatus.setText("请先登录订货平台");
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }

        btnRefresh.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        tvStatus.setText("正在下载最新策略表...");
        tvStatus.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                // 1. 下载 AI 解析结果
                Request req = new Request.Builder().url(PARSED_JSON_URL).build();
                Response resp = httpClient.newCall(req).execute();
                String json = resp.body() != null ? resp.body().string() : "";
                resp.close();

                if (json.isEmpty() || !json.startsWith("[")) {
                    throw new Exception("解析结果格式错误，等待 GitHub Actions 完成");
                }

                // 2. 解析 JSON → SupplyItem
                com.google.gson.JsonArray arr = new com.google.gson.Gson()
                        .fromJson(json, com.google.gson.JsonArray.class);
                List<SupplyItem> items = new ArrayList<>();
                int tierColIdx = (30 - selectedTier); // tier offset in quotas object

                for (int i = 0; i < arr.size(); i++) {
                    com.google.gson.JsonObject obj = arr.get(i).getAsJsonObject();
                    SupplyItem item = new SupplyItem();
                    item.setProductCode(obj.has("code") ? obj.get("code").getAsString() : "");
                    item.setBrandName(obj.has("brand") ? obj.get("brand").getAsString() : "");
                    item.setTierCategory(obj.has("category") ? obj.get("category").getAsString() : "");
                    item.setRegion(obj.has("region") ? obj.get("region").getAsString() : "");
                    item.setTotalRetailers(obj.has("retailers") ? obj.get("retailers").getAsInt() : 0);
                    // 读取当前挡位的配额
                    if (obj.has("quotas")) {
                        com.google.gson.JsonObject quotas = obj.getAsJsonObject("quotas");
                        String tierKey = String.valueOf(selectedTier);
                        item.setPersonalQuota(quotas.has(tierKey) ? quotas.get(tierKey).getAsInt() : 0);
                    }
                    item.setUserTier(selectedTier);
                    items.add(item);
                }

                // 缓存
                allItems.clear();
                allItems.addAll(items);
                String today = dateFmt.format(new Date());
                prefs.edit().putString("cache_date", today).apply();

                handler.post(() -> {
                    btnRefresh.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            "AI 解析完成 · " + items.size() + " 款", Toast.LENGTH_SHORT).show();
                    showResults(items);
                });

            } catch (Exception e) {
                handler.post(() -> {
                    btnRefresh.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("AI 解析")) {
                        tvStatus.setText("等待云端解析完成...");
                        Toast.makeText(this, "文件已上传，等待 GitHub Actions 解析", Toast.LENGTH_SHORT).show();
                    } else {
                        tvStatus.setText("更新失败: " + msg);
                        Toast.makeText(this, "更新失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

}
