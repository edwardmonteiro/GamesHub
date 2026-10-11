package com.lumi.ovelhadegiz;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

public final class MainActivity extends Activity {
    private WebView game;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        game = new WebView(this);
        game.setBackgroundColor(0xff101717);
        WebSettings settings = game.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        setContentView(game);
        game.loadUrl("file:///android_asset/index.html");
    }

    @Override public void onBackPressed() {
        if (game != null) game.evaluateJavascript(
            "if(document.getElementById('pause')) document.getElementById('pause').click()", null);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (game != null) {
            game.destroy();
            game = null;
        }
        super.onDestroy();
    }
}
