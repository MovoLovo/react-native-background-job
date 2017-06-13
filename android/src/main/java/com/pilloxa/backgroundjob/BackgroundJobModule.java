package com.pilloxa.backgroundjob;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.firebase.jobdispatcher.Constraint;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.RetryStrategy;
import com.firebase.jobdispatcher.Trigger;
import java.util.HashMap;
import java.util.Map;

import static com.firebase.jobdispatcher.FirebaseJobDispatcher.CANCEL_RESULT_SUCCESS;
import static com.firebase.jobdispatcher.FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS;

class BackgroundJobModule extends ReactContextBaseJavaModule {
  private static final String LOG_TAG = BackgroundJobModule.class.getSimpleName();

  private static final String NETWORK_TYPE_UNMETERED = "UNMETERED";
  private static final String NETWORK_TYPE_ANY = "ANY";

  private FirebaseJobDispatcher mJobDispatcher;

  @NonNull private Bundle mForegroundJobBundle = Bundle.EMPTY;

  @Override public void initialize() {
    super.initialize();
    Log.d(LOG_TAG, "Initializing BackgroundJob");
    if (mJobDispatcher == null) {
      mJobDispatcher =
          new FirebaseJobDispatcher(new GooglePlayDriver(getReactApplicationContext()));
    }
  }

  BackgroundJobModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @ReactMethod
  public void schedule(String jobKey, int timeout, int period, boolean persist, boolean override,
      int networkType, boolean requiresCharging, boolean requiresDeviceIdle, boolean alwaysRunning,
      boolean allowExecutionInForeground, String title, String icon, String text,
      Callback callback) {
    final Bundle jobBundle = new Bundle();
    jobBundle.putString("jobKey", jobKey);
    jobBundle.putString("notificationTitle", title);
    jobBundle.putString("notificationIcon", icon);
    jobBundle.putString("notificationText", text);
    jobBundle.putLong("timeout", timeout);
    jobBundle.putInt("persist", persist ? 1 : 0);
    jobBundle.putBoolean("override", override);
    jobBundle.putLong("period", period);
    jobBundle.putInt("networkType", networkType);
    jobBundle.putInt("requiresCharging", requiresCharging ? 1 : 0);
    jobBundle.putInt("requiresDeviceIdle", requiresDeviceIdle ? 1 : 0);
    jobBundle.putInt("alwaysRunning", alwaysRunning ? 1 : 0);
    jobBundle.putBoolean("allowExecutionInForeground", allowExecutionInForeground);

    Log.d(LOG_TAG, "Scheduling job with:" + jobBundle.toString());

    final boolean scheduled;
    if (alwaysRunning) {
      scheduled = scheduleForegroundJob(jobBundle);
    } else {
      scheduled =
          scheduleBackgroundJob(jobKey, period, persist, override, networkType, requiresCharging,
              jobBundle);
    }
    callback.invoke(scheduled);
  }

  /**
   * Schedule a new background job that will be triggered via {@link FirebaseJobDispatcher}.
   */
  private boolean scheduleBackgroundJob(String jobKey, int period, boolean persist,
      boolean override, int networkType, boolean requiresCharging, Bundle jobBundle) {
    Job.Builder jobBuilder = mJobDispatcher.newJobBuilder()
        .setService(BackgroundJob.class)
        .setExtras(jobBundle)
        .setTag(jobKey)
        .setTrigger(Trigger.executionWindow(period, period))
        .setLifetime(persist ? Lifetime.FOREVER : Lifetime.UNTIL_NEXT_BOOT)
        .addConstraint(networkType)
        .setRecurring(true)
        .setReplaceCurrent(override)
        .setRetryStrategy(RetryStrategy.DEFAULT_LINEAR);
    if (requiresCharging) {
      jobBuilder.addConstraint(Constraint.DEVICE_CHARGING);
    }
    if (mJobDispatcher.schedule(jobBuilder.build()) == SCHEDULE_RESULT_SUCCESS) {
      Log.d(LOG_TAG, "Successfully scheduled: " + jobKey);
      return true;
    } else {
      Log.e(LOG_TAG, "Failed to schedule: " + jobKey);
      return false;
    }
  }

  /**
   * Will fully schedule (cancel the old and schedule a new one) always running foreground job.
   *
   * @return true if there is a scheduled foreground job
   */
  private boolean scheduleForegroundJob(@NonNull Bundle jobBundle) {
    final Bundle foregroundJobBundle = new Bundle(jobBundle);
    final boolean scheduled =
        ForegroundJobService.schedule(getReactApplicationContext(), foregroundJobBundle);
    if (scheduled) {
      mForegroundJobBundle = foregroundJobBundle;
    }
    return !mForegroundJobBundle.isEmpty();
  }

  private boolean cancelCurrentForegroundJob() {
    mForegroundJobBundle = Bundle.EMPTY;
    return ForegroundJobService.stop(getReactApplicationContext());
  }

  @ReactMethod public void cancel(String jobKey, Callback callback) {
    Log.d(LOG_TAG, "Cancelling job: " + jobKey);
    final boolean canceled;
    if (mForegroundJobBundle.getString("jobKey", "").equals(jobKey)) {
      canceled = cancelCurrentForegroundJob();
    } else {
      canceled = mJobDispatcher.cancel(jobKey) == CANCEL_RESULT_SUCCESS;
    }
    callback.invoke(canceled);
  }

  @ReactMethod public void cancelAll(Callback callback) {
    Log.d(LOG_TAG, "Cancelling all jobs");
    final boolean foregroundCanceled = !ForegroundJobService.isRunning(getReactApplicationContext())
        || cancelCurrentForegroundJob();
    final boolean allBackgroundCanceled = mJobDispatcher.cancelAll() == CANCEL_RESULT_SUCCESS;
    callback.invoke(foregroundCanceled || allBackgroundCanceled);
  }

  @Override public String getName() {
    return "BackgroundJob";
  }

  @Nullable @Override public Map<String, Object> getConstants() {
    Log.d(LOG_TAG, "Getting constants");
    HashMap<String, Object> constants = new HashMap<>();
    constants.put(NETWORK_TYPE_UNMETERED, Constraint.ON_UNMETERED_NETWORK);
    constants.put(NETWORK_TYPE_ANY, Constraint.ON_ANY_NETWORK);
    return constants;
  }
}