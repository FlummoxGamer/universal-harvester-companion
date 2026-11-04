package app.harvester.companion.utils.phoenix;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProcessPhoenix {
  static final String KEY_RESTART_INTENT = "phoenix_restart_intent";
  static final String KEY_RESTART_INTENTS = "phoenix_restart_intents";
  static final String KEY_MAIN_PROCESS_PID = "phoenix_main_process_pid";

  public static void triggerRebirth(Context context) {
    triggerRebirth(context, getRestartIntent(context));
  }

  public static void triggerRebirth(Context context, Class<? extends Activity> targetClass) {
    Intent nextIntent = new Intent(context, targetClass);
    triggerRebirth(context, nextIntent);
  }

  public static void triggerRebirth(Context context, Intent... nextIntents) {
    if (nextIntents.length < 1) {
      throw new IllegalArgumentException("intents cannot be empty");
    }
    nextIntents[0].addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);

    Intent intent = new Intent(context, PhoenixActivity.class);
    intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
    intent.putParcelableArrayListExtra(
        KEY_RESTART_INTENTS, new ArrayList<>(Arrays.asList(nextIntents)));
    intent.putExtra(KEY_MAIN_PROCESS_PID, Process.myPid());
    context.startActivity(intent);
  }

  public static void triggerServiceRebirth(Context context, Class<? extends Service> targetClass) {
    Intent nextIntent = new Intent(context, targetClass);
    triggerServiceRebirth(context, nextIntent);
  }

  public static void triggerServiceRebirth(Context context, Intent nextIntent) {
    Intent intent = new Intent(context, PhoenixService.class);
    intent.putExtra(KEY_RESTART_INTENT, nextIntent);
    intent.putExtra(KEY_MAIN_PROCESS_PID, Process.myPid());
    context.startService(intent);
  }

  private static Intent getRestartIntent(Context context) {
    String packageName = context.getPackageName();

    Intent defaultIntent = null;
    PackageManager packageManager = context.getPackageManager();
    if (Build.VERSION.SDK_INT >= 21 && packageManager.hasSystemFeature(FEATURE_LEANBACK)) {
      defaultIntent = packageManager.getLeanbackLaunchIntentForPackage(packageName);
    }
    if (defaultIntent == null) {
      defaultIntent = packageManager.getLaunchIntentForPackage(packageName);
    }
    if (defaultIntent != null) {
      return defaultIntent;
    }

    throw new IllegalStateException(
        "Unable to determine default activity for "
            + packageName
            + ". Does an activity specify the DEFAULT category in its intent filter?");
  }

  public static boolean isPhoenixProcess(Context context) {
    int currentPid = Process.myPid();
    ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    List<ActivityManager.RunningAppProcessInfo> runningProcesses = manager.getRunningAppProcesses();
    if (runningProcesses != null) {
      for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
        if (processInfo.pid == currentPid && processInfo.processName.endsWith(":phoenix")) {
          return true;
        }
      }
    }
    return false;
  }
}
