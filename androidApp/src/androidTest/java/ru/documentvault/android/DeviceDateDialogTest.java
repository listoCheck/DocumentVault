package ru.documentvault.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises the confirmation dialog using synthetic OCR text. */
@RunWith(AndroidJUnit4.class)
public final class DeviceDateDialogTest {
    @Test public void invalidDateKeepsDialogAndSuggestionCanBeSelected() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            Class<?> resultClass = Class.forName("ru.documentvault.android.MainActivity$UploadResult");
            Constructor<?> constructor = resultClass.getDeclaredConstructor(String.class, String.class, String.class, String.class);
            constructor.setAccessible(true);
            Object result = constructor.newInstance("synthetic-id", "synthetic.jpg", "Срок до 31 декабря 2030", "");
            Method show = MainActivity.class.getDeclaredMethod("confirmOcr", resultClass); show.setAccessible(true);
            AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { dialogRef.set((AlertDialog) show.invoke(activity, result)); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                    AlertDialog dialog = dialogRef.get();
                    View root = dialog.getWindow().getDecorView();
                    EditText input = findEditable(root); assertNotNull(input);
                    input.setText("2030-99-99");
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    assertTrue("Invalid date must not close the dialog", dialog.isShowing());
                    assertNotNull(input.getError());
                    View suggestion = findExact(root, "2030-12-31"); assertNotNull(suggestion); suggestion.performClick();
                    assertEquals("2030-12-31", input.getText().toString());
                    dialog.dismiss();
            });
        } finally { activity.finish(); }
    }
    private static EditText findEditable(View node) {
        if (node instanceof EditText) return (EditText) node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) { EditText found = findEditable(((ViewGroup) node).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private static View findExact(View node, String value) {
        if (node instanceof TextView && value.contentEquals(((TextView) node).getText())) return node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) { View found = findExact(((ViewGroup) node).getChildAt(i), value); if (found != null) return found; }
        return null;
    }
}
