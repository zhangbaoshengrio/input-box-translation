package com.ibt.translate;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * Runs in its own process (":restart"): kills the main app process, then reopens the app so
 * LSPosed hands it a fresh connection. LSPosed only delivers that connection at process start.
 */
public class RestartActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pid = getIntent().getIntExtra("pid", -1);
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        startActivity(new Intent(this, MainActivity.class)
                .putExtra("retried", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
