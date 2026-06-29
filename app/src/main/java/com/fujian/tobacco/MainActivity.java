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
                // 1. 调 API 获取列表
                String body = "action=ecw.page&method=call_service";
                okhttp3.RequestBody reqBody = okhttp3.RequestBody.create(
                        body, okhttp3.MediaType.parse("application/x-www-form-urlencoded"));
                Request apiReq = new Request.Builder()
                        .url("https://yxmall.fjycgs.com/wdk?action=ecw.page&method=call_service")
                        .header("Cookie", cookie)
                        .post(reqBody)
                        .build();
                Response apiResp = httpClient.newCall(apiReq).execute();
                String respBody = apiResp.body() != null ? apiResp.body().string() : "";
                apiResp.close();

                com.google.gson.JsonObject root = new com.google.gson.Gson()
                        .fromJson(respBody, com.google.gson.JsonObject.class);
                if (!"1".equals(root.get("code").getAsString())) {
                    throw new Exception("登录过期，请重新登录");
                }
                com.google.gson.JsonArray rows = root.getAsJsonObject("result")
                        .getAsJsonArray("rows");
                String fileid = null, showTime = null, titlePeriod = null, filename = null;
                for (int i = 0; i < rows.size(); i++) {
                    com.google.gson.JsonObject row = rows.get(i).getAsJsonObject();
                    String title = row.get("title").getAsString();
                    if (title != null && title.contains("全区卷烟货源投放策略表")) {
                        showTime = row.get("show_time").getAsString();
                        java.util.regex.Matcher pm = java.util.regex.Pattern
                                .compile("（(\\d{4}年\\d{1,2}月\\d{1,2}日)下午-(\\d{4}年\\d{1,2}月\\d{1,2}日)上午）")
                                .matcher(title);
                        if (pm.find()) titlePeriod = pm.group(1) + "-" + pm.group(2);
                        String remark = row.get("remark").getAsString();
                        java.util.regex.Matcher nm = java.util.regex.Pattern
                                .compile("([^/>]+\\.xlsx?)").matcher(remark);
                        if (nm.find()) filename = nm.group(1);
                        else filename = title + ".xlsx";
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("fileid=([A-F0-9]+)").matcher(remark);
                        if (m.find()) { fileid = m.group(1); break; }
                    }
                }
                if (fileid == null) throw new Exception("未找到策略表");

                // 检查是否已是最新
                String cachedDay = prefs.getString("cache_date", "");
                String remoteDay = showTime != null && showTime.length() >= 10
                        ? showTime.substring(0, 10) : "";
                if (remoteDay.equals(cachedDay) && cachedXlsxFile != null
                        && cachedXlsxFile.exists()) {
                    handler.post(() -> {
                        btnRefresh.setEnabled(true);
                        progress.setVisibility(View.GONE);
                        String period = prefs.getString("cache_period", "");
                        tvStatus.setText(selectedTier + " 档 · " + adapter.getItemCount()
                                + " 款 · " + period + "（最新）");
                        Toast.makeText(MainActivity.this, "策略表暂无更新", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 2. 下载 xlsx
                String dlUrl = "https://yxmall.fjycgs.com/wdk?action=ecw.file"
                        + "&method=attachment_download&fileid=" + fileid;
                Request dlReq = new Request.Builder().url(dlUrl)
                        .header("Cookie", cookie).build();
                Response dlResp = httpClient.newCall(dlReq).execute();
                if (!dlResp.isSuccessful() || dlResp.body() == null)
                    throw new Exception("下载失败 HTTP " + dlResp.code());

                File file = new File(getCacheDir(), filename != null ? filename : "supply_strategy.xlsx");
                try (InputStream is = dlResp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                dlResp.close();
                if (file.length() < 1000) throw new Exception("文件太小");

                cachedXlsxFile = file;
                String saveDate = remoteDay != null && !remoteDay.isEmpty()
                        ? remoteDay : dateFmt.format(new Date());
                prefs.edit().putString("cache_date", saveDate)
                        .putString("cache_period", titlePeriod != null ? titlePeriod : saveDate)
                        .putString("cache_filename", filename != null ? filename : "")
                        .apply();

                handler.post(() -> {
                    btnRefresh.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "策略表已更新", Toast.LENGTH_SHORT).show();
                });
                doParse(file);

            } catch (Exception e) {
                handler.post(() -> {
                    btnRefresh.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("登录过期") || msg.contains("API"))) {
                        tvStatus.setText("登录过期，请重新登录");
                    } else {
                        tvStatus.setText("更新失败: " + msg);
                        Toast.makeText(this, "更新失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

}
