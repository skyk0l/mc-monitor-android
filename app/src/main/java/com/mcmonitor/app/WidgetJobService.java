package com.mcmonitor.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Выполняет сетевые обновления виджетов в задаче, управляемой системой. */
public final class WidgetJobService extends JobService {

    private static final String TAG = "MCMonitorWidgetJob";
    private static final int PERIODIC_JOB_ID = 7301;
    private static final int REFRESH_JOB_ID = 7302;
    private static final long REFRESH_INTERVAL = 30 * 60 * 1000L;
    private static final Object REFRESH_LOCK = new Object();

    private final ConcurrentHashMap<Integer, JobRun> runningJobs = new ConcurrentHashMap<>();

    public static void schedule(Context context) {
        try {
            JobScheduler scheduler = scheduler(context);
            if (scheduler == null) {
                return;
            }
            if (!WidgetUpdater.hasWidgets(context)) {
                scheduler.cancel(PERIODIC_JOB_ID);
                return;
            }
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) != null) {
                return;
            }

            JobInfo job = new JobInfo.Builder(
                    PERIODIC_JOB_ID, new ComponentName(context, WidgetJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(REFRESH_INTERVAL)
                    .build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Не удалось запланировать периодическое обновление виджетов");
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Ошибка настройки фонового обновления виджетов", exception);
        }
    }

    public static void requestRefresh(Context context) {
        try {
            if (!WidgetUpdater.hasWidgets(context)) {
                return;
            }
            JobScheduler scheduler = scheduler(context);
            if (scheduler == null || scheduler.getPendingJob(REFRESH_JOB_ID) != null) {
                return;
            }
            JobInfo job = new JobInfo.Builder(
                    REFRESH_JOB_ID, new ComponentName(context, WidgetJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(0)
                    .build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Не удалось запланировать обновление виджетов");
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Ошибка запуска обновления виджетов", exception);
        }
    }

    private static JobScheduler scheduler(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            Log.e(TAG, "JobScheduler недоступен");
        }
        return scheduler;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        JobRun run = new JobRun();
        runningJobs.put(params.getJobId(), run);
        run.thread = new Thread(() -> {
            try {
                synchronized (REFRESH_LOCK) {
                    if (!run.stopped.get()) {
                        WidgetUpdater.refreshAllBlocking(getApplicationContext());
                    }
                }
            } catch (RuntimeException exception) {
                Log.e(TAG, "Ошибка фонового обновления виджетов", exception);
            } finally {
                runningJobs.remove(params.getJobId(), run);
                if (!run.stopped.get()) {
                    jobFinished(params, false);
                }
            }
        }, "mcmonitor-widget-job");
        run.thread.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        JobRun run = runningJobs.remove(params.getJobId());
        if (run != null) {
            run.stopped.set(true);
            run.thread.interrupt();
        }
        return true;
    }

    private static final class JobRun {
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile Thread thread;
    }
}
