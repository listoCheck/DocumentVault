package ru.documentvault.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public final class VaultApi {
    public static final class HttpFailure extends Exception {
        public final int status;
        HttpFailure(int status) { super(status == 413 ? "Превышен размер файла или лимит хранилища." : status == 429 ? "Слишком много попыток. Подождите и повторите." : "Ошибка сервера: HTTP " + status); this.status = status; }
    }
    private final String root;
    public VaultApi(String root) {
        URI uri = URI.create(root);
        boolean localDebug = BuildConfig.DEBUG && "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !localDebug) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty()))
            throw new IllegalArgumentException("Укажите HTTPS-адрес сервера без пути и завершающего слеша");
        this.root = root;
    }
    private byte[] request(String method, String path, byte[] body, String token, String id, boolean json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(root + path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000); connection.setReadTimeout(60000); connection.setRequestMethod(method);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (id != null) connection.setRequestProperty("X-Document-Id", id);
        if (json) connection.setRequestProperty("Content-Type", "application/json");
        try {
            if (body != null) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); try (var stream = connection.getOutputStream()) { stream.write(body); } }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpFailure(status);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n; int total = 0;
                while ((n = in.read(buffer)) != -1) { total += n; if (total > 16 * 1024 * 1024) throw new IllegalStateException("Ответ слишком большой"); out.write(buffer, 0, n); }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
    public String login(String username, String password) throws Exception {
        byte[] body = new JSONObject().put("username", username).put("password", password).toString().getBytes(StandardCharsets.UTF_8);
        return new JSONObject(new String(request("POST", "/v1/sessions", body, null, null, true), StandardCharsets.UTF_8)).getString("token");
    }
    public void register(String username, String password, char[] recoveryPassword) throws Exception {
        byte[] masterKey = new byte[32], salt = new byte[16], recoveryKey = null;
        new java.security.SecureRandom().nextBytes(masterKey); new java.security.SecureRandom().nextBytes(salt);
        try {
            recoveryKey = VaultCrypto.derive(recoveryPassword, salt);
            JSONObject registration = new JSONObject().put("username", username).put("password", password)
                .put("recoverySalt", Base64.getEncoder().encodeToString(salt))
                .put("wrappedKey", Base64.getEncoder().encodeToString(VaultCrypto.encrypt(recoveryKey, masterKey, "master-key")));
            request("POST", "/v1/accounts", registration.toString().getBytes(StandardCharsets.UTF_8), null, null, true);
        } finally {
            Arrays.fill(masterKey, (byte) 0); Arrays.fill(recoveryPassword, '\0');
            if (recoveryKey != null) Arrays.fill(recoveryKey, (byte) 0);
        }
    }
    public byte[] unlock(String token, char[] password) throws Exception {
        byte[] key = null;
        try {
            JSONObject vault = new JSONObject(new String(request("GET", "/v1/vault", null, token, null, false), StandardCharsets.UTF_8));
            key = VaultCrypto.derive(password, Base64.getDecoder().decode(vault.getString("recoverySalt")));
            return VaultCrypto.decrypt(key, Base64.getDecoder().decode(vault.getString("wrappedKey")), "master-key");
        } finally { if (key != null) Arrays.fill(key, (byte) 0); Arrays.fill(password, '\0'); }
    }
    public JSONArray list(String token) throws Exception { return new JSONArray(new String(request("GET", "/v1/documents", null, token, null, false), StandardCharsets.UTF_8)); }
    public void upload(String token, String id, byte[] ciphertext) throws Exception { request("POST", "/v1/documents", ciphertext, token, id, false); }
    public byte[] download(String token, String id) throws Exception { return request("GET", "/v1/documents/" + id, null, token, null, false); }
    public byte[] metadata(String token, String id) throws Exception {
        try { return request("GET", "/v1/documents/" + id + "/metadata", null, token, null, false); }
        catch (HttpFailure e) { if (e.status == 404) return null; throw e; }
    }
    public void saveMetadata(String token, String id, byte[] ciphertext) throws Exception { request("PUT", "/v1/documents/" + id + "/metadata", ciphertext, token, null, false); }
    public void logout(String token) throws Exception { request("DELETE", "/v1/sessions/current", null, token, null, false); }
}
