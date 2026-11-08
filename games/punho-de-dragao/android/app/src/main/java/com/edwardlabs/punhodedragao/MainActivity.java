package com.edwardlabs.punhodedragao;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView web;
    private ControllerServer server;
    private String lanUrl = "";
    private String qrDataUrl = "";
    private final boolean[] axisHeld = new boolean[4];

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
        web.addJavascriptInterface(new Bridge(), "Native");
        startServer();
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
        hideSystemUi();
    }

    // ---------- servidor de controle pelo Wi-Fi ----------
    private void startServer() {
        server = new ControllerServer(getAssets(), new ControllerServer.Listener() {
            @Override public void onButton(String b, boolean down) { runOnUiThread(() -> send(b, down)); }
            @Override public void onClients(int n) {
                runOnUiThread(() -> web.evaluateJavascript("window.__pc&&window.__pc(" + n + ")", null));
            }
        });
        int port = server.open();
        if (port > 0) {
            server.start();
            String ip = lanIp();
            if (ip != null) {
                lanUrl = "http://" + ip + ":" + port + "/";
                qrDataUrl = qr(lanUrl);
            }
        }
    }

    private static String lanIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (a.isSiteLocalAddress() && (name.startsWith("wlan") || name.startsWith("eth"))) return ip;
                    if (a.isSiteLocalAddress() && fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String qr(String text) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth(), h = m.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** Exposto ao jogo como window.Native */
    private class Bridge {
        @JavascriptInterface public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("url", lanUrl);
                o.put("qr", qrDataUrl);
                o.put("clients", server != null ? server.clientCount() : 0);
                o.put("tv", getPackageManager().hasSystemFeature("android.software.leanback"));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
        @JavascriptInterface public void toPhones(String msg) { if (server != null) server.broadcast(msg); }
    }

    // ---------- tela e entrada física ----------
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

    // Controle físico / controle remoto da TV: X soco · Y chute · A (ou OK) pulo · B especial · START pausa
    private String map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_L1: return "p";
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_BUTTON_R1: return "k";
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return "j";
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_R2: case KeyEvent.KEYCODE_BUTTON_L2: return "s";
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "st";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "l";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "r";
            case KeyEvent.KEYCODE_DPAD_UP: return "u";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "d";
            default: return null;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int src = e.getSource();
        boolean physical = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
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
        if (b != null && physical) {
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
            axis(0, "l", x < -0.4f); axis(1, "r", x > 0.4f); axis(2, "u", y < -0.4f); axis(3, "d", y > 0.4f);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void axis(int i, String b, boolean on) { if (axisHeld[i] != on) { axisHeld[i] = on; send(b, on); } }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideSystemUi(); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (server != null) server.shutdown(); web.destroy(); super.onDestroy(); }
}
