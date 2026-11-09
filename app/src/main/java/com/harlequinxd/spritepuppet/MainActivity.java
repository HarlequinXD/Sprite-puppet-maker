package com.harlequinxd.spritepuppet;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the Sprite Puppet editor (assets/index.html) in a WebView and gives it a small native bridge:
 * HTTPS requests to the OpenAI API (no CORS limits, long timeouts), key/settings storage,
 * saving exports to Downloads, and the system file picker for photo uploads.
 */
public class MainActivity extends Activity {
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int FILE_REQ = 4242;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("sprite_puppet", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types = params.getAcceptTypes();
                String type = (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty()) ? types[0] : "*/*";
                if (type.startsWith(".")) type = "*/*"; // e.g. ".json" -> let the user pick any file
                if (type.contains(",")) type = "*/*";
                i.setType(type);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose a file"), FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQ && fileCallback != null) {
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) out = new Uri[]{data.getData()};
                else if (data.getClipData() != null && data.getClipData().getItemCount() > 0)
                    out = new Uri[]{data.getClipData().getItemAt(0).getUri()};
            }
            fileCallback.onReceiveValue(out);
            fileCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.__onBack && window.__onBack()) ? '1' : '0'", value -> {
            if (value == null || !value.contains("1")) finish();
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private String doHttp(String specJson) throws Exception {
        JSONObject o = new JSONObject(specJson);
        HttpURLConnection c = (HttpURLConnection) new URL(o.getString("url")).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(360000);
        c.setRequestMethod(o.optString("method", "GET"));
        c.setUseCaches(false);
        JSONObject h = o.optJSONObject("headers");
        if (h != null) {
            Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                c.setRequestProperty(k, h.getString(k));
            }
        }
        byte[] payload = null;
        if (o.has("multipart")) {
            String boundary = "----SpritePuppet" + System.nanoTime();
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            JSONArray parts = o.getJSONArray("multipart");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (p.has("dataBase64")) {
                    bos.write(("Content-Disposition: form-data; name=\"" + p.getString("name") + "\"; filename=\"" + p.optString("filename", "file") + "\"\r\n"
                            + "Content-Type: " + p.optString("mime", "application/octet-stream") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    bos.write(Base64.decode(p.getString("dataBase64"), Base64.DEFAULT));
                } else {
                    bos.write(("Content-Disposition: form-data; name=\"" + p.getString("name") + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    bos.write(p.optString("value", "").getBytes(StandardCharsets.UTF_8));
                }
                bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            payload = bos.toByteArray();
        } else if (o.has("body") && !o.isNull("body")) {
            payload = o.getString("body").getBytes(StandardCharsets.UTF_8);
        }
        if (payload != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            body = out.toString("UTF-8");
        }
        c.disconnect();
        JSONObject r = new JSONObject();
        r.put("status", code);
        r.put("body", body);
        return r.toString();
    }

    private static String errorJson(Throwable t) {
        try {
            JSONObject r = new JSONObject();
            r.put("status", 0);
            r.put("body", t.getClass().getSimpleName() + ": " + t.getMessage());
            return r.toString();
        } catch (Exception e) {
            return "{\"status\":0,\"body\":\"error\"}";
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void http(final String id, final String spec) {
            pool.execute(() -> {
                String out;
                try { out = doHttp(spec); } catch (Throwable t) { out = errorJson(t); }
                results.put(id, out);
                final String js = "window.__nativeDone && window.__nativeDone('" + id.replace("'", "") + "')";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }

        @JavascriptInterface
        public String take(String id) {
            String r = results.remove(id);
            return r == null ? "{\"status\":0,\"body\":\"missing result\"}" : r;
        }

        @JavascriptInterface
        public String getPref(String key) { return prefs.getString(key, ""); }

        @JavascriptInterface
        public void setPref(String key, String value) { prefs.edit().putString(key, value).apply(); }

        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SpritePuppetMaker");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "ERROR:could not create file";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return "ERROR:could not open file";
                        os.write(data);
                    }
                    return "Downloads/SpritePuppetMaker/" + safe;
                } else {
                    File dir = new File(getExternalFilesDir(null), "exports");
                    if (!dir.exists() && !dir.mkdirs()) return "ERROR:could not create folder";
                    File f = new File(dir, safe);
                    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
                    return f.getAbsolutePath();
                }
            } catch (Throwable t) {
                return "ERROR:" + t.getMessage();
            }
        }
    }
}
