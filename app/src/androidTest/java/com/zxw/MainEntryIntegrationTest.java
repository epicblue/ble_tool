package com.zxw;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.fitness.ui.ExerciseListActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;

/** 设备冒烟测试：主页启动后体能入口可用；授权/拒绝场景仍需在设备上分别验证。 */
@RunWith(AndroidJUnit4.class)
public class MainEntryIntegrationTest {
    @Test public void mainScreenCanOpenFitness() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = InstrumentationRegistry.getTargetContext();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                ExerciseListActivity.class.getName(), null, false);
        Activity home = null;
        Activity fitness = null;
        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            home = instrumentation.startActivitySync(intent);
            Activity finalHome = home;
            instrumentation.runOnMainSync(() -> finalHome.findViewById(R.id.fitness_btn).performClick());
            fitness = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull("体能入口应在主页直接打开", fitness);
        } finally {
            Activity finalFitness = fitness;
            Activity finalHome = home;
            instrumentation.runOnMainSync(() -> {
                if (finalFitness != null) finalFitness.finish();
                if (finalHome != null) finalHome.finish();
            });
            instrumentation.removeMonitor(monitor);
        }
    }
}
