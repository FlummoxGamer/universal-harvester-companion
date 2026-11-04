package app.harvester.companion.utils.phoenix;

import android.app.IntentService;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.os.StrictMode;

public final class PhoenixService extends IntentService {

  public PhoenixService() {
    super("PhoenixService");
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    if (intent == null) {
      return;
    }

    Process.killProcess(
        intent.getIntExtra(ProcessPhoenix.KEY_MAIN_PROCESS_PID, -1));

    Intent nextIntent;
    if (Build.VERSION.SDK_INT >= 33) {
      nextIntent = intent.getParcelableExtra(ProcessPhoenix.KEY_RESTART_INTENT, Intent.class);
    } else {
      nextIntent = intent.getParcelableExtra(ProcessPhoenix.KEY_RESTART_INTENT);
    }

    if (Build.VERSION.SDK_INT > 31) {
      StrictMode.setVmPolicy(
          new StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
              .permitUnsafeIntentLaunch()
              .build());
    }

    if (Build.VERSION.SDK_INT >= 26) {
      startForegroundService(nextIntent);
    } else {
      startService(nextIntent);
    }

    Runtime.getRuntime().exit(0);
  }
}
