package com.grillme.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极小的 HTTP/1.1 客户端。存在的理由：WebDAV 要发 PROPFIND / MKCOL，
 * 而 java.net.HttpURLConnection 不允许自定义方法，又不愿意为了这个引第三方库。
 * 只支持 http/https、Basic 认证、Content-Length 请求体、chunked 响应。
 */
public final class Http {

    public static class Resp {
        public int code;
        public byte[] body = new byte[0];

        public String text() {
            try {
                return new String(body, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }

    private Http() {
    }

    public static Resp req(String url, String method, String user, String pass,
                           String body, int timeoutMs) throws IOException {
        return req(url, method, null, user, pass, body, timeoutMs);
    }

    public static Resp req(String url, String method, Map<String, String> extra,
                           String user, String pass, String body, int timeoutMs) throws IOException {
        URL u = new URL(url);
        boolean tls = "https".equalsIgnoreCase(u.getProtocol());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = u.getFile();
        if (path == null || path.length() == 0) {
            path = "/";
        }

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        Socket sock = raw;
        if (tls) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true);
            ssl.startHandshake();
            sock = ssl;
        }

        try {
            byte[] payload = body == null ? new byte[0] : body.getBytes("UTF-8");
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host);
            if ((tls && port != 443) || (!tls && port != 80)) {
                head.append(':').append(port);
            }
            head.append("\r\n");
            head.append("User-Agent: GrillMe/1.0 (Android)\r\n");
            head.append("Accept: */*\r\n");
            head.append("Accept-Encoding: identity\r\n");
            head.append("Connection: close\r\n");
            if (user != null && user.length() > 0) {
                String token = android.util.Base64.encodeToString(
                        (user + ":" + pass).getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                head.append("Authorization: Basic ").append(token).append("\r\n");
            }
            if (payload.length > 0) {
                head.append("Content-Type: text/plain; charset=utf-8\r\n");
                head.append("Content-Length: ").append(payload.length).append("\r\n");
            }
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            head.append("\r\n");

            OutputStream out = sock.getOutputStream();
            out.write(head.toString().getBytes("UTF-8"));
            if (payload.length > 0) {
                out.write(payload);
            }
            out.flush();

            InputStream in = sock.getInputStream();
            Resp resp = new Resp();
            String status = readLine(in);
            resp.code = parseCode(status);
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                            line.substring(c + 1).trim());
                }
            }
            resp.body = readBody(in, headers);
            return resp;
        } finally {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static int parseCode(String status) {
        if (status == null) {
            return 0;
        }
        String[] parts = status.split(" ");
        if (parts.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
        }
        if (c == -1 && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null || sizeLine.length() == 0) {
                    break;
                }
                int size;
                try {
                    int sp = sizeLine.indexOf(';');
                    size = Integer.parseInt((sp > 0 ? sizeLine.substring(0, sp) : sizeLine).trim(), 16);
                } catch (Exception e) {
                    break;
                }
                if (size == 0) {
                    readLine(in);
                    break;
                }
                byte[] buf = new byte[size];
                int read = 0;
                while (read < size) {
                    int n = in.read(buf, read, size - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                out.write(buf, 0, read);
                readLine(in);
            }
            return out.toByteArray();
        }

        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                int len = Integer.parseInt(cl.trim());
                byte[] buf = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(buf, read, len - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                if (read == len) {
                    return buf;
                }
                byte[] cut = new byte[read];
                System.arraycopy(buf, 0, cut, 0, read);
                return cut;
            } catch (Exception ignored) {
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
