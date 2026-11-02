package ru.documentvault.android;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.time.LocalDate;

public final class ReminderWorker extends Worker {
    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }
    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        try {
            if (new ReminderStore(context).dueCount(LocalDate.now()) == 0) return Result.success();
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success();
            String today = LocalDate.now().toString();
            android.content.SharedPreferences state = context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE);
            if (today.equals(state.getString("last_notification", ""))) return Result.success();
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel("document_expiry", "Сроки документов", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_SECRET); manager.createNotificationChannel(channel);
            PendingIntent intent = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(1001, new NotificationCompat.Builder(context, "document_expiry")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("DocumentVault")
                .setContentText("Проверьте сроки документов")
                .setVisibility(NotificationCompat.VISIBILITY_SECRET).setAutoCancel(true).setContentIntent(intent).build());
            state.edit().putString("last_notification", today).apply();
            return Result.success();
        } catch (Exception e) { return Result.retry(); }
    }
}
