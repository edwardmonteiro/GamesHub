package com.edwardlabs.gameshub;

import android.content.res.AssetManager;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Servidor mínimo na TV: entrega a página de controle (GET /) e recebe os botões
 * do celular por WebSocket. Vários celulares podem se conectar; um botão fica
 * pressionado enquanto qualquer celular o segura.
 */
public class ControllerServer extends Thread {

    public interface Listener {
        void onButton(String button, boolean down);
        void onClients(int count);
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Set<String> VALID = new HashSet<>(java.util.Arrays.asList("l", "r", "u", "d", "p", "k", "j", "s", "st"));

    private final AssetManager assets;
    private final Listener listener;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> held = new HashMap<>();
    private ServerSocket server;
    private int port;

    public ControllerServer(AssetManager assets, Listener listener) {
        super("controller-server");
        this.assets = assets;
        this.listener = listener;
        setDaemon(true);
    }

    /** Abre a porta (tenta 8765..8774) e devolve a escolhida, ou -1. */
    public int open() {
        for (int p = 8765; p < 8775; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                port = p;
                return p;
            } catch (IOException ignored) { }
        }
        return -1;
    }

    public int getPort() { return port; }

    public int clientCount() { return clients.size(); }

    @Override public void run() {
        while (server != null && !server.isClosed()) {
            try {
                final Socket s = server.accept();
                s.setTcpNoDelay(true);
                new Thread(() -> handle(s), "controller-client").start();
            } catch (IOException e) {
                if (server == null || server.isClosed()) break;
            }
        }
    }

    public void shutdown() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Client c : clients) c.close();
    }

    /** Envia uma mensagem curta a todos os celulares (ex.: "hit" para vibrar). */
    public void broadcast(String msg) {
        for (Client c : clients) c.send(msg);
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String request = readLine(in);
            if (request == null) { s.close(); return; }
            String[] parts = request.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String upgrade = headers.get("upgrade");
            if (upgrade != null && upgrade.equalsIgnoreCase("websocket") && headers.containsKey("sec-websocket-key")) {
                websocket(s, in, out, headers.get("sec-websocket-key"));
            } else {
                serve(out, path);
                s.close();
            }
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void serve(OutputStream out, String path) throws IOException {
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/") || path.equals("/controle") || path.equals("/controle.html")) {
            byte[] body = readAsset("controle.html");
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
        } else {
            byte[] body = "Não encontrado".getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
        }
        out.flush();
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream a = assets.open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = a.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private void websocket(Socket s, InputStream in, OutputStream out, String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String accept = Base64.encodeToString(sha1.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)), Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        Client c = new Client(s, out);
        clients.add(c);
        listener.onClients(clients.size());
        c.send("hello");
        try {
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                if (len > 65536) break;
                byte[] mask = new byte[4];
                if (masked) readFully(in, mask);
                byte[] data = new byte[(int) len];
                readFully(in, data);
                if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                if (op == 0x8) break;                                  // fechar
                if (op == 0x9) { c.frame(0xA, data); continue; }       // ping -> pong
                if (op == 0x1) onText(c, new String(data, StandardCharsets.UTF_8));
            }
        } catch (SocketTimeoutException timeout) {
            // celular sumiu (tela apagou, saiu do Wi-Fi): solta os botões abaixo
        } finally {
            clients.remove(c);
            releaseAll(c);
            c.close();
            listener.onClients(clients.size());
        }
    }

    /** Mensagens: "p1,k0" (botão + 1/0), "hb" (batimento). */
    private void onText(Client c, String text) {
        for (String m : text.split(",")) {
            m = m.trim();
            if (m.length() < 2 || m.equals("hb")) continue;
            String b = m.substring(0, m.length() - 1);
            boolean down = m.charAt(m.length() - 1) == '1';
            if (!VALID.contains(b)) continue;
            setHeld(c, b, down);
        }
    }

    private synchronized void setHeld(Client c, String b, boolean down) {
        boolean had = c.pressed.contains(b);
        if (down == had) return;
        if (down) c.pressed.add(b); else c.pressed.remove(b);
        int before = held.containsKey(b) ? held.get(b) : 0;
        int after = Math.max(0, before + (down ? 1 : -1));
        held.put(b, after);
        if ((before > 0) != (after > 0)) listener.onButton(b, after > 0);
    }

    private synchronized void releaseAll(Client c) {
        for (String b : new HashSet<>(c.pressed)) setHeld(c, b, false);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) return null;
        }
        if (ch < 0 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("eof");
            off += n;
        }
    }

    private static final class Client {
        final Socket socket;
        final OutputStream out;
        final Set<String> pressed = new HashSet<>();

        Client(Socket socket, OutputStream out) { this.socket = socket; this.out = out; }

        void send(String text) { frame(0x1, text.getBytes(StandardCharsets.UTF_8)); }

        synchronized void frame(int op, byte[] data) {
            try {
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write(0x80 | op);
                if (data.length < 126) f.write(data.length);
                else { f.write(126); f.write((data.length >> 8) & 0xFF); f.write(data.length & 0xFF); }
                f.write(data);
                out.write(f.toByteArray());
                out.flush();
            } catch (IOException ignored) { }
        }

        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
}
