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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.annotation.NonNull;
import javax.crypto.Cipher;

public final class MainActivity extends FragmentActivity {
    private static final int PICK_DOCUMENT = 100;
    private static final int SAVE_DOCUMENT = 101;
    private static final int CAPTURE_DOCUMENT = 102;
    private static final int NOTIFICATION_PERMISSION = 103;
    private static final int MAX_FILE_BYTES = 15 * 1024 * 1024;
    private static final String DEFAULT_SERVER = "https://95.174.95.143";
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "vault-worker"));
    private EditText serverField, usernameField, passwordField, recoveryField;
    private EditText registerServer, registerUsername, registerPassword, registerRecovery, registerConfirm;
    private EditText searchField;
    private TextView status, heroTitle, heroSubtitle, documentCount;
    private LinearLayout progressPanel;
    private TextView progressText;
    private LinearLayout loginPanel, registerPanel, unlockPanel, vaultPanel, documentList, folderBar;
    private VaultApi api;
    private String token;
    private byte[] masterKey;
    private byte[] pendingDocument;
    private String[] documentIds = new String[0];
    private String[] documentNames = new String[0];
    private final java.util.Map<String, String> documentExpiry = new java.util.HashMap<>();
    private final java.util.Map<String, String> documentText = new java.util.HashMap<>();
    private final java.util.Map<String, String> documentFolders = new java.util.HashMap<>();
    private String selectedFolder = null;
    private AlertDialog previewDialog;
    private File pendingCapture;
    private BiometricKeyStore biometricStore;
    private String accountIdentity;
    private Button biometricUnlock;
    private volatile boolean busy;
    private boolean creatingAccount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        biometricStore = new BiometricKeyStore(this);
        String pendingName = state == null ? null : state.getString("cameraFile");
        File cameraDir = new File(getCacheDir(), "camera");
        if (pendingName != null && pendingName.matches("capture-[a-zA-Z0-9-]+\\.jpg")) pendingCapture = new File(cameraDir, pendingName);
        File[] stale = cameraDir.listFiles();
        if (stale != null) for (File file : stale) if (pendingCapture == null || !file.equals(pendingCapture)) file.delete();
        File[] stalePdf = new File(getCacheDir(), "ocr-pdf").listFiles();
        if (stalePdf != null) for (File file : stalePdf) if (file.isFile() && file.getName().startsWith("document-")) file.delete();
        File[] stalePreviews = new File(getCacheDir(), "preview").listFiles();
        if (stalePreviews != null) for (File file : stalePreviews) if (file.isFile() && file.getName().startsWith("preview-")) file.delete();
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
        space(layout, 14); status = label("Укажите адрес сервера и войдите в аккаунт.", 13, muted(), false);
        status.setPadding(dp(14), dp(12), dp(14), dp(12)); status.setBackground(round(Color.rgb(231, 239, 235), 14)); layout.addView(status);
        space(layout, 20);

        loginPanel = card();
        loginPanel.addView(label("Вход в аккаунт", 19, ink(), true));
        space(loginPanel, 5); loginPanel.addView(label("Используйте данные существующего аккаунта.", 13, muted(), false));
        space(loginPanel, 20);
        loginPanel.addView(label("Адрес сервера · можно изменить", 12, ink(), true)); serverField = field(loginPanel, "https://example.com", false);
        serverField.setText(getPreferences(MODE_PRIVATE).getString("server_address", DEFAULT_SERVER));
        loginPanel.addView(label("Логин", 12, ink(), true)); usernameField = field(loginPanel, "Ваш логин", false);
        loginPanel.addView(label("Пароль", 12, ink(), true)); passwordField = field(loginPanel, "Пароль входа", true);
        button(loginPanel, "Войти", true, this::login);
        space(loginPanel, 8); button(loginPanel, "Создать аккаунт", false, this::showRegistration);
        layout.addView(loginPanel);

        registerPanel = card(); registerPanel.addView(label("Новый аккаунт", 19, ink(), true));
        space(registerPanel, 5); registerPanel.addView(label("Пароль восстановления защищает ключ к документам. Сохраните его отдельно.", 13, muted(), false));
        space(registerPanel, 18);
        registerPanel.addView(label("Адрес сервера · можно изменить", 12, ink(), true)); registerServer = field(registerPanel, "https://example.com", false);
        registerPanel.addView(label("Логин", 12, ink(), true)); registerUsername = field(registerPanel, "От 3 до 40 латинских символов", false);
        registerPanel.addView(label("Пароль входа", 12, ink(), true)); registerPassword = field(registerPanel, "Не менее 12 символов", true);
        registerPanel.addView(label("Пароль восстановления", 12, ink(), true)); registerRecovery = field(registerPanel, "Отдельный пароль", true);
        registerPanel.addView(label("Повторите пароль восстановления", 12, ink(), true)); registerConfirm = field(registerPanel, "Повторите пароль", true);
        button(registerPanel, "Зарегистрироваться", true, this::register);
        space(registerPanel, 8); button(registerPanel, "Уже есть аккаунт", false, this::showLogin);
        layout.addView(registerPanel);

        unlockPanel = card(); unlockPanel.addView(label("Откройте хранилище", 19, ink(), true));
        space(unlockPanel, 7); unlockPanel.addView(label("Введите пароль восстановления, чтобы получить доступ к документам.", 14, muted(), false));
        space(unlockPanel, 18); unlockPanel.addView(label("Пароль восстановления", 12, ink(), true));
        recoveryField = field(unlockPanel, "Ваш пароль восстановления", true);
        button(unlockPanel, "Разблокировать", true, this::unlock);
        space(unlockPanel, 8); biometricUnlock = button(unlockPanel, "Войти по биометрии", false, this::unlockWithBiometrics);
        space(unlockPanel, 8); button(unlockPanel, "Выйти из аккаунта", false, this::logout);
        layout.addView(unlockPanel);

        vaultPanel = column();
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = column(); LinearLayout right = column();
        button(left, "+  Добавить файл", true, this::chooseFile); button(right, "Камера", false, this::capturePhoto);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1); leftParams.setMargins(0, 0, dp(8), 0);
        actions.addView(left, leftParams); actions.addView(right, new LinearLayout.LayoutParams(0, -2, 1)); vaultPanel.addView(actions);
        space(vaultPanel, 20); searchField = field(vaultPanel, "Поиск по документам", false);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { renderDocuments(); }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        vaultPanel.addView(label("Поиск по названию и тексту во всех папках", 12, muted(), false));
        space(vaultPanel, 14);
        TextView folderLabel = label("Папки", 17, ink(), true); vaultPanel.addView(folderLabel);
        space(vaultPanel, 8); HorizontalScrollView folders = new HorizontalScrollView(this); folders.setHorizontalScrollBarEnabled(false);
        folderBar = new LinearLayout(this); folderBar.setOrientation(LinearLayout.HORIZONTAL); folders.addView(folderBar); vaultPanel.addView(folders);
        space(vaultPanel, 16);
        space(vaultPanel, 2);
        LinearLayout section = new LinearLayout(this); section.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionTitle = label("Мои документы", 20, ink(), true); section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
        documentCount = label("0", 12, Color.rgb(26, 105, 91), true); documentCount.setGravity(Gravity.CENTER); documentCount.setBackground(round(Color.rgb(223, 240, 233), 20));
        section.addView(documentCount, new LinearLayout.LayoutParams(dp(34), dp(30))); vaultPanel.addView(section);
        space(vaultPanel, 12); documentList = column(); vaultPanel.addView(documentList);
        space(vaultPanel, 14); button(vaultPanel, "Обновить список", false, this::refresh);
        space(vaultPanel, 8); button(vaultPanel, "Настроить биометрию", false, this::enableBiometrics);
        space(vaultPanel, 8); button(vaultPanel, "Заблокировать", false, this::lock);
        layout.addView(vaultPanel);

        space(layout, 18); TextView foot = label("Данные шифруются на устройстве", 12, muted(), false); foot.setGravity(Gravity.CENTER); layout.addView(foot);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(layout);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this); frame.addView(scroll);
        progressPanel = new LinearLayout(this); progressPanel.setGravity(Gravity.CENTER_VERTICAL); progressPanel.setPadding(dp(16), dp(12), dp(16), dp(12)); progressPanel.setBackground(round(Color.rgb(22, 79, 71), 16));
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this); spinner.setIndeterminate(true); progressPanel.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
        progressText = label("", 14, Color.WHITE, true); progressText.setPadding(dp(12), 0, 0, 0); progressPanel.addView(progressText);
        android.widget.FrameLayout.LayoutParams progressParams = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); progressParams.setMargins(dp(16), 0, dp(16), dp(20)); frame.addView(progressPanel, progressParams); progressPanel.setVisibility(View.GONE); setContentView(frame);
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
    private Button button(LinearLayout layout, String text, boolean primary, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(25, 86, 74)); button.setBackground(round(primary ? Color.rgb(22, 97, 81) : Color.rgb(226, 239, 232), 14));
        button.setElevation(0); button.setOnClickListener(v -> { if (!busy) action.run(); }); layout.addView(button, new LinearLayout.LayoutParams(-1, dp(52))); return button;
    }
    private void updateScreen() {
        boolean loggedIn = token != null, unlocked = masterKey != null;
        loginPanel.setVisibility(!loggedIn && !creatingAccount ? View.VISIBLE : View.GONE);
        registerPanel.setVisibility(!loggedIn && creatingAccount ? View.VISIBLE : View.GONE);
        unlockPanel.setVisibility(loggedIn && !unlocked ? View.VISIBLE : View.GONE);
        vaultPanel.setVisibility(unlocked ? View.VISIBLE : View.GONE);
        biometricUnlock.setVisibility(loggedIn && accountIdentity != null && biometricStore.hasKey(accountIdentity) ? View.VISIBLE : View.GONE);
        heroTitle.setText(unlocked ? "Документы" : loggedIn ? "Вход выполнен" : creatingAccount ? "Создайте\nхранилище" : "Документы\nпод защитой");
        heroSubtitle.setText(unlocked ? "Всё важное под рукой." : loggedIn ? "Остался один шаг до ваших документов." : creatingAccount ? "Доступ к документам только у вас." : "Войдите, чтобы открыть своё хранилище.");
    }
    private void showRegistration() {
        registerServer.setText(serverField.getText()); registerUsername.setText(usernameField.getText()); creatingAccount = true; updateScreen();
        status.setText("Создайте два разных пароля и сохраните пароль восстановления.");
    }
    private void showLogin() {
        serverField.setText(registerServer.getText()); usernameField.setText(registerUsername.getText());
        registerPassword.setText(""); registerRecovery.setText(""); registerConfirm.setText(""); creatingAccount = false; updateScreen();
        status.setText("Укажите адрес сервера и войдите в аккаунт.");
    }
    private void register() {
        String address = registerServer.getText().toString().trim();
        String name = registerUsername.getText().toString().trim();
        String password = registerPassword.getText().toString();
        char[] recovery = registerRecovery.getText().toString().toCharArray();
        char[] confirmation = registerConfirm.getText().toString().toCharArray();
        try {
            if (!name.matches("[a-zA-Z0-9_.-]{3,40}")) { status.setText("Логин: 3–40 латинских букв, цифр или символов _ . -"); Arrays.fill(recovery, '\0'); return; }
            if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) { status.setText("Пароль входа: от 12 символов до 72 байт UTF-8."); Arrays.fill(recovery, '\0'); return; }
            if (recovery.length < 12 || !Arrays.equals(recovery, confirmation) || Arrays.equals(recovery, password.toCharArray())) {
                status.setText("Пароль восстановления должен содержать не менее 12 символов, отличаться от пароля входа и совпадать с повтором."); Arrays.fill(recovery, '\0'); return;
            }
        } finally { Arrays.fill(confirmation, '\0'); }
        registerPassword.setText(""); registerRecovery.setText(""); registerConfirm.setText("");
        runTask("Создаём аккаунт…", () -> {
            try { new VaultApi(address).register(name, password, recovery); return true; }
            finally { Arrays.fill(recovery, '\0'); }
        }, success -> {
            rememberServer(address); serverField.setText(address); usernameField.setText(name); creatingAccount = false; updateScreen();
            status.setText("Аккаунт создан. Войдите с паролем входа.");
        });
    }
    private void renderDocuments() {
        renderFolders();
        documentList.removeAllViews();
        String query = searchField.getText().toString().trim();
        int matches = 0;
        for (int i = 0; i < documentIds.length; i++) {
            String name = i < documentNames.length ? documentNames[i] : documentIds[i];
            if ((!query.isEmpty() || selectedFolder == null || selectedFolder.equals(documentFolders.getOrDefault(documentIds[i], ""))) && (query.isEmpty() || OcrSearch.matches(name + " " + documentText.getOrDefault(documentIds[i], ""), query))) matches++;
        }
        documentCount.setText(String.valueOf(matches));
        if (matches == 0 && !query.isEmpty()) {
            LinearLayout empty = card(); TextView hint = label("Ничего не найдено. Попробуйте другой запрос.", 14, muted(), false); hint.setGravity(Gravity.CENTER); empty.addView(hint); documentList.addView(empty); return;
        }
        if (documentIds.length == 0) {
            LinearLayout empty = card(); TextView title = label("Здесь пока пусто", 16, ink(), true); title.setGravity(Gravity.CENTER); empty.addView(title);
            space(empty, 6); TextView hint = label("Добавьте файл или сделайте снимок документа.", 13, muted(), false); hint.setGravity(Gravity.CENTER); empty.addView(hint); documentList.addView(empty); return;
        }
        if (matches == 0) { LinearLayout empty = card(); empty.addView(label("В этой папке пока нет документов.", 14, muted(), false)); documentList.addView(empty); return; }
        for (int i = 0; i < documentIds.length; i++) {
            final String id = documentIds[i]; String name = i < documentNames.length ? documentNames[i] : id;
            if (query.isEmpty() && selectedFolder != null && !selectedFolder.equals(documentFolders.getOrDefault(id, ""))) continue;
            if (!query.isEmpty() && !OcrSearch.matches(name + " " + documentText.getOrDefault(id, ""), query)) continue;
            LinearLayout row = new LinearLayout(this); row.setPadding(dp(14), dp(14), dp(14), dp(14)); row.setGravity(Gravity.CENTER_VERTICAL); row.setBackground(round(Color.WHITE, 16));
            String type = name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? "PDF" : "IMG";
            TextView icon = label(type, 11, Color.rgb(23, 99, 81), true); icon.setGravity(Gravity.CENTER); icon.setBackground(round(Color.rgb(226, 240, 233), 12)); row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout detail = column(); detail.setPadding(dp(12), 0, dp(8), 0); TextView title = label(name, 15, ink(), true); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); detail.addView(title);
            String expiry = documentExpiry.get(id);
            String folder = documentFolders.getOrDefault(id, "");
            detail.addView(label((folder.isEmpty() ? "Без папки" : folder) + " · " + (expiry == null ? "Срок не указан" : "До " + expiry), 12, muted(), false)); row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(label("›", 24, muted(), false));
            row.setOnClickListener(v -> { if (!busy) showDocumentActions(id, name); });
            documentList.addView(row); space(documentList, 8);
        }
    }
    private void renderFolders() {
        folderBar.removeAllViews();
        java.util.Set<String> names = new java.util.TreeSet<>(); names.addAll(documentFolders.values()); names.remove("");
        addFolderChip("Все", null); addFolderChip("Без папки", "");
        for (String name : names) addFolderChip(name, name);
    }
    private void addFolderChip(String title, String folder) {
        TextView chip = label(title, 13, java.util.Objects.equals(selectedFolder, folder) ? Color.WHITE : Color.rgb(25, 86, 74), true);
        chip.setPadding(dp(16), dp(10), dp(16), dp(10)); chip.setBackground(round(java.util.Objects.equals(selectedFolder, folder) ? Color.rgb(22, 97, 81) : Color.rgb(226, 239, 232), 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); params.setMargins(0, 0, dp(8), 0); folderBar.addView(chip, params);
        chip.setOnClickListener(v -> { selectedFolder = folder; renderDocuments(); });
    }
    private AlertDialog showDocumentActions(String id, String name) {
        LinearLayout content = column(); content.setPadding(dp(20), dp(12), dp(20), dp(16));
        TextView title = label(name, 19, ink(), true); content.addView(title); space(content, 6);
        String folder = documentFolders.getOrDefault(id, ""); String expiry = documentExpiry.get(id);
        content.addView(label((folder.isEmpty() ? "Без папки" : folder) + " · " + (expiry == null ? "Срок не указан" : "До " + expiry), 13, muted(), false)); space(content, 16);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        button(content, "Открыть документ", true, () -> { dialog.dismiss(); preview(id); }); space(content, 8);
        button(content, "Просмотр с текстом OCR", false, () -> { dialog.dismiss(); previewWithOcr(id); }); space(content, 8);
        button(content, "Переименовать", false, () -> { dialog.dismiss(); renameDocument(id, name); }); space(content, 8);
        button(content, "Переместить в папку", false, () -> { dialog.dismiss(); moveToFolder(id); }); space(content, 8);
        button(content, "Распознать текст заново", false, () -> { dialog.dismiss(); reanalyze(id, true); }); space(content, 8);
        button(content, "Распознать без обрезки", false, () -> { dialog.dismiss(); reanalyze(id, false); }); space(content, 8);
        button(content, "Изменить срок", false, () -> { dialog.dismiss(); editExpiry(id); }); space(content, 8);
        button(content, "Сохранить на устройство", false, () -> { dialog.dismiss(); download(id); });
        ScrollView scroll = new ScrollView(this); scroll.addView(content); dialog.setView(scroll); dialog.show(); return dialog;
    }
    private void renameDocument(String id, String oldName) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(oldName); input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Переименовать документ").setView(input).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.getBytes(StandardCharsets.UTF_8).length > 240) { input.setError("Введите название до 240 байт без слешей."); return; }
            int dot = oldName.lastIndexOf('.'); String extension = dot > 0 ? oldName.substring(dot) : "";
            if (!extension.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).endsWith(extension.toLowerCase(java.util.Locale.ROOT))) name += extension;
            dialog.dismiss(); saveName(id, name);
        })); dialog.show();
    }
    private void saveName(String id, String name) {
        if (masterKey == null || token == null) return;
        byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token;
        runTask("Переименовываем…", () -> {
            byte[] plain = null, updated = null;
            try {
                byte[] encrypted = selectedApi.metadata(selectedToken, id);
                JSONObject data;
                if (encrypted == null) data = new JSONObject();
                else { plain = VaultCrypto.decrypt(key, encrypted, "metadata:" + id); data = new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
                data.put("name", name); updated = data.toString().getBytes(StandardCharsets.UTF_8);
                selectedApi.saveMetadata(selectedToken, id, VaultCrypto.encrypt(key, updated, "metadata:" + id)); return true;
            } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); if (updated != null) Arrays.fill(updated, (byte) 0); }
        }, ignored -> refresh("Документ переименован."));
    }
    private interface Task<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }
    private <T> void runTask(String message, Task<T> task, Done<T> done) {
        if (busy) return;
        busy = true; status.setText(message); progressText.setText(message); progressPanel.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                T value = task.run();
                runOnUiThread(() -> {
                    busy = false; progressPanel.setVisibility(View.GONE);
                    if (!isFinishing() && !isDestroyed()) done.accept(value);
                    else if (value instanceof byte[]) Arrays.fill((byte[]) value, (byte) 0);
                    else if (value instanceof FileData) Arrays.fill(((FileData) value).content, (byte) 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { busy = false; if (!isFinishing() && !isDestroyed()) { progressPanel.setVisibility(View.GONE); String error = "Ошибка: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); status.setText(error); android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show(); } });
            }
        });
    }
    private void taskMessage(String message) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && busy) { progressText.setText(message); status.setText(message); } }); }
    private void login() {
        if (token != null) { status.setText("Сначала закройте приложение для смены аккаунта."); return; }
        String address = serverField.getText().toString().trim();
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString(); passwordField.setText("");
        runTask("Вход…", () -> {
            VaultApi selectedApi = new VaultApi(address);
            return new LoginResult(selectedApi, selectedApi.login(username, password), address + "\u0000" + username.toLowerCase(java.util.Locale.ROOT));
        }, result -> { rememberServer(address); api = result.api; token = result.token; accountIdentity = result.identity; updateScreen(); status.setText("Вход выполнен. Разблокируйте хранилище."); });
    }
    private void rememberServer(String address) {
        if (address.regionMatches(true, 0, "https://", 0, 8)) getPreferences(MODE_PRIVATE).edit().putString("server_address", address).apply();
    }
    private static final class LoginResult {
        final VaultApi api; final String token, identity;
        LoginResult(VaultApi api, String token, String identity) { this.api = api; this.token = token; this.identity = identity; }
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
            masterKey = null; token = null; api = null; accountIdentity = null; documentIds = new String[0]; documentNames = new String[0]; documentExpiry.clear(); documentText.clear(); documentFolders.clear(); selectedFolder = null; searchField.setText("");
            renderDocuments(); updateScreen(); status.setText("Вы вышли из аккаунта.");
        });
    }
    private void refresh() { refresh(null); }
    private void refresh(String completionMessage) {
        if (token == null) { status.setText("Сначала войдите в аккаунт."); return; }
        VaultApi selectedApi = api; String selectedToken = token; byte[] key = masterKey == null ? null : masterKey.clone(); String identity = accountIdentity;
        runTask("Обновляем документы…", () -> {
            try {
                JSONArray list = selectedApi.list(selectedToken);
                String[] ids = new String[list.length()], names = new String[list.length()];
                java.util.Map<String, String> dates = new java.util.HashMap<>();
                java.util.Map<String, String> texts = new java.util.HashMap<>();
                java.util.Map<String, String> folders = new java.util.HashMap<>();
                for (int i = 0; i < list.length(); i++) {
                    ids[i] = list.getJSONObject(i).getString("id"); names[i] = ids[i];
                    if (key != null) {
                        byte[] encrypted = selectedApi.metadata(selectedToken, ids[i]);
                        if (encrypted != null) {
                            byte[] plain = VaultCrypto.decrypt(key, encrypted, "metadata:" + ids[i]);
                            try { JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                                String name = data.optString("name", ""); if (!name.trim().isEmpty()) names[i] = name;
                                String text = data.optString("text", ""); if (!text.isEmpty()) texts.put(ids[i], text);
                                String folder = data.optString("folder", ""); if (!folder.isEmpty()) folders.put(ids[i], folder);
                                String date = data.optString("expiry", ""); if (!date.isEmpty()) { java.time.LocalDate.parse(date); dates.put(ids[i], date); }
                            }
                            finally { Arrays.fill(plain, (byte) 0); }
                        }
                    }
                }
                boolean reminderError = false;
                if (key != null && identity != null) try { new ReminderStore(getApplicationContext()).replaceAccount(identity, dates); } catch (Exception e) { reminderError = true; }
                return new DocumentListing(ids, names, dates, texts, folders, reminderError);
            } finally { if (key != null) Arrays.fill(key, (byte) 0); }
        }, listing -> {
            documentIds = listing.ids; documentNames = listing.names; documentExpiry.clear(); documentExpiry.putAll(listing.dates); documentText.clear(); documentText.putAll(listing.texts); documentFolders.clear(); documentFolders.putAll(listing.folders); renderDocuments();
            String resultMessage = completionMessage == null ? "Документы обновлены." : completionMessage;
            status.setText(listing.reminderError ? resultMessage + " Напоминания не обновлены." : resultMessage);
            if (!listing.reminderError && !listing.dates.isEmpty()) enableReminders();
        });
    }
    private static final class DocumentListing {
        final String[] ids, names; final java.util.Map<String, String> dates, texts, folders; final boolean reminderError;
        DocumentListing(String[] ids, String[] names, java.util.Map<String, String> dates, java.util.Map<String, String> texts, java.util.Map<String, String> folders, boolean reminderError) { this.ids = ids; this.names = names; this.dates = dates; this.texts = texts; this.folders = folders; this.reminderError = reminderError; }
    }
    private void moveToFolder(String id) {
        if (masterKey == null || token == null) return;
        java.util.Set<String> existing = new java.util.TreeSet<>(); existing.addAll(documentFolders.values()); existing.remove("");
        java.util.List<String> choices = new java.util.ArrayList<>(); choices.add("Без папки"); choices.addAll(existing); choices.add("+ Новая папка");
        new AlertDialog.Builder(this).setTitle("Переместить документ").setItems(choices.toArray(new String[0]), (dialog, which) -> {
            if (which == choices.size() - 1) {
                EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Название папки");
                new AlertDialog.Builder(this).setTitle("Новая папка").setView(input).setNegativeButton("Отмена", null).setPositiveButton("Переместить", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty() || value.length() > 80 || value.contains("/") || value.contains("\\")) { status.setText("Название папки: от 1 до 80 символов, без слешей."); return; }
                    saveFolder(id, value);
                }).show();
            } else saveFolder(id, which == 0 ? "" : choices.get(which));
        }).setNegativeButton("Отмена", null).show();
    }
    private void saveFolder(String id, String folder) {
        if (masterKey == null || token == null) return;
        byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token;
        runTask("Сохраняем папку…", () -> {
            byte[] plain = null, updated = null;
            try {
                byte[] encrypted = selectedApi.metadata(selectedToken, id);
                JSONObject data;
                if (encrypted == null) data = new JSONObject();
                else { plain = VaultCrypto.decrypt(key, encrypted, "metadata:" + id); data = new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
                data.put("folder", folder); updated = data.toString().getBytes(StandardCharsets.UTF_8);
                selectedApi.saveMetadata(selectedToken, id, VaultCrypto.encrypt(key, updated, "metadata:" + id));
                return true;
            } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); if (updated != null) Arrays.fill(updated, (byte) 0); }
        }, ignored -> { selectedFolder = folder; refresh("Документ перемещён."); });
    }
    private void editExpiry(String id) {
        if (masterKey == null || token == null) { status.setText("Сначала разблокируйте хранилище."); return; }
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("ГГГГ-ММ-ДД"); input.setText(documentExpiry.getOrDefault(id, ""));
        new AlertDialog.Builder(this).setTitle("Срок действия").setMessage("Введите дату в формате ГГГГ-ММ-ДД или очистите поле.")
            .setView(input).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (dialog, which) -> {
                String expiry = input.getText().toString().trim();
                if (!expiry.isEmpty()) try { java.time.LocalDate.parse(expiry); } catch (java.time.DateTimeException e) { status.setText("Неверная дата. Срок не изменён."); return; }
                if (masterKey == null || token == null) { status.setText("Хранилище заблокировано."); return; }
                byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token, identity = accountIdentity;
                runTask("Сохраняем срок…", () -> {
                    byte[] plain = null, updated = null;
                    try {
                        byte[] encrypted = selectedApi.metadata(selectedToken, id);
                        JSONObject data;
                        if (encrypted == null) data = new JSONObject().put("name", "").put("text", "");
                        else { plain = VaultCrypto.decrypt(key, encrypted, "metadata:" + id); data = new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
                        data.put("expiry", expiry); updated = data.toString().getBytes(StandardCharsets.UTF_8);
                        selectedApi.saveMetadata(selectedToken, id, VaultCrypto.encrypt(key, updated, "metadata:" + id));
                        if (identity != null) try { new ReminderStore(getApplicationContext()).put(identity, id, expiry); }
                        catch (Exception e) { return "Срок сохранён, но напоминание не настроено."; }
                        return "Срок сохранён.";
                    } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); if (updated != null) Arrays.fill(updated, (byte) 0); }
                }, message -> { if (!expiry.isEmpty() && !message.contains("не настроено")) enableReminders(); refresh(message); });
            }).show();
    }
    private void enableReminders() {
        ReminderScheduler.schedule(getApplicationContext());
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATION_PERMISSION && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ReminderScheduler.schedule(getApplicationContext());
    }
    private boolean biometricAvailable() {
        if (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) return true;
        status.setText("Надёжная биометрия недоступна или не настроена. Используйте пароль восстановления."); return false;
    }
    private BiometricPrompt.PromptInfo biometricPromptInfo(String title) {
        return new BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Подтвердите личность на этом устройстве")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Отмена").build();
    }
    private void enableBiometrics() {
        if (masterKey == null || accountIdentity == null || !biometricAvailable()) return;
        final String alias;
        final Cipher cipher;
        try { alias = biometricStore.createAlias(); cipher = biometricStore.encryptionCipher(alias); }
        catch (Exception e) { status.setText("Не удалось подготовить защищённый ключ устройства."); return; }
        byte[] copy = masterKey.clone(); String identity = accountIdentity; busy = true;
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                try {
                    Cipher authorized = result.getCryptoObject() == null ? null : result.getCryptoObject().getCipher();
                    if (authorized == null) throw new IllegalStateException("Нет подтверждённого шифра");
                    biometricStore.store(identity, alias, authorized.getIV(), authorized.doFinal(copy));
                    status.setText("Биометрия настроена для этого устройства."); updateScreen();
                } catch (Exception e) { try { biometricStore.removeAlias(alias); } catch (Exception ignored) { } status.setText("Не удалось сохранить биометрический ключ."); }
                finally { Arrays.fill(copy, (byte) 0); busy = false; }
            }
            @Override public void onAuthenticationError(int code, @NonNull CharSequence message) {
                Arrays.fill(copy, (byte) 0); try { biometricStore.removeAlias(alias); } catch (Exception ignored) { }
                busy = false; status.setText("Биометрия не настроена: " + message);
            }
        });
        try { prompt.authenticate(biometricPromptInfo("Настроить биометрию"), new BiometricPrompt.CryptoObject(cipher)); }
        catch (RuntimeException e) { Arrays.fill(copy, (byte) 0); try { biometricStore.removeAlias(alias); } catch (Exception ignored) { } busy = false; status.setText("Не удалось открыть биометрическое подтверждение."); }
    }
    private void unlockWithBiometrics() {
        if (accountIdentity == null || !biometricAvailable()) return;
        String identity = accountIdentity;
        final Cipher cipher;
        final byte[] encrypted;
        try { cipher = biometricStore.decryptionCipher(identity); encrypted = biometricStore.encryptedKey(identity); }
        catch (Exception e) { status.setText("Ключ устройства недоступен. Разблокируйте пароль восстановления и настройте биометрию заново."); return; }
        busy = true;
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                try {
                    Cipher authorized = result.getCryptoObject() == null ? null : result.getCryptoObject().getCipher();
                    if (authorized == null) throw new IllegalStateException("Нет подтверждённого шифра");
                    byte[] recovered = authorized.doFinal(encrypted);
                    if (recovered.length != 32) { Arrays.fill(recovered, (byte) 0); throw new IllegalStateException("Некорректный ключ"); }
                    if (masterKey != null) Arrays.fill(masterKey, (byte) 0);
                    masterKey = recovered; busy = false; updateScreen(); status.setText("Хранилище разблокировано."); refresh();
                } catch (Exception e) { busy = false; status.setText("Не удалось разблокировать. Используйте пароль восстановления."); }
            }
            @Override public void onAuthenticationError(int code, @NonNull CharSequence message) { busy = false; status.setText("Биометрическое подтверждение: " + message); }
        });
        try { prompt.authenticate(biometricPromptInfo("Открыть хранилище"), new BiometricPrompt.CryptoObject(cipher)); }
        catch (RuntimeException e) { busy = false; status.setText("Не удалось открыть биометрическое подтверждение."); }
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
                catch (Exception error) { return new UploadResult(id, name, null, error instanceof VaultApi.HttpFailure && ((VaultApi.HttpFailure) error).status == 413 ? "Документ загружен, но для названия нет места в хранилище." : "Документ загружен. Сохранить название не удалось."); }
                finally { Arrays.fill(metadata, (byte) 0); }
                String mime = getContentResolver().getType(uri);
                boolean isPdf = (mime != null && mime.equals("application/pdf")) || name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
                taskMessage("Распознаём текст…");
                try {
                    if (isPdf) return new UploadResult(id, name, LocalOcr.recognizePdf(getApplicationContext(), file, (page, total) -> taskMessage("Распознаём страницу " + page + " из " + total + "…")), "Документ загружен и распознан.");
                    LocalOcr.ImageResult result = LocalOcr.recognizeImage(getApplicationContext(), file);
                    return new UploadResult(id, name, result.text, result.cropped ? "Границы документа найдены. Проверьте результат." : "Границы не найдены: распознано исходное фото.", result.layout.toString());
                }
                catch (Exception | LinkageError error) { return new UploadResult(id, name, null, "Документ загружен, но OCR не сработал: " + error.getClass().getSimpleName()); }
            } finally { Arrays.fill(key, (byte) 0); if (file != null) Arrays.fill(file, (byte) 0); if (payload != null) Arrays.fill(payload, (byte) 0); if (temporaryCapture != null) temporaryCapture.delete(); }
        }, result -> {
            status.setText(result.message);
            if (result.text != null) confirmOcr(result); else refresh(result.message);
        });
    }
    private static final class UploadResult {
        final String id, name, text, message, layout;
        UploadResult(String id, String name, String text, String message) { this(id, name, text, message, null); }
        UploadResult(String id, String name, String text, String message, String layout) { this.id = id; this.name = name; this.text = text; this.message = message; this.layout = layout; }
    }
    private void reanalyze(String id, boolean detectPage) {
        loadFile(id, file -> runTask("Распознаём текст…", () -> {
            try {
                boolean pdf = file.name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
                if (pdf) return new UploadResult(id, file.name, LocalOcr.recognizePdf(getApplicationContext(), file.content, (page, total) -> taskMessage("Распознаём страницу " + page + " из " + total + "…")), "Распознавание завершено.");
                LocalOcr.ImageResult result = LocalOcr.recognizeImage(getApplicationContext(), file.content, detectPage);
                return new UploadResult(id, file.name, result.text, result.cropped ? "Границы документа найдены. Проверьте результат." : detectPage ? "Границы не найдены: распознано исходное фото." : "Распознано исходное фото без обрезки.", result.layout.toString());
            } finally { Arrays.fill(file.content, (byte) 0); }
        }, this::confirmOcr));
    }
    private AlertDialog confirmOcr(UploadResult result) {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(24, 12, 24, 12);
        TextView scanStatus = new TextView(this); scanStatus.setText(result.message); content.addView(scanStatus);
        java.util.List<java.time.LocalDate> dates = DateSuggestions.find(result.text);
        TextView label = new TextView(this); label.setText("Выберите срок или укажите его вручную в формате ГГГГ-ММ-ДД. Если при правке изменится число слов, наложение потребуется создать заново."); content.addView(label);
        EditText expiry = new EditText(this); expiry.setHint("ГГГГ-ММ-ДД"); expiry.setSingleLine(true); expiry.setText(documentExpiry.getOrDefault(result.id, "")); content.addView(expiry);
        if (!dates.isEmpty()) {
            HorizontalScrollView dateScroll = new HorizontalScrollView(this);
            LinearLayout choices = new LinearLayout(this); choices.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < Math.min(dates.size(), 12); i++) {
                String value = dates.get(i).toString(); Button choice = new Button(this); choice.setText(value);
                choice.setOnClickListener(view -> expiry.setText(value)); choices.addView(choice);
            }
            dateScroll.addView(choices); content.addView(dateScroll);
            if (dates.size() > 12) { TextView more = new TextView(this); more.setText("Показаны первые 12 дат. Другую дату можно ввести вручную."); content.addView(more); }
        }
        EditText text = new EditText(this); text.setText(result.text); text.setGravity(Gravity.TOP | Gravity.START); text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); content.addView(text, new LinearLayout.LayoutParams(-1, dp(180)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Проверьте результат OCR").setView(content)
            .setNegativeButton("Не сохранять", (ignoredDialog, which) -> refresh("Текст OCR не сохранён."))
            .setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = expiry.getText().toString().trim();
            if (!value.isEmpty()) try { java.time.LocalDate.parse(value); }
            catch (java.time.DateTimeException error) { expiry.setError("Укажите дату в формате ГГГГ-ММ-ДД"); return; }
            String corrected = text.getText().toString(); String layout = corrected.equals(result.text) ? result.layout : OcrOverlay.correctedLayout(result.layout, corrected);
            dialog.dismiss(); saveOcr(new UploadResult(result.id, result.name, corrected, result.message, layout), value);
        }));
        dialog.show();
        return dialog;
    }
    private void saveOcr(UploadResult result, String expiry) {
        if (masterKey == null || token == null) { status.setText("Хранилище заблокировано."); return; }
        if (!expiry.isEmpty()) try { java.time.LocalDate.parse(expiry); } catch (java.time.DateTimeException e) { status.setText("Неверная дата. Текст OCR не сохранён."); return; }
        byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token; String identity = accountIdentity;
        runTask("Шифрование текста OCR…", () -> {
            byte[] bytes = null, plain = null;
            try {
                byte[] old = selectedApi.metadata(selectedToken, result.id); JSONObject data;
                if (old == null) data = new JSONObject(); else { plain = VaultCrypto.decrypt(key, old, "metadata:" + result.id); data = new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
                data.put("name", result.name).put("text", result.text).put("expiry", expiry);
                if (result.layout == null) data.remove("ocrLayout"); else data.put("ocrLayout", new JSONObject(result.layout));
                bytes = data.toString().getBytes(StandardCharsets.UTF_8);
                selectedApi.saveMetadata(selectedToken, result.id, VaultCrypto.encrypt(key, bytes, "metadata:" + result.id));
                if (identity != null) try { new ReminderStore(getApplicationContext()).put(identity, result.id, expiry); }
                    catch (Exception e) { return "Данные сохранены, но напоминание не настроено."; }
                return "Распознанный текст и срок сохранены в зашифрованном виде.";
            } finally { Arrays.fill(key, (byte) 0); if (bytes != null) Arrays.fill(bytes, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); }
        }, message -> { if (!expiry.isEmpty() && !message.contains("не настроено")) enableReminders(); refresh(message); });
    }
    private void chooseDocument() {
        if (token == null || masterKey == null) { status.setText("Войдите и разблокируйте хранилище."); return; }
        if (documentIds.length == 0) { status.setText("Обновите список документов."); return; }
        new AlertDialog.Builder(this).setTitle("Выберите документ")
            .setItems(documentIds, (dialog, selected) -> download(documentIds[selected])).setNegativeButton("Отмена", null).show();
    }
    private void download(String id) {
        loadFile(id, file -> {
            if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0);
            pendingDocument = file.content;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream"); intent.putExtra(Intent.EXTRA_TITLE, file.name);
            startActivityForResult(intent, SAVE_DOCUMENT);
        });
    }
    private void preview(String id) { loadFile(id, this::showPreview); }
    private void previewWithOcr(String id) {
        if (masterKey == null || token == null) return;
        loadFile(id, file -> {
            if (masterKey == null || token == null) { Arrays.fill(file.content, (byte) 0); return; }
            byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token;
            runTask("Готовим просмотр с текстом…", () -> {
            byte[] plain = null; android.graphics.Bitmap image = null, overlay = null;
            try {
                if (file.name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) throw new IllegalArgumentException("Наложение OCR пока доступно для изображений.");
                byte[] encrypted = selectedApi.metadata(selectedToken, id);
                if (encrypted == null) throw new IllegalArgumentException("Сначала распознайте документ заново.");
                plain = VaultCrypto.decrypt(key, encrypted, "metadata:" + id); JSONObject layout = new JSONObject(new String(plain, StandardCharsets.UTF_8)).optJSONObject("ocrLayout");
                if (layout == null || layout.optJSONArray("words") == null || layout.optJSONArray("words").length() == 0) throw new IllegalArgumentException("Нет координат текста. Распознайте документ заново и сохраните без правок.");
                image = LocalOcr.decodeImage(file.content); overlay = OcrOverlay.render(image, layout);
                ByteArrayOutputStream output = new ByteArrayOutputStream(); if (!overlay.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("Не удалось подготовить просмотр.");
                return new FileData("Распознанный текст.png", output.toByteArray());
            } finally { Arrays.fill(key, (byte) 0); Arrays.fill(file.content, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); if (image != null) image.recycle(); if (overlay != null) overlay.recycle(); }
            }, this::showPreview);
        });
    }
    private void loadFile(String id, Done<FileData> done) {
        byte[] key = masterKey.clone(); VaultApi selectedApi = api; String selectedToken = token;
        String displayName = null; for (int i = 0; i < documentIds.length; i++) if (documentIds[i].equals(id) && i < documentNames.length && !documentNames[i].equals(id)) { displayName = documentNames[i]; break; }
        final String selectedName = displayName;
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
                return new FileData(selectedName == null ? name : selectedName, content);
            } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); }
        }, done);
    }
    private void showPreview(FileData file) {
        try {
            if (file.name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) { showPdfPreview(file); return; }
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(file.content, 0, file.content.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("Формат не поддерживает просмотр. Сохраните файл на устройство.");
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            int sample = 1; while (((long) Math.max(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > 2000) sample *= 2;
            options.inSampleSize = sample;
            android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(file.content, 0, file.content.length, options);
            if (decoded == null) throw new IllegalArgumentException("Не удалось открыть изображение.");
            int orientation = 1;
            try { orientation = new android.media.ExifInterface(new java.io.ByteArrayInputStream(file.content)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1); } catch (java.io.IOException ignored) { }
            android.graphics.Bitmap image;
            try { image = LocalOcr.orientBitmap(decoded, orientation); } catch (Throwable e) { decoded.recycle(); throw e; }
            if (image != decoded) decoded.recycle();
            ImageView view = new ImageView(this); view.setImageBitmap(image); view.setAdjustViewBounds(true); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            previewDialog = new AlertDialog.Builder(this).setTitle(file.name).setView(view).setPositiveButton("Закрыть", null).create();
            previewDialog.setOnDismissListener(d -> { view.setImageDrawable(null); image.recycle(); previewDialog = null; }); previewDialog.show();
        } catch (Exception e) { status.setText("Просмотр: " + e.getMessage()); }
        finally { Arrays.fill(file.content, (byte) 0); }
    }
    private void showPdfPreview(FileData file) throws Exception {
        File directory = new File(getCacheDir(), "preview");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Не удалось подготовить просмотр");
        File temporary = File.createTempFile("preview-", ".pdf", directory);
        android.os.ParcelFileDescriptor descriptor = null; android.graphics.pdf.PdfRenderer renderer = null;
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temporary)) { out.write(file.content); }
            descriptor = android.os.ParcelFileDescriptor.open(temporary, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new android.graphics.pdf.PdfRenderer(descriptor);
            if (renderer.getPageCount() < 1) throw new IllegalArgumentException("PDF не содержит страниц.");
            final android.os.ParcelFileDescriptor opened = descriptor; final android.graphics.pdf.PdfRenderer pdf = renderer;
            LinearLayout content = column(); ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(image, new LinearLayout.LayoutParams(-1, dp(430))); TextView pageLabel = label("", 14, muted(), true); pageLabel.setGravity(Gravity.CENTER); content.addView(pageLabel);
            LinearLayout controls = new LinearLayout(this); Button previous = new Button(this), next = new Button(this); previous.setText("Назад"); next.setText("Далее");
            controls.addView(previous, new LinearLayout.LayoutParams(0, -2, 1)); controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(controls);
            int[] page = {0}; android.graphics.Bitmap[] shown = {null};
            Runnable render = () -> { try (android.graphics.pdf.PdfRenderer.Page current = pdf.openPage(page[0])) {
                float scale = Math.min(2f, 1800f / Math.max(current.getWidth(), current.getHeight()));
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(Math.max(1, Math.round(current.getWidth() * scale)), Math.max(1, Math.round(current.getHeight() * scale)), android.graphics.Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE); current.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                image.setImageBitmap(bitmap); if (shown[0] != null) shown[0].recycle(); shown[0] = bitmap;
                pageLabel.setText("Страница " + (page[0] + 1) + " из " + pdf.getPageCount()); previous.setEnabled(page[0] > 0); next.setEnabled(page[0] + 1 < pdf.getPageCount());
            } catch (Exception e) { status.setText("Не удалось открыть страницу PDF."); } };
            previous.setOnClickListener(v -> { if (page[0] > 0) { page[0]--; render.run(); } }); next.setOnClickListener(v -> { if (page[0] + 1 < pdf.getPageCount()) { page[0]++; render.run(); } });
            previewDialog = new AlertDialog.Builder(this).setTitle(file.name).setView(content).setPositiveButton("Закрыть", null).create();
            previewDialog.setOnDismissListener(d -> { image.setImageDrawable(null); if (shown[0] != null) shown[0].recycle(); pdf.close(); try { opened.close(); } catch (java.io.IOException ignored) { } temporary.delete(); previewDialog = null; });
            previewDialog.show(); render.run();
        } catch (Exception e) { if (renderer != null) renderer.close(); if (descriptor != null) try { descriptor.close(); } catch (java.io.IOException ignored) { } temporary.delete(); throw e; }
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
    private void lock() { if (previewDialog != null) previewDialog.dismiss(); if (masterKey != null) Arrays.fill(masterKey, (byte) 0); if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0); masterKey = null; pendingDocument = null; documentIds = new String[0]; documentNames = new String[0]; documentExpiry.clear(); documentText.clear(); documentFolders.clear(); selectedFolder = null; searchField.setText(""); renderDocuments(); updateScreen(); status.setText("Хранилище заблокировано."); }
    @Override protected void onDestroy() { if (previewDialog != null) previewDialog.dismiss(); if (masterKey != null) Arrays.fill(masterKey, (byte) 0); if (pendingDocument != null) Arrays.fill(pendingDocument, (byte) 0); masterKey = null; pendingDocument = null; token = null; executor.shutdownNow(); super.onDestroy(); }
}
