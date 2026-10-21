package com.ytthumb.downloader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends BridgeActivity {

    private boolean pageLoaded = false;
    private String lastHandled = "";

    private static final String API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
    private static final String ANDROID_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";

    private static final String HOOK_JS =
        "(function(){" +
        "if(window.__dlHooked)return;window.__dlHooked=true;" +
        "var o=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){" +
        "  if(this.download&&this.href.indexOf('blob:')===0){" +
        "    var n=this.download;" +
        "    fetch(this.href).then(function(r){return r.blob();}).then(function(b){" +
        "      var f=new FileReader();" +
        "      f.onload=function(){AndroidSaver.save(f.result.split(',')[1],b.type||'image/jpeg',n);};" +
        "      f.readAsDataURL(b);" +
        "    });" +
        "    return;" +
        "  }" +
        "  return o.apply(this,arguments);" +
        "};" +
        "})();";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = getBridge().getWebView();
        webView.addJavascriptInterface(new Saver(), "AndroidSaver");
        webView.addJavascriptInterface(new Captions(), "AndroidCaptions");

        getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(HOOK_JS, null);
                pageLoaded = true;
                pasteFromClipboard();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            pasteFromClipboard();
        }
    }

    private void pasteFromClipboard() {
        if (!pageLoaded) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            if (cs == null) return;
            String text = cs.toString().trim();
            if (!text.contains("youtu") || text.equals(lastHandled)) return;
            lastHandled = text;

            String js = "(function(){var i=document.getElementById('url');" +
                "if(!i||typeof run!=='function')return;" +
                "i.value=" + JSONObject.quote(text) + ";run();})();";
            getBridge().getWebView().evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    /* ---------- talking to YouTube for captions ---------- */

    private String http(String urlStr, String postBody, String userAgent, String clientName, String clientVersion) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        if (postBody != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-YouTube-Client-Name", clientName);
            c.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            c.setRequestProperty("Origin", "https://www.youtube.com");
            try (OutputStream os = c.getOutputStream()) {
                os.write(postBody.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new Exception("YouTube answered with error " + code);
        }
        InputStream is = c.getInputStream();
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bo.write(buf, 0, n);
        }
        is.close();
        return bo.toString("UTF-8");
    }

    private String loadTracks(String videoId) throws Exception {
        String[][] clients = {
            {"ANDROID", "20.10.38", "3", ANDROID_UA},
            {"IOS", "20.10.4", "5", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"}
        };
        String lastError = "This video has no captions or lyrics (not even auto-generated).";

        for (String[] cl : clients) {
            try {
                JSONObject client = new JSONObject();
                client.put("clientName", cl[0]);
                client.put("clientVersion", cl[1]);
                client.put("hl", "en");
                client.put("gl", "US");
                if (cl[0].equals("ANDROID")) {
                    client.put("androidSdkVersion", 30);
                    client.put("osName", "Android");
                    client.put("osVersion", "11");
                } else {
                    client.put("deviceMake", "Apple");
                    client.put("deviceModel", "iPhone16,2");
                    client.put("osName", "iPhone");
                    client.put("osVersion", "18.3.2.22D82");
                }
                JSONObject context = new JSONObject();
                context.put("client", client);
                JSONObject body = new JSONObject();
                body.put("context", context);
                body.put("videoId", videoId);
                body.put("contentCheckOk", true);
                body.put("racyCheckOk", true);

                String resp = http(
                    "https://www.youtube.com/youtubei/v1/player?key=" + API_KEY + "&prettyPrint=false",
                    body.toString(), cl[3], cl[2], cl[1]);
                JSONObject pr = new JSONObject(resp);

                String title = "";
                JSONObject vd = pr.optJSONObject("videoDetails");
                if (vd != null) title = vd.optString("title", "");

                JSONObject ps = pr.optJSONObject("playabilityStatus");
                if (ps != null && !"OK".equals(ps.optString("status", "OK"))) {
                    String reason = ps.optString("reason", "");
                    lastError = reason.isEmpty() ? "YouTube would not let the app see this video." : reason;
                    continue;
                }

                JSONObject caps = pr.optJSONObject("captions");
                JSONObject rend = caps == null ? null : caps.optJSONObject("playerCaptionsTracklistRenderer");
                JSONArray arr = rend == null ? null : rend.optJSONArray("captionTracks");
                if (arr == null || arr.length() == 0) {
                    lastError = "This video has no captions or lyrics (not even auto-generated).";
                    continue;
                }

                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.getJSONObject(i);
                    JSONObject o = new JSONObject();
                    o.put("url", t.optString("baseUrl", ""));
                    o.put("lang", t.optString("languageCode", ""));
                    o.put("auto", "asr".equals(t.optString("kind", "")));
                    String name = "";
                    JSONObject nm = t.optJSONObject("name");
                    if (nm != null) {
                        name = nm.optString("simpleText", "");
                        if (name.isEmpty()) {
                            JSONArray runs = nm.optJSONArray("runs");
                            if (runs != null && runs.length() > 0) {
                                name = runs.getJSONObject(0).optString("text", "");
                            }
                        }
                    }
                    o.put("name", name);
                    out.put(o);
                }

                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("title", title);
                result.put("tracks", out);
                return result.toString();
            } catch (Exception e) {
                lastError = "Could not reach YouTube: " + e.getMessage();
            }
        }

        JSONObject fail = new JSONObject();
        fail.put("ok", false);
        fail.put("error", lastError);
        return fail.toString();
    }

    private String loadText(String url) throws Exception {
        if (!url.startsWith("https://www.youtube.com/api/timedtext")) {
            throw new Exception("unexpected caption address");
        }
        String clean = url.replaceAll("&fmt=[^&]*", "");
        String body = http(clean + "&fmt=json3", null, ANDROID_UA, "3", "20.10.38");
        if (body == null || body.trim().isEmpty()) {
            body = http(clean, null, ANDROID_UA, "3", "20.10.38");
        }
        JSONObject res = new JSONObject();
        res.put("ok", true);
        res.put("body", body == null ? "" : body);
        return res.toString();
    }

    private void deliver(final String cb, final String payload) {
        final String js = "window.__capDone(" + JSONObject.quote(cb) + "," + JSONObject.quote(payload) + ");";
        runOnUiThread(() -> getBridge().getWebView().evaluateJavascript(js, null));
    }

    private String errorJson(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", message);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"Something went wrong\"}";
        }
    }

    private class Captions {
        @JavascriptInterface
        public void tracks(final String videoId, final String cb) {
            new Thread(() -> {
                String result;
                try {
                    result = loadTracks(videoId);
                } catch (Exception e) {
                    result = errorJson("Could not get captions: " + e.getMessage());
                }
                deliver(cb, result);
            }).start();
        }

        @JavascriptInterface
        public void text(final String url, final String cb) {
            new Thread(() -> {
                String result;
                try {
                    result = loadText(url);
                } catch (Exception e) {
                    result = errorJson("Could not get the caption file: " + e.getMessage());
                }
                deliver(cb, result);
            }).start();
        }
    }

    /* ---------- saving files into Downloads ---------- */

    private class Saver {
        @JavascriptInterface
        public void save(String base64, String mime, String name) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(data);
                }
                toast("Saved to Downloads: " + name);
            } catch (Exception e) {
                toast("Download failed");
            }
        }

        private void toast(String t) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show());
        }
    }
}
