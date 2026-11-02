package ru.documentvault.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.WindowManager;
import android.view.View;
import android.view.Gravity;
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
    private TextView status, heroTitle, heroSubtitle, documentCount;
    private LinearLayout loginPanel, unlockPanel, vaultPanel, documentList;
    private VaultApi api;
    private String token;
    private byte[] masterKey;
    private byte[] pendingDocument;
    private String[] documentIds = new String[0];
    private String[] documentNames = new String[0];
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
        getWindow().setStatusBarColor(Color.rgb(245, 248, 246));
        getWindow().setNavigationBarColor(Color.rgb(245, 248, 246));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout layout = column(); layout.setPadding(dp(22), dp(24), dp(22), dp(36)); layout.setBackgroundColor(Color.rgb(245, 248, 246));
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label("DV", 15, Color.WHITE, true); mark.setGravity(Gravity.CENTER); mark.setBackground(round(Color.rgb(22, 79, 71), 14));
        brand.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout brandText = column(); brandText.setPadding(dp(12), 0, 0, 0);
        brandText.addView(label("DocumentVault", 17, ink(), true));
        brandText.addView(label("ЛИЧНОЕ ХРАНИЛИЩЕ", 10, muted(), true));
        brand.addView(brandText); layout.addView(brand);
        space(layout, 34);
        heroTitle = label("Документы\nпод защитой", 30, ink(), true); heroTitle.setLineSpacing(dp(2), 1f); layout.addView(heroTitle);
        space(layout, 8);
        heroSubtitle = label("Войдите, чтобы открыть своё хранилище.", 15, muted(), false); layout.addView(heroSubtitle);
        space(layout, 24);

        loginPanel = card();
        loginPanel.addView(label("Вход в аккаунт", 19, ink(), true));
        space(loginPanel, 5); loginPanel.addView(label("Используйте данные существующего аккаунта.", 13, muted(), false));
        space(loginPanel, 20);
        loginPanel.addView(label("Адрес сервера", 12, ink(), true)); serverField = field(loginPanel, "https://example.com", false);
        loginPanel.addView(label("Логин", 12, ink(), true)); usernameField = field(loginPanel, "Ваш логин", false);
        loginPanel.addView(label("Пароль", 12, ink(), true)); passwordField = field(loginPanel, "Пароль входа", true);
        button(loginPanel, "Войти", true, this::login);
        space(loginPanel, 12); loginPanel.addView(label("Регистрация пока доступна в версии для Windows.", 12, muted(), false));
        layout.addView(loginPanel);

        unlockPanel = card(); unlockPanel.addView(label("Откройте хранилище", 19, ink(), true));
        space(unlockPanel, 7); unlockPanel.addView(label("Введите пароль восстановления, чтобы получить доступ к документам.", 14, muted(), false));
        space(unlockPanel, 18); unlockPanel.addView(label("Пароль восстановления", 12, ink(), true));
        recoveryField = field(unlockPanel, "Ваш пароль восстановления", true);
        button(unlockPanel, "Разблокировать", true, this::unlock);
        space(unlockPanel, 8); button(unlockPanel, "Выйти из аккаунта", false, this::logout);
        layout.addView(unlockPanel);

        vaultPanel = column();
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = column(); LinearLayout right = column();
        button(left, "+  Добавить файл", true, this::chooseFile); button(right, "Камера", false, this::capturePhoto);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1); leftParams.setMargins(0, 0, dp(8), 0);
        actions.addView(left, leftParams); actions.addView(right, new LinearLayout.LayoutParams(0, -2, 1)); vaultPanel.addView(actions);
        space(vaultPanel, 24);
        LinearLayout section = new LinearLayout(this); section.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionTitle = label("Мои документы", 20, ink(), true); section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
        documentCount = label("0", 12, Color.rgb(26, 105, 91), true); documentCount.setGravity(Gravity.CENTER); documentCount.setBackground(round(Color.rgb(223, 240, 233), 20));
        section.addView(documentCount, new LinearLayout.LayoutParams(dp(34), dp(30))); vaultPanel.addView(section);
        space(vaultPanel, 12); documentList = column(); vaultPanel.addView(documentList);
        space(vaultPanel, 14); button(vaultPanel, "Обновить список", false, this::refresh);
        space(vaultPanel, 8); button(vaultPanel, "Заблокировать", false, this::lock);
        layout.addView(vaultPanel);

        space(layout, 18);
        status = label("Укажите адрес сервера и войдите в аккаунт.", 13, muted(), false);
        status.setPadding(dp(14), dp(12), dp(14), dp(12)); status.setBackground(round(Color.rgb(231, 239, 235), 14)); layout.addView(status);
        space(layout, 18); TextView foot = label("Данные шифруются на устройстве", 12, muted(), false); foot.setGravity(Gravity.CENTER); layout.addView(foot);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(layout); setContentView(scroll);
        renderDocuments(); updateScreen();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int ink() { return Color.rgb(26, 45, 41); }
    private int muted() { return Color.rgb(105, 126, 118); }
    private GradientDrawable round(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card() { LinearLayout view = column(); view.setPadding(dp(20), dp(22), dp(20), dp(22)); view.setBackground(round(Color.WHITE, 22)); return view; }
    private TextView label(String text, int size, int color, boolean bold) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private EditText field(LinearLayout layout, String hint, boolean secret) {
        EditText input = new EditText(this); input.setHint(hint); input.setSingleLine(true); input.setTextSize(15); input.setTextColor(ink()); input.setHintTextColor(Color.rgb(149, 165, 158));
        input.setPadding(dp(14), 0, dp(14), 0); input.setBackground(round(Color.rgb(245, 248, 246), 12));
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.setMargins(0, dp(7), 0, dp(16)); layout.addView(input, params); return input;
    }
    private void button(LinearLayout layout, String text, boolean primary, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(25, 86, 74)); button.setBackground(round(primary ? Color.rgb(22, 97, 81) : Color.rgb(226, 239, 232), 14));
        button.setElevation(0); button.setOnClickListener(v -> { if (!busy) action.run(); }); layout.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
    }
    private void updateScreen() {
        boolean loggedIn = token != null, unlocked = masterKey != null;
        loginPanel.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        unlockPanel.setVisibility(loggedIn && !unlocked ? View.VISIBLE : View.GONE);
        vaultPanel.setVisibility(unlocked ? View.VISIBLE : View.GONE);
        heroTitle.setText(unlocked ? "Всё важное\nрядом" : loggedIn ? "Вход выполнен" : "Документы\nпод защитой");
        heroSubtitle.setText(unlocked ? "Ваши документы в надёжном месте." : loggedIn ? "Остался один шаг до ваших документов." : "Войдите, чтобы открыть своё хранилище.");
    }
    private void renderDocuments() {
        documentList.removeAllViews(); documentCount.setText(String.valueOf(documentIds.length));
        if (documentIds.length == 0) {
            LinearLayout empty = card(); TextView title = label("Здесь пока пусто", 16, ink(), true); title.setGravity(Gravity.CENTER); empty.addView(title);
            space(empty, 6); TextView hint = label("Добавьте файл или сделайте снимок документа.", 13, muted(), false); hint.setGravity(Gravity.CENTER); empty.addView(hint); documentList.addView(empty); return;
        }
        for (int i = 0; i < documentIds.length; i++) {
            final String id = documentIds[i]; String name = i < documentNames.length ? documentNames[i] : id;
            LinearLayout row = new LinearLayout(this); row.setPadding(dp(14), dp(14), dp(14), dp(14)); row.setGravity(Gravity.CENTER_VERTICAL); row.setBackground(round(Color.WHITE, 16));
            TextView icon = label("DOC", 11, Color.rgb(23, 99, 81), true); icon.setGravity(Gravity.CENTER); icon.setBackground(round(Color.rgb(226, 240, 233), 12)); row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout detail = column(); detail.setPadding(dp(12), 0, dp(8), 0); TextView title = label(name, 15, ink(), true); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); detail.addView(title);
            detail.addView(label("Нажмите, чтобы сохранить", 12, muted(), false)); row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(label("›", 24, muted(), false));
            row.setOnClickListener(v -> { if (!busy) download(id); });
            documentList.addView(row); space(documentList, 8);
        }
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
        }, result -> { api = result.api; token = result.token; updateScreen(); status.setText("Вход выполнен. Разблокируйте хранилище."); });
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
            masterKey = key; updateScreen(); status.setText("Хранилище разблокировано."); refresh();
        });
    }
    private void logout() {
        if (token == null) return;
        VaultApi selectedApi = api; String selectedToken = token;
        runTask("Выход…", () -> { selectedApi.logout(selectedToken); return true; }, ignored -> {
            if (masterKey != null) Arrays.fill(masterKey, (byte) 0);
            masterKey = null; token = null; api = null; documentIds = new String[0]; documentNames = new String[0];
            renderDocuments(); updateScreen(); status.setText("Вы вышли из аккаунта.");
        });
    }
    private void refresh() {
        if (token == null) { status.setText("Сначала войдите в аккаунт."); return; }
        VaultApi selectedApi = api; String selectedToken = token; byte[] key = masterKey == null ? null : masterKey.clone();
        runTask("Обновляем документы…", () -> {
            try {
                JSONArray list = selectedApi.list(selectedToken);
                String[] ids = new String[list.length()], names = new String[list.length()];
                for (int i = 0; i < list.length(); i++) {
                    ids[i] = list.getJSONObject(i).getString("id"); names[i] = ids[i];
                    if (key != null) {
                        byte[] encrypted = selectedApi.metadata(selectedToken, ids[i]);
                        if (encrypted != null) {
                            byte[] plain = VaultCrypto.decrypt(key, encrypted, "metadata:" + ids[i]);
                            try { String name = new JSONObject(new String(plain, StandardCharsets.UTF_8)).optString("name", ""); if (!name.trim().isEmpty()) names[i] = name; }
                            finally { Arrays.fill(plain, (byte) 0); }
                        }
                    }
                }
                return new DocumentListing(ids, names);
            } finally { if (key != null) Arrays.fill(key, (byte) 0); }
        }, listing -> {
            documentIds = listing.ids; documentNames = listing.names; renderDocuments(); status.setText("Документы обновлены.");
        });
    }
    private static final class DocumentListing {
        final String[] ids, names;
        DocumentListing(String[] ids, String[] names) { this.ids = ids; this.names = names; }
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
            if (result.text != null) confirmOcr(result); else refresh();
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
            .setNegativeButton("Не сохранять", (dialog, which) -> { status.setText("Текст OCR не сохранён."); refresh(); })
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
        }, message -> { status.setText(message); refresh(); });
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
    private void lock() { if (masterKey != null) Arrays.fill(masterKey, (byte) 0); if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0); masterKey = null; pendingDocument = null; documentIds = new String[0]; documentNames = new String[0]; renderDocuments(); updateScreen(); status.setText("Хранилище заблокировано."); }
    @Override protected void onDestroy() { if (masterKey != null) Arrays.fill(masterKey, (byte) 0); if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0); masterKey = null; pendingDocument = null; token = null; executor.shutdownNow(); super.onDestroy(); }
}
