package com.edwardlabs.gameshub;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Biblioteca de jogos do hub.
 *
 * Duas fontes locais: os jogos embutidos no APK (assets/games/&lt;slug&gt;) e os baixados depois
 * (filesDir/games/&lt;slug&gt;). O catálogo remoto (games-catalog.json) diz quais jogos existem e qual a
 * versão mais nova; o hub baixa só o que falta ou o que ficou velho, confere o SHA-256 e guarda no aparelho.
 */
public class GameStore {

    /** Origens do catálogo, na ordem de tentativa. Só HTTPS. */
    static final String[] BASES = {
        "https://edwardmonteiro.github.io/GamesHub/",
        "https://raw.githubusercontent.com/edwardmonteiro/GamesHub/main/"
    };
    static final String CATALOG = "games-catalog.json";
    private static final int MAX_BYTES = 25 * 1024 * 1024;

    public interface Progress { void on(int percent); }

    private final AssetManager assets;
    private final File root;     // filesDir/games
    private final File catalogFile;

    public GameStore(Context ctx) {
        assets = ctx.getAssets();
        root = new File(ctx.getFilesDir(), "games");
        catalogFile = new File(ctx.getFilesDir(), "catalog.json");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    public static boolean validSlug(String s) {
        return s != null && s.matches("[a-z0-9][a-z0-9-]{0,63}");
    }

    // ---------- leitura local ----------

    private String readAsset(String path) {
        try (InputStream in = assets.open(path)) { return new String(readAll(in), StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static String readFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) { return new String(readAll(in), StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    private JSONObject obj(String text) {
        try { return text == null ? null : new JSONObject(text); } catch (Exception e) { return null; }
    }

    private JSONObject bundledMeta(String slug) { return obj(readAsset("games/" + slug + "/meta.json")); }

    private JSONObject installedInfo(String slug) { return obj(readFile(new File(new File(root, slug), "info.json"))); }

    private boolean downloaded(String slug) { return new File(new File(root, slug), "index.html").isFile(); }

    private JSONObject cachedCatalog() { return obj(readFile(catalogFile)); }

    // ---------- lista para a tela ----------

    /** Junta catálogo remoto (se já baixado alguma vez) + jogos embutidos + jogos baixados. */
    public synchronized String list() {
        Map<String, JSONObject> all = new LinkedHashMap<>();
        JSONObject cat = cachedCatalog();
        JSONArray remote = cat != null ? cat.optJSONArray("games") : null;
        if (remote != null) {
            for (int i = 0; i < remote.length(); i++) {
                JSONObject g = remote.optJSONObject(i);
                if (g != null && validSlug(g.optString("slug"))) all.put(g.optString("slug"), g);
            }
        }
        try {
            String[] dirs = assets.list("games");
            if (dirs != null) {
                for (String slug : dirs) {
                    if (!validSlug(slug) || all.containsKey(slug)) continue;
                    JSONObject m = bundledMeta(slug);
                    if (m == null) continue;
                    m.put("slug", slug);
                    m.put("path", "games/" + slug);
                    all.put(slug, m);
                }
            }
        } catch (Exception ignored) { }

        JSONArray out = new JSONArray();
        for (Map.Entry<String, JSONObject> e : all.entrySet()) {
            try {
                String slug = e.getKey();
                JSONObject g = e.getValue();
                String installed = "";
                JSONObject info = downloaded(slug) ? installedInfo(slug) : null;
                if (info != null) installed = info.optString("version", "0");
                else if (bundledMeta(slug) != null) installed = bundledMeta(slug).optString("version", "0");
                String latest = g.optString("version", installed);
                String state = installed.isEmpty() ? "baixar" : (compare(latest, installed) > 0 ? "atualizar" : "pronto");
                JSONObject o = new JSONObject();
                o.put("slug", slug);
                o.put("title", g.optString("title", slug));
                o.put("description", g.optString("description", ""));
                o.put("category", g.optString("category", "Jogos"));
                o.put("color", g.optString("color", "#444444"));
                o.put("controls", g.optString("controls", ""));
                o.put("difficulty", g.optString("difficulty", ""));
                o.put("version", latest);
                o.put("installed", installed);
                o.put("state", state);
                o.put("size", g.optLong("size", 0));
                out.put(o);
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    // ---------- rede ----------

    private static byte[] http(String url, int max, Progress p) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                long got = 0;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    got += n;
                    if (got > max) throw new IOException("grande demais");
                    if (p != null && total > 0) p.on((int) (got * 100 / total));
                }
                return b.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Baixa o catálogo mais novo. Devolve true se conseguiu. */
    public boolean fetchRemote() {
        for (String base : BASES) {
            try {
                byte[] data = http(base + CATALOG + "?t=" + System.currentTimeMillis() / 60000, 2 * 1024 * 1024, null);
                JSONObject o = new JSONObject(new String(data, StandardCharsets.UTF_8));
                if (o.optJSONArray("games") == null) continue;
                writeAtomic(catalogFile, data);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); o.getFD().sync(); }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("não consegui salvar " + f.getName());
        }
    }

    private JSONObject remoteEntry(String slug) {
        JSONObject cat = cachedCatalog();
        JSONArray a = cat != null ? cat.optJSONArray("games") : null;
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject g = a.optJSONObject(i);
            if (g != null && slug.equals(g.optString("slug"))) return g;
        }
        return null;
    }

    // ---------- preparar para jogar ----------

    /**
     * Garante que o jogo está instalado e atualizado e devolve a URL para abrir no WebView.
     * Se a atualização falhar mas existe uma versão local, usa a local. Lança exceção se não há nada para jogar.
     */
    public String prepare(String slug, Progress progress) throws Exception {
        if (!validSlug(slug)) throw new IOException("jogo inválido");
        JSONObject remote = remoteEntry(slug);
        String state = null;
        JSONArray arr = new JSONArray(list());
        for (int i = 0; i < arr.length(); i++) {
            if (slug.equals(arr.getJSONObject(i).optString("slug"))) state = arr.getJSONObject(i).optString("state");
        }
        if (state == null) throw new IOException("jogo não encontrado");

        if (!"pronto".equals(state) && remote != null) {
            try {
                download(slug, remote, progress);
            } catch (Exception e) {
                if ("baixar".equals(state)) throw e;   // sem versão local: não há o que jogar
            }
        } else if ("baixar".equals(state)) {
            throw new IOException("sem conexão com o catálogo");
        }
        return url(slug);
    }

    private void download(String slug, JSONObject entry, Progress progress) throws Exception {
        String path = entry.optString("path", "games/" + slug);
        if (!path.matches("games/[a-z0-9-]+")) throw new IOException("caminho inválido");
        String want = entry.optString("sha256", "");
        Exception last = null;
        for (String base : BASES) {
            try {
                byte[] data = http(base + path + "/index.html", MAX_BYTES, progress);
                if (!want.isEmpty() && !want.equalsIgnoreCase(sha256(data))) throw new IOException("assinatura (SHA-256) não confere");
                File dir = new File(root, slug);
                writeAtomic(new File(dir, "index.html"), data);
                JSONObject info = new JSONObject();
                info.put("version", entry.optString("version", "0"));
                info.put("sha256", sha256(data));
                writeAtomic(new File(dir, "info.json"), info.toString().getBytes(StandardCharsets.UTF_8));
                if (progress != null) progress.on(100);
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("falha no download");
    }

    private String url(String slug) throws IOException {
        File f = new File(new File(root, slug), "index.html");
        if (f.isFile()) return "file://" + f.getAbsolutePath();
        if (bundledMeta(slug) != null) return "file:///android_asset/games/" + slug + "/index.html";
        throw new IOException("sem arquivo do jogo");
    }

    /** Para testes. */
    List<String> slugs() {
        List<String> r = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(list());
            for (int i = 0; i < a.length(); i++) r.add(a.getJSONObject(i).optString("slug"));
        } catch (Exception ignored) { }
        return r;
    }
}
