package com.edwardlabs.gameshub;

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

/**
 * Hub de jogos: uma tela de catálogo (assets/hub.html) que baixa e abre jogos HTML5.
 * O servidor de controle pelo Wi-Fi (celular vira joystick) vale para todos os jogos.
 */
public class MainActivity extends Activity {
    private static final String HUB_URL = "file:///android_asset/hub.html";

    /** Jogos que não conhecem o hub recebem as teclas do controle como eventos de teclado. */
    private static final String SHIM =
        "(function(){if(window.__hk)return;var M={l:['ArrowLeft',37,'ArrowLeft'],r:['ArrowRight',39,'ArrowRight'],"
        + "u:['ArrowUp',38,'ArrowUp'],d:['ArrowDown',40,'ArrowDown'],j:[' ',32,'Space'],p:['z',90,'KeyZ'],"
        + "k:['x',88,'KeyX'],s:['c',67,'KeyC'],st:['Escape',27,'Escape']};"
        + "window.__hk=function(b,v){var m=M[b];if(!m)return;"
        + "var e=new KeyboardEvent(v?'keydown':'keyup',{key:m[0],code:m[2],bubbles:true,cancelable:true});"
        + "try{Object.defineProperty(e,'keyCode',{get:function(){return m[1]}});"
        + "Object.defineProperty(e,'which',{get:function(){return m[1]}});}catch(_){}"
        + "(document.body||document).dispatchEvent(e);};})();";

    private WebView web;
    private ControllerServer server;
    private GameStore store;
    private String lanUrl = "";
    private String qrDataUrl = "";
    private volatile String current = null;     // slug do jogo aberto; null = estamos no hub
    private final boolean[] axisHeld = new boolean[4];

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new GameStore(this);
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
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (current != null) v.evaluateJavascript(SHIM, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        startServer();
        showHub();
        hideSystemUi();
    }

    private void showHub() {
        current = null;
        web.loadUrl(HUB_URL);
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

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    /** Exposto às páginas como window.Native (hub e jogos). */
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

        /** Lista de jogos conhecidos (embutidos + baixados + catálogo em cache), em JSON. */
        @JavascriptInterface public String games() { return store.list(); }

        /** Atualiza o catálogo pela internet; ao terminar chama window.__catalog(ok). */
        @JavascriptInterface public void refresh() {
            new Thread(() -> {
                boolean ok = store.fetchRemote();
                js("window.__catalog&&window.__catalog(" + ok + ")");
            }, "catalog").start();
        }

        /** Baixa/atualiza (se preciso) e abre o jogo. Progresso: window.__progress(slug, %). Erro: window.__fail(slug, msg). */
        @JavascriptInterface public void play(final String slug) {
            if (!GameStore.validSlug(slug)) return;
            new Thread(() -> {
                try {
                    final String url = store.prepare(slug, p -> js("window.__progress&&window.__progress('" + slug + "'," + p + ")"));
                    runOnUiThread(() -> { current = slug; web.loadUrl(url); });
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage()).replace("\\", " ").replace("'", " ").replace("\n", " ");
                    js("window.__fail&&window.__fail('" + slug + "','" + m + "')");
                }
            }, "play").start();
        }

        @JavascriptInterface public void exit() { runOnUiThread(MainActivity.this::finish); }
    }

    // ---------- tela e entrada física ----------
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** Entrega um botão ao hub (window.__nk) ou ao jogo (window.__nk próprio, senão teclas simuladas). */
    private void send(String b, boolean down) {
        web.evaluateJavascript("(window.__nk||window.__hk||function(){})('" + b + "'," + (down ? 1 : 0) + ")", null);
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
                // O jogo/hub trata o "voltar" (pausa, fecha janela...). Se devolver "exit": jogo -> hub, hub -> sai.
                web.evaluateJavascript("window.__back?window.__back():'exit'", r -> {
                    if (r == null || r.contains("exit")) {
                        if (current != null) showHub(); else finish();
                    }
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
