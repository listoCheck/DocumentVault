package ru.documentvault.android;

import android.content.Context;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

final class ReminderScheduler {
    private ReminderScheduler() { }
    static void schedule(Context context) {
        WorkManager manager = WorkManager.getInstance(context);
        manager.enqueueUniquePeriodicWork("document-expiry-daily", ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(ReminderWorker.class, 24, TimeUnit.HOURS).build());
        manager.enqueueUniqueWork("document-expiry-now", ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(ReminderWorker.class).build());
    }
}
