package com.fujian.tobacco.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fujian.tobacco.MainActivity;
import com.fujian.tobacco.R;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * WebView 登录 → shouldInterceptRequest 拦截快讯 HTML
 * → 注入钩子脚本 → 页面自动调 API → 钩子捕获 JSON → 提取 fileid → 下载
 */
public class LoginActivity extends AppCompatActivity {

    private static final String BASE_DOMAIN = "https://yxmall.fjycgs.com";
    private static final String LOGIN_URL = BASE_DOMAIN + "/mobile/#/pages/index/login";
    private static final String NOTICE_URL = BASE_DOMAIN + "/mobile/#/pages/notice/notice?column_uuid=C9D1BD5D9F9000019EF6113B668014D4&v=12";

    private WebView webView;
    private ProgressBar progress;
    private TextView tvLog;
    private Button btnDone;
    private boolean loggedIn;
    private boolean isFetching;
    private boolean downloadDone;
    private String capturedCookie;

    private final OkHttpClient http = new OkHttpClient();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        btnDone = findViewById(R.id.btn_done);
        tvLog = findViewById(R.id.tv_log);

        setupWebView();
        webView.loadUrl(LOGIN_URL);

        btnDone.setOnClickListener(v -> {
            if (downloadDone) goMain();
            else if (loggedIn) startFetch();
            else checkLoginJs();
        });
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // 拦截快讯页 HTML，注入钩子脚本
                if (isFetching && url.contains("notice/notice") && url.contains("column_uuid")) {
                    log("→ 拦截HTML，注入钩子...");
                    return injectHookIntoHtml(req);
                }
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(android.view.View.GONE);
                int idx = url.indexOf("#");
                String hash = idx >= 0 ? url.substring(idx + 1) : "";

                if (hash.startsWith("/?")) {
                    if (!loggedIn) {
                        loggedIn = true;
                        capturedCookie = CookieManager.getInstance().getCookie(url);
                        saveCookie();
                        log("✓ 登录成功，自动开始下载...");
                        log("登录成功，自动下载中...");
                        btnDone.setText("下载中...");
                        btnDone.setEnabled(false);
                        startFetch();
                    }
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                onPageFinished(view, url);
            }
        });
    }

    // ⭐ 核心：拦截 HTML 响应，在 <head> 后插入钩子 <script>
    private WebResourceResponse injectHookIntoHtml(WebResourceRequest req) {
        try {
            // 自己发请求拿 HTML
            Request r = new Request.Builder()
                    .url(req.getUrl().toString())
                    .header("Cookie", capturedCookie)
                    .header("User-Agent", req.getRequestHeaders().get("User-Agent"))
                    .build();
            Response resp = http.newCall(r).execute();
            if (resp.body() == null) return null;

            String html = resp.body().string();
            // 在 <head> 后插入钩子
            String hook = "<script>(function(){" +
                    "if(window._h)return;window._h=1;" +
                    "var os=XMLHttpRequest.prototype.send;" +
                    "XMLHttpRequest.prototype.send=function(b){" +
                    "  if(this._m==='POST'&&this._u&&this._u.indexOf('call_service')>=0){" +
                    "    this.addEventListener('load',function(){" +
                    // 直接在 JS 里提取 fileid，不传整 JSON
                    "      try{" +
                    "        var d=JSON.parse(this.responseText);" +
                    "        var rows=d.result.rows;" +
                    "        for(var i=0;i<rows.length;i++){" +
                    "          if(rows[i].title.indexOf('全区卷烟货源投放策略表')>=0){" +
                    "            var m=rows[i].remark.match(/fileid=([A-F0-9]+)/);" +
                    "            if(m)window._fid=m[1];" +
                    "            var p=rows[i].title.match(/（(\\d{4}年\\d{1,2}月\\d{1,2}日)下午-(\\d{4}年\\d{1,2}月\\d{1,2}日)上午）/);" +
                    "            if(p)window._period=p[1]+'-'+p[2];" +
                    "            var nm=rows[i].remark.match(/[^/]+\\.xlsx?/);" +
                    "            if(nm)window._fname=nm[0];" +
                    "            else window._fname=rows[i].title+'.xlsx';" +
                    "            break;" +
                    "          }" +
                    "        }" +
                    "      }catch(e){window._err=e.message;}" +
                    "    });" +
                    "  }os.call(this,b);" +
                    "};" +
                    "var oo=XMLHttpRequest.prototype.open;" +
                    "XMLHttpRequest.prototype.open=function(m,u){" +
                    "  this._u=u;this._m=m;oo.apply(this,arguments);" +
                    "};" +
                    "})();</script>";
            html = html.replace("<head>", "<head>" + hook);

            return new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream(html.getBytes()));
        } catch (Exception e) {
            return null;
        }
    }

    // ====== 下载流程 ======

    private void startFetch() {
        isFetching = true;
        log("→ 开始获取策略表...");
        btnDone.setEnabled(false);
        log("正在加载快讯页...");
        progress.setVisibility(android.view.View.VISIBLE);
        webView.loadUrl(NOTICE_URL);
        // 轮询等待 API 结果
        pollResult(0);
    }

    private void pollResult(int count) {
        if (count > 12) { log("✗ 轮询超时(" + count + "次)"); onError("请求超时, 请重试"); return; }
        if (count == 0) log("→ 开始轮询API结果...");
        // 先查错误
        webView.evaluateJavascript("window._err||'null'", raw -> {
            String err = unwrap(raw);
            if (err != null && !err.equals("null")) {
                log("✗ JS: " + err);
                onError("JS错误: " + err);
                return;
            }
            // 查 fileid
            webView.evaluateJavascript("window._fid||'null'", raw2 -> {
                String fid = unwrap(raw2);
                if (fid != null && !fid.equals("null") && fid.length() > 10) {
                    log("✓ fileid=" + fid);
                    // 保存时间段 + 文件名
                    webView.evaluateJavascript("window._period||''", raw3 -> {
                        String p = unwrap(raw3);
                        if (p != null && !p.isEmpty() && !p.equals("null")) {
                            getSharedPreferences("tobacco", MODE_PRIVATE)
                                    .edit().putString("cache_period", p).apply();
                        }
                    });
                    webView.evaluateJavascript("window._fname||''", raw4 -> {
                        String fn = unwrap(raw4);
                        if (fn != null && !fn.isEmpty() && !fn.equals("null")) {
                            getSharedPreferences("tobacco", MODE_PRIVATE)
                                    .edit().putString("cache_filename", fn).apply();
                            log("file=" + fn);
                        }
                    });
                    log("正在下载...");
                    doDownload(BASE_DOMAIN
                            + "/wdk?action=ecw.file&method=attachment_download&fileid=" + fid);
                } else {
                    handler.postDelayed(() -> pollResult(count + 1), 1000);
                }
            });
        });
    }

    private void doDownload(String url) {
        new Thread(() -> {
            try {
                Request r = new Request.Builder().url(url).header("Cookie", capturedCookie).build();
                Response resp = http.newCall(r).execute();
                String contentType = resp.header("Content-Type", "?");
                log("下载响应: " + resp.code() + " type=" + contentType + " size=" + resp.body().contentLength());
                if (!resp.isSuccessful() || resp.body() == null) {
                    handler.post(() -> onError("下载失败 " + resp.code()));
                    return;
                }
                String filename = getSharedPreferences("tobacco", MODE_PRIVATE)
                        .getString("cache_filename", "supply_strategy.xlsx");
                File f = new File(getCacheDir(), filename);
                try (InputStream is = resp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(f)) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = is.read(b)) != -1) fos.write(b, 0, n);
                }
                if (f.length() < 1000) { handler.post(() -> onError("文件无效 size=" + f.length())); return; }

                android.util.Log.d("LOGIN", "下载成功: " + f.length() + " bytes");
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(new Date());
                getSharedPreferences("tobacco", MODE_PRIVATE).edit()
                        .putString("cache_date", today).apply();
                handler.post(() -> {
                    downloadDone = true;
                    log("✓ 下载完成 " + f.length() + " bytes");
                    log("→ 上传到 GitHub...");
                    uploadToGitHub(f);
                });
            } catch (Exception e) {
                handler.post(() -> onError(e.getMessage()));
            }
        }).start();
    }

    // ====== 工具 ======

    private void checkLoginJs() {
        webView.evaluateJavascript(
                "javascript:(function(){var h=location.hash.substring(1);" +
                        "return h.startsWith('/?')?'ok':'no';})()",
                v -> {
                    if (v != null && v.contains("ok") && !loggedIn) {
                        loggedIn = true;
                        capturedCookie = CookieManager.getInstance().getCookie(BASE_DOMAIN);
                        saveCookie();
                        log("✓ 登录成功(JS检测)，自动开始下载...");
                        log("登录成功，自动下载中...");
                        btnDone.setEnabled(false);
                        startFetch();
                    } else {
                        Toast.makeText(this, "请先完成登录", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void log(String msg) {
        android.util.Log.d("LOGIN", msg);
        handler.post(() -> tvLog.append(msg + "\n"));
    }

    private String unwrap(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\""))
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"");
        return s;
    }

    private void saveCookie() {
        getSharedPreferences("tobacco", MODE_PRIVATE)
                .edit().putString("cookie", capturedCookie)
                .putLong("login_time", System.currentTimeMillis()).apply();
    }

    private void goMain() {
        isFetching = false;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void uploadToGitHub(File file) {
        new Thread(() -> {
            try {
                String token = "github_pat_你的TOKEN"; // TODO: 从设置中读取
                String repo = "Caibospoem/tobacco";
                String path = "data/" + file.getName();
                String base64 = android.util.Base64.encodeToString(
                        java.nio.file.Files.readAllBytes(file.toPath()),
                        android.util.Base64.NO_WRAP);

                // GitHub API: PUT /repos/{owner}/{repo}/contents/{path}
                String json = "{\"message\":\"更新策略表\",\"content\":\"" + base64 + "\"}";
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        json, okhttp3.MediaType.parse("application/json"));
                Request req = new Request.Builder()
                        .url("https://api.github.com/repos/" + repo + "/contents/" + path)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .put(body)
                        .build();
                Response resp = http.newCall(req).execute();
                String respBody = resp.body() != null ? resp.body().string() : "";
                int code = resp.code();

                if (code == 201 || code == 200) {
                    log("✓ 上传成功 → GitHub Actions 开始解析");
                } else {
                    // 文件已存在需要更新（需要 sha）
                    com.google.gson.JsonObject obj = new com.google.gson.Gson()
                            .fromJson(respBody, com.google.gson.JsonObject.class);
                    if (code == 422 && obj.has("message")) {
                        log("文件已存在，获取sha...");
                        // 先获取现有文件的sha
                        Request getReq = new Request.Builder()
                                .url("https://api.github.com/repos/" + repo + "/contents/" + path)
                                .header("Authorization", "Bearer " + token)
                                .build();
                        Response getResp = http.newCall(getReq).execute();
                        String getBody = getResp.body() != null ? getResp.body().string() : "";
                        com.google.gson.JsonObject existing = new com.google.gson.Gson()
                                .fromJson(getBody, com.google.gson.JsonObject.class);
                        String sha = existing.get("sha").getAsString();
                        String updateJson = "{\"message\":\"更新策略表\",\"content\":\""
                                + base64 + "\",\"sha\":\"" + sha + "\"}";
                        okhttp3.RequestBody updateBody = okhttp3.RequestBody.create(
                                updateJson, okhttp3.MediaType.parse("application/json"));
                        Request updateReq = new Request.Builder()
                                .url("https://api.github.com/repos/" + repo + "/contents/" + path)
                                .header("Authorization", "Bearer " + token)
                                .header("Accept", "application/vnd.github+json")
                                .put(updateBody)
                                .build();
                        Response updateResp = http.newCall(updateReq).execute();
                        log(updateResp.code() == 200 || updateResp.code() == 201
                                ? "✓ 更新成功" : "✗ 更新失败 " + updateResp.code());
                    } else {
                        log("✗ 上传失败 " + code + " " + respBody.substring(0,
                                Math.min(100, respBody.length())));
                    }
                }
                handler.postDelayed(this::goMain, 500);
            } catch (Exception e) {
                log("✗ 上传异常: " + e.getMessage());
                handler.postDelayed(this::goMain, 500);
            }
        }).start();
    }

    private void onError(String msg) {
        log("✗ " + msg);
        isFetching = false;
        log("失败: " + msg);
        btnDone.setEnabled(true);
        btnDone.setText("重试 →");
        progress.setVisibility(android.view.View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
