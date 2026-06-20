package com.fujian.tobacco;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fujian.tobacco.data.model.SupplyItem;
import com.fujian.tobacco.data.parser.ExcelParser;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 福建烟草订货助手 — 唯一页面
 *
 * 功能：选挡位 → 下载策略表 → 查看本期可订卷烟配额
 */
public class MainActivity extends AppCompatActivity {

    // ⚠️ 改成你自己的 GitHub Release 下载地址
    private static final String XLSX_URL =
            "https://github.com/你的用户名/你的仓库/releases/latest/download/supply_strategy.xlsx";

    private AutoCompleteTextView actvTier;
    private MaterialButton btnRefresh;
    private MaterialTextView tvStatus;
    private ProgressBar progressBar;
    private RecyclerView rvList;
    private SupplyAdapter adapter;

    private final List<SupplyItem> allItems = new ArrayList<>();
    private int selectedTier = 15;
    private File cachedXlsxFile;  // 缓存的 xlsx 文件路径

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        actvTier = findViewById(R.id.actv_tier);
        btnRefresh = findViewById(R.id.btn_refresh);
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress);
        rvList = findViewById(R.id.rv_list);

        setupTierSelector();
        setupRecyclerView();

        btnRefresh.setOnClickListener(v -> downloadAndParse());

        // 启动时从 assets 预置文件加载
        initFromAssets();
    }

    // ========== 挡位选择器 ==========

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

    // ========== 从 assets 加载 ==========

    private void initFromAssets() {
        cachedXlsxFile = new File(getCacheDir(), "supply_strategy.xlsx");

        // 如果缓存已有，直接解析
        if (cachedXlsxFile.exists()) {
            tvStatus.setText("数据就绪，选择挡位查看");
            reparseForTier();
            return;
        }

        // 首次启动：从 assets 复制到缓存
        tvStatus.setText("正在准备数据...");
        progressBar.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try (InputStream is = getAssets().open("supply_strategy.xlsx");
                 FileOutputStream fos = new FileOutputStream(cachedXlsxFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvStatus.setText("加载失败: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                });
                return;
            }

            // 解析
            try {
                List<SupplyItem> items = ExcelParser.parse(
                        cachedXlsxFile.getAbsolutePath(), selectedTier);
                allItems.clear();
                allItems.addAll(items);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    showResults(items);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("解析失败: " + e.getMessage());
                });
            }
        });
    }

    // ========== RecyclerView ==========

    private void setupRecyclerView() {
        adapter = new SupplyAdapter(new ArrayList<>());
        rvList.setLayoutManager(new LinearLayoutManager(this));
        rvList.setAdapter(adapter);
    }

    /**
     * 切换挡位时重新解析缓存文件（极快，ZIP+SAX 内存解析）
     */
    private void reparseForTier() {
        if (cachedXlsxFile == null || !cachedXlsxFile.exists()) {
            tvStatus.setText("请先刷新数据下载策略表");
            tvStatus.setVisibility(View.VISIBLE);
            rvList.setVisibility(View.GONE);
            return;
        }

        executor.execute(() -> {
            try {
                List<SupplyItem> items = ExcelParser.parse(
                        cachedXlsxFile.getAbsolutePath(), selectedTier);
                allItems.clear();
                allItems.addAll(items);

                mainHandler.post(() -> showResults(items));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvStatus.setText("解析失败: " + e.getMessage());
                    tvStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showResults(List<SupplyItem> items) {
        // 只显示有配额的
        List<SupplyItem> withQuota = new ArrayList<>();
        for (SupplyItem item : items) {
            if (item.getPersonalQuota() > 0) withQuota.add(item);
        }
        // 全部为0就显示所有
        if (withQuota.isEmpty()) withQuota = items;

        adapter.updateData(withQuota);

        if (withQuota.isEmpty()) {
            tvStatus.setText(selectedTier + " 档本期无可订卷烟");
            tvStatus.setVisibility(View.VISIBLE);
            rvList.setVisibility(View.GONE);
        } else {
            String info = selectedTier + " 档 · " + withQuota.size() + " 款可订";
            tvStatus.setText(info);
            tvStatus.setVisibility(View.VISIBLE);
            rvList.setVisibility(View.VISIBLE);
        }
    }

    // ========== 下载 + 解析 ==========

    private void downloadAndParse() {
        btnRefresh.setEnabled(false);
        btnRefresh.setText("下载中...");
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("正在下载策略表...");
        tvStatus.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(XLSX_URL).build();
                Response response = httpClient.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    throw new Exception("下载失败: HTTP " + response.code());
                }

                // 保存到缓存
                File file = new File(getCacheDir(), "supply_strategy.xlsx");
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                cachedXlsxFile = file;  // 记录文件路径，供后续切换挡位用

                // 解析
                List<SupplyItem> items = ExcelParser.parse(file.getAbsolutePath(), selectedTier);

                allItems.clear();
                allItems.addAll(items);

                // 缓存日期
                String today = dateFmt.format(new Date());
                getPreferences(MODE_PRIVATE).edit().putString("cache_date", today).apply();

                mainHandler.post(() -> {
                    btnRefresh.setEnabled(true);
                    btnRefresh.setText("刷新数据");
                    progressBar.setVisibility(View.GONE);
                    showResults(items);
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    btnRefresh.setEnabled(true);
                    btnRefresh.setText("刷新数据");
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("下载失败: " + e.getMessage());
                    Toast.makeText(this, "下载失败，请检查网络和下载地址", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ========== 缓存 ==========


    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
