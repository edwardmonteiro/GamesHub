package com.edwardlabs.punhodedragao;

import android.app.Activity;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private final boolean[] axisHeld = new boolean[4]; // l r u d vindos do analógico/direcional

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
        hideSystemUi();
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void send(String b, boolean down) {
        web.evaluateJavascript("window.__nk&&window.__nk('" + b + "'," + (down ? 1 : 0) + ")", null);
    }

    // Controle Bluetooth: X soco · Y chute · A pulo · B especial · START pausa
    private String map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_L1: return "p";
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_BUTTON_R1: return "k";
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: return "j";
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_R2: case KeyEvent.KEYCODE_BUTTON_L2: return "s";
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MENU: return "st";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "l";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "r";
            case KeyEvent.KEYCODE_DPAD_UP: return "u";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "d";
            default: return null;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int src = e.getSource();
        boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                   || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                   || (src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("window.__back?window.__back():'exit'", r -> {
                    if (r == null || r.contains("exit")) finish();
                });
            }
            return true;
        }
        String b = map(e.getKeyCode());
        if (b != null && pad) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) send(b, true);
            else if (e.getAction() == KeyEvent.ACTION_UP) send(b, false);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hx) > 0.5f) x = hx;
            if (Math.abs(hy) > 0.5f) y = hy;
            set(0, "l", x < -0.4f); set(1, "r", x > 0.4f); set(2, "u", y < -0.4f); set(3, "d", y > 0.4f);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void set(int i, String b, boolean on) {
        if (axisHeld[i] != on) { axisHeld[i] = on; send(b, on); }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideSystemUi(); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
}
