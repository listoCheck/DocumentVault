package ru.documentvault.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import androidx.core.content.FileProvider;

public final class MainActivity extends Activity {
    private static final int PICK_DOCUMENT = 100;
    private static final int SAVE_DOCUMENT = 101;
    private static final int CAPTURE_DOCUMENT = 102;
    private static final int MAX_FILE_BYTES = 15 * 1024 * 1024;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText serverField, usernameField, passwordField, recoveryField;
    private TextView status, documents;
    private VaultApi api;
    private String token;
    private byte[] masterKey;
    private byte[] pendingDocument;
    private String[] documentIds = new String[0];
    private File pendingCapture;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String pendingName = state == null ? null : state.getString("cameraFile");
        File cameraDir = new File(getCacheDir(), "camera");
        if (pendingName != null && pendingName.matches("capture-[a-zA-Z0-9-]+\\.jpg")) pendingCapture = new File(cameraDir, pendingName);
        File[] stale = cameraDir.listFiles();
        if (stale != null) for (File file : stale) if (pendingCapture == null || !file.equals(pendingCapture)) file.delete();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(28, 32, 28, 32);
        TextView title = new TextView(this); title.setText("DocumentVault — прототип"); title.setTextSize(22); layout.addView(title);
        serverField = field(layout, "HTTPS-адрес сервера", false);
        usernameField = field(layout, "Логин", false);
        passwordField = field(layout, "Пароль входа", true);
        recoveryField = field(layout, "Пароль восстановления", true);
        button(layout, "Войти", this::login);
        button(layout, "Разблокировать", this::unlock);
        button(layout, "Обновить список", this::refresh);
        button(layout, "Загрузить файл", this::chooseFile);
        button(layout, "Сфотографировать", this::capturePhoto);
        button(layout, "Сохранить документ", this::chooseDocument);
        button(layout, "Заблокировать", this::lock);
        status = new TextView(this); status.setText("Нужен настроенный HTTPS-сервер. Биометрия пока недоступна."); layout.addView(status);
        documents = new TextView(this); layout.addView(documents);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
    }
    private EditText field(LinearLayout layout, String hint, boolean secret) {
        EditText input = new EditText(this); input.setHint(hint); input.setSingleLine(true);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(input); return input;
    }
    private void button(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(v -> { if (!busy) action.run(); }); layout.addView(button);
    }
    private interface Task<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }
    private <T> void runTask(String message, Task<T> task, Done<T> done) {
        if (busy) return;
        busy = true; status.setText(message);
        executor.execute(() -> {
            try {
                T value = task.run();
                runOnUiThread(() -> {
                    busy = false;
                    if (!isFinishing() && !isDestroyed()) done.accept(value);
                    else if (value instanceof byte[]) Arrays.fill((byte[]) value, (byte) 0);
                    else if (value instanceof FileData) Arrays.fill(((FileData) value).content, (byte) 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { busy = false; if (!isFinishing() && !isDestroyed()) status.setText("Ошибка: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); });
            }
        });
    }
    private void login() {
        if (token != null) { status.setText("Сначала закройте приложение для смены аккаунта."); return; }
        String address = serverField.getText().toString().trim();
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString(); passwordField.setText("");
        runTask("Вход…", () -> {
            VaultApi selectedApi = new VaultApi(address);
            return new LoginResult(selectedApi, selectedApi.login(username, password));
        }, result -> { api = result.api; token = result.token; status.setText("Вход выполнен. Введите пароль восстановления и разблокируйте хранилище."); });
    }
    private static final class LoginResult {
        final VaultApi api; final String token;
        LoginResult(VaultApi api, String token) { this.api = api; this.token = token; }
    }
    private void unlock() {
        if (token == null) { status.setText("Сначала войдите в аккаунт."); return; }
        char[] password = recoveryField.getText().toString().toCharArray(); recoveryField.setText("");
        VaultApi selectedApi = api; String selectedToken = token;
        runTask("Разблокировка…", () -> selectedApi.unlock(selectedToken, password), key -> {
            if (masterKey != null) Arrays.fill(masterKey, (byte) 0);
            masterKey = key; status.setText("Хранилище разблокировано. Можно загрузить файл или обновить список.");
        });
    }
    private void refresh() {
        if (token == null) { status.setText("Сначала войдите в аккаунт."); return; }
        VaultApi selectedApi = api; String selectedToken = token;
        runTask("Получение списка…", () -> selectedApi.list(selectedToken), list -> {
            StringBuilder output = new StringBuilder("Документы (идентификаторы):\n");
            String[] ids = new String[list.length()];
            try { for (int i = 0; i < list.length(); i++) { ids[i] = list.getJSONObject(i).getString("id"); output.append(ids[i]).append('\n'); } }
            catch (Exception e) { status.setText("Некорректный ответ сервера"); return; }
            documentIds = ids;
            documents.setText(output.toString()); status.setText("Документов: " + list.length());
        });
    }
    private void chooseFile() {
        if (token == null || masterKey == null) { status.setText("Войдите и разблокируйте хранилище."); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        startActivityForResult(intent, PICK_DOCUMENT);
    }
    private void capturePhoto() {
        if (token == null || masterKey == null) { status.setText("Войдите и разблокируйте хранилище."); return; }
        File directory = new File(getCacheDir(), "camera");
        if (!directory.isDirectory() && !directory.mkdirs()) { status.setText("Не удалось подготовить камеру."); return; }
        pendingCapture = new File(directory, "capture-" + UUID.randomUUID() + ".jpg");
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", pendingCapture);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.setClipData(ClipData.newRawUri("camera", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, CAPTURE_DOCUMENT);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            pendingCapture.delete(); pendingCapture = null; status.setText("Приложение камеры недоступно.");
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (pendingCapture != null) state.putString("cameraFile", pendingCapture.getName());
        super.onSaveInstanceState(state);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE_DOCUMENT) {
            File capture = pendingCapture; pendingCapture = null;
            if (capture == null) return;
            if (resultCode != RESULT_OK || token == null || masterKey == null) {
                capture.delete(); status.setText("Съёмка отменена или сессия завершена. При необходимости войдите снова."); return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", capture);
            uploadUri(uri, capture);
            return;
        }
        if (requestCode == SAVE_DOCUMENT) {
            byte[] content = pendingDocument; pendingDocument = null;
            if (content == null) return;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) { Arrays.fill(content, (byte) 0); return; }
            Uri destination = data.getData();
            runTask("Сохранение файла…", () -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IllegalStateException("Не удалось открыть файл для записи");
                    out.write(content); return "Файл сохранён без шифрования в выбранном месте.";
                } finally { Arrays.fill(content, (byte) 0); }
            }, message -> status.setText(message));
            return;
        }
        if (requestCode != PICK_DOCUMENT || resultCode != RESULT_OK || data == null || data.getData() == null || token == null || masterKey == null) return;
        uploadUri(data.getData(), null);
    }
    private void uploadUri(Uri uri, File temporaryCapture) {
        byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token;
        runTask("Шифрование и загрузка…", () -> {
            byte[] file = null; byte[] payload = null;
            try {
                String name = fileName(uri); file = readBounded(uri);
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                if (nameBytes.length < 1 || nameBytes.length > 4096) throw new IllegalArgumentException("Некорректное имя файла");
                payload = ByteBuffer.allocate(4 + nameBytes.length + file.length).putInt(nameBytes.length).put(nameBytes).put(file).array();
                String id = UUID.randomUUID().toString();
                selectedApi.upload(selectedToken, id, VaultCrypto.encrypt(key, payload, "document:" + id));
                byte[] metadata = new JSONObject().put("name", name).put("text", "").put("expiry", "").toString().getBytes(StandardCharsets.UTF_8);
                try { selectedApi.saveMetadata(selectedToken, id, VaultCrypto.encrypt(key, metadata, "metadata:" + id)); }
                catch (Exception ignored) { return new UploadResult(id, name, null, "Документ загружен. Сохранить название не удалось."); }
                finally { Arrays.fill(metadata, (byte) 0); }
                String mime = getContentResolver().getType(uri);
                if ((mime != null && mime.equals("application/pdf")) || name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
                    return new UploadResult(id, name, null, "PDF загружен. OCR PDF на Android пока не реализован.");
                try { return new UploadResult(id, name, LocalOcr.recognize(getApplicationContext(), file), "Документ загружен и распознан."); }
                catch (Exception | LinkageError error) { return new UploadResult(id, name, null, "Документ загружен, но OCR не сработал: " + error.getClass().getSimpleName()); }
            } finally { Arrays.fill(key, (byte) 0); if (file != null) Arrays.fill(file, (byte) 0); if (payload != null) Arrays.fill(payload, (byte) 0); if (temporaryCapture != null) temporaryCapture.delete(); }
        }, result -> {
            status.setText(result.message);
            if (result.text != null) confirmOcr(result);
        });
    }
    private static final class UploadResult {
        final String id, name, text, message;
        UploadResult(String id, String name, String text, String message) { this.id = id; this.name = name; this.text = text; this.message = message; }
    }
    private void confirmOcr(UploadResult result) {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(24, 12, 24, 12);
        java.util.List<java.time.LocalDate> dates = DateSuggestions.find(result.text);
        TextView label = new TextView(this); label.setText("Найденные даты: " + dates + "\nУкажите срок действия вручную в формате ГГГГ-ММ-ДД или оставьте поле пустым."); content.addView(label);
        EditText expiry = new EditText(this); expiry.setHint("ГГГГ-ММ-ДД"); expiry.setSingleLine(true); content.addView(expiry);
        TextView text = new TextView(this); text.setText(result.text); ScrollView scroll = new ScrollView(this); scroll.addView(text); content.addView(scroll, new LinearLayout.LayoutParams(-1, 400));
        new AlertDialog.Builder(this).setTitle("Проверьте результат OCR").setView(content)
            .setNegativeButton("Не сохранять", (dialog, which) -> status.setText("Текст OCR не сохранён."))
            .setPositiveButton("Сохранить", (dialog, which) -> saveOcr(result, expiry.getText().toString().trim())).show();
    }
    private void saveOcr(UploadResult result, String expiry) {
        if (masterKey == null || token == null) { status.setText("Хранилище заблокировано."); return; }
        if (!expiry.isEmpty()) try { java.time.LocalDate.parse(expiry); } catch (java.time.DateTimeException e) { status.setText("Неверная дата. Текст OCR не сохранён."); return; }
        byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token;
        runTask("Шифрование текста OCR…", () -> {
            byte[] bytes = null;
            try {
                bytes = new JSONObject().put("name", result.name).put("text", result.text).put("expiry", expiry).toString().getBytes(StandardCharsets.UTF_8);
                selectedApi.saveMetadata(selectedToken, result.id, VaultCrypto.encrypt(key, bytes, "metadata:" + result.id));
                return "Распознанный текст и срок сохранены в зашифрованном виде.";
            } finally { Arrays.fill(key, (byte) 0); if (bytes != null) Arrays.fill(bytes, (byte) 0); }
        }, message -> status.setText(message));
    }
    private void chooseDocument() {
        if (token == null || masterKey == null) { status.setText("Войдите и разблокируйте хранилище."); return; }
        if (documentIds.length == 0) { status.setText("Обновите список документов."); return; }
        new AlertDialog.Builder(this).setTitle("Выберите документ")
            .setItems(documentIds, (dialog, selected) -> download(documentIds[selected])).setNegativeButton("Отмена", null).show();
    }
    private void download(String id) {
        byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token;
        runTask("Загрузка и расшифровка…", () -> {
            byte[] plain = null;
            try {
                plain = VaultCrypto.decrypt(key, selectedApi.download(selectedToken, id), "document:" + id);
                ByteBuffer buffer = ByteBuffer.wrap(plain);
                if (buffer.remaining() < 5) throw new IllegalArgumentException("Повреждён документ");
                int length = buffer.getInt();
                if (length < 1 || length > 4096 || length > buffer.remaining()) throw new IllegalArgumentException("Повреждён документ");
                byte[] nameBytes = new byte[length]; buffer.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8).replace('\\', '/');
                name = name.substring(name.lastIndexOf('/') + 1);
                if (name.trim().isEmpty() || name.equals(".") || name.equals("..")) name = "document";
                byte[] content = new byte[buffer.remaining()]; buffer.get(content);
                return new FileData(name, content);
            } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); }
        }, file -> {
            if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0);
            pendingDocument = file.content;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream"); intent.putExtra(Intent.EXTRA_TITLE, file.name);
            startActivityForResult(intent, SAVE_DOCUMENT);
        });
    }
    private static final class FileData {
        final String name; final byte[] content;
        FileData(String name, byte[] content) { this.name = name; this.content = content; }
    }
    private String fileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        }
        return "document";
    }
    private byte[] readBounded(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Не удалось открыть файл");
            byte[] buffer = new byte[8192]; int n; int total = 0;
            while ((n = in.read(buffer)) != -1) { total += n; if (total > MAX_FILE_BYTES) throw new IllegalArgumentException("Размер файла превышает 15 МиБ"); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    private void lock() { if (masterKey != null) Arrays.fill(masterKey, (byte) 0); if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0); masterKey = null; pendingDocument = null; documentIds = new String[0]; documents.setText(""); status.setText("Хранилище заблокировано."); }
    @Override protected void onDestroy() { if (masterKey != null) Arrays.fill(masterKey, (byte) 0); if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0); masterKey = null; pendingDocument = null; token = null; executor.shutdownNow(); super.onDestroy(); }
}
