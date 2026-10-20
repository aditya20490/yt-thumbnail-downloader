package com.ytthumb.downloader;

import android.content.ContentValues;
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

import java.io.OutputStream;

public class MainActivity extends BridgeActivity {

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

        getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(HOOK_JS, null);
            }
        });
    }

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
