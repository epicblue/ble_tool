package com.zxw.fitness;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.R;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.ui.HistoryActivity;
import com.zxw.fitness.ui.ReplayActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

/** 设备测试：真实账号隔离、页面统计、筛选、回放入口和删除后的徽章更新。 */
@RunWith(AndroidJUnit4.class)
public class HistoryGrowthIntegrationTest {
    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static Activity openHistory(Context context) {
        return openHistory(context, null);
    }

    private static Activity openHistory(Context context, String userId) {
        Intent intent = new Intent(context, HistoryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (userId != null) intent.putExtra(HistoryActivity.EXTRA_USER_ID, userId);
        return InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    }

    private static void waitText(Activity activity, int id, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AtomicReference<String> actual = new AtomicReference<>("");
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain(() -> {
                TextView view = activity.findViewById(id);
                actual.set(view == null ? "" : view.getText().toString());
            });
            if (expected.equals(actual.get())) return;
            Thread.sleep(100);
        }
        assertEquals(expected, actual.get());
    }

    private static RecyclerView.ViewHolder waitHolder(Activity activity, int position) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AtomicReference<RecyclerView.ViewHolder> holder = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain(() -> {
                RecyclerView list = activity.findViewById(R.id.rvSessions);
                holder.set(list.findViewHolderForAdapterPosition(position));
            });
            if (holder.get() != null) return holder.get();
            Thread.sleep(100);
        }
        fail("列表项目未显示：" + position);
        return null;
    }

    private static void clickChip(Activity activity, String label) {
        onMain(() -> {
            LinearLayout chips = activity.findViewById(R.id.chipGroup);
            for (int i = 0; i < chips.getChildCount(); i++) {
                TextView chip = (TextView) chips.getChildAt(i);
                if (label.contentEquals(chip.getText())) {
                    chip.performClick();
                    return;
                }
            }
            fail("没有找到筛选项：" + label);
        });
    }

    private static void toTop(Activity activity) {
        onMain(() -> {
            RecyclerView list = activity.findViewById(R.id.rvSessions);
            list.scrollToPosition(0);
        });
    }

    @Test public void childHistoryUsesSavedRecordsKeepsReplayAndRecalculatesAfterDelete() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UserProfile original = UserStore.getCurrent(context);
        UserProfile child = UserStore.addUser(context, "成长页临时账号");
        UserProfile other = UserStore.addUser(context, "成长页隔离账号");
        Activity history = null;
        Activity replay = null;
        try {
            UserStore.setCurrent(context, child);
            ExerciseType[] types = {ExerciseType.SQUAT, ExerciseType.RUNNING,
                    ExerciseType.PLANK, ExerciseType.SQUAT, ExerciseType.RUNNING};
            long firstId = -1;
            long now = System.currentTimeMillis();
            for (int i = 0; i < types.length; i++) {
                Calendar date = Calendar.getInstance();
                date.setTimeInMillis(now);
                date.add(Calendar.DAY_OF_YEAR, -i);
                Session session = new Session();
                session.setId(SessionStore.newSessionId());
                session.setUserId(child.getId());
                session.setExerciseType(types[i]);
                session.setStartedAt(date.getTimeInMillis());
                session.setDurationMs(1000);
                session.setReps(7);
                if (i == 0) firstId = session.getId();
                SessionStore.save(context, session);
            }
            history = openHistory(context);
            waitText(history, R.id.tvRecordCount, "5");
            waitText(history, R.id.tvActiveDays, "5");
            waitText(history, R.id.tvExerciseKinds, "3");
            waitText(history, R.id.tvBadgeCount, "3 / 3");
            final Activity badgeScreen = history;
            onMain(() -> ((LinearLayout) badgeScreen.findViewById(R.id.badgeRow))
                    .getChildAt(0).performClick());
            onView(withText("好呀")).inRoot(isDialog()).perform(click());

            clickChip(history, "引体向上");
            waitText(history, R.id.tvEmptyTitle, "这个动作还没有足迹");
            final Activity emptyScreen = history;
            onMain(() -> emptyScreen.findViewById(R.id.btnEmptyAction).performClick());
            clickChip(history, "深蹲");
            final Activity screen = history;
            onMain(() -> assertTrue(((TextView) screen.findViewById(R.id.tvSubtitle))
                    .getText().toString().contains("筛选 2 条")));
            final RecyclerView.ViewHolder first = waitHolder(history, 1);
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    ReplayActivity.class.getName(), null, false);
            onMain(() -> first.itemView.performClick());
            replay = instrumentation.waitForMonitorWithTimeout(monitor, 20000);
            instrumentation.removeMonitor(monitor);
            assertNotNull("记录点击应打开回放", replay);
            assertEquals(firstId, replay.getIntent().getLongExtra(ReplayActivity.EXTRA_SESSION_ID, -1));
            assertEquals(child.getId(), replay.getIntent().getStringExtra(ReplayActivity.EXTRA_USER_ID));
            final Activity replayScreen = replay;
            onMain(replayScreen::finish);
            replay = null;
            instrumentation.waitForIdleSync();

            RecyclerView.ViewHolder afterReplay = waitHolder(history, 1);
            final View delete = afterReplay.itemView.findViewById(R.id.btnDelete);
            onMain(delete::performClick);
            onView(withText("删除")).inRoot(isDialog()).perform(click());
            assertEquals(4, SessionStore.loadAll(context, child.getId()).size());
            toTop(history);
            waitText(history, R.id.tvRecordCount, "4");
            waitText(history, R.id.tvActiveDays, "4");
            waitText(history, R.id.tvBadgeCount, "2 / 3");

            onMain(screen::finish);
            history = null;
            instrumentation.waitForIdleSync();
            UserStore.setCurrent(context, other);
            history = openHistory(context);
            waitText(history, R.id.tvRecordCount, "0");
            waitText(history, R.id.tvActiveDays, "0");
            waitText(history, R.id.tvBadgeCount, "0 / 3");
            assertEquals("切换账号不能更改前一个账号的记录", 4,
                    SessionStore.loadAll(context, child.getId()).size());

            // 训练完成入口固定查看该次训练的账号，而不切换全局当前账号。
            final Activity otherScreen = history;
            onMain(otherScreen::finish);
            history = null;
            instrumentation.waitForIdleSync();
            history = openHistory(context, child.getId());
            waitText(history, R.id.tvRecordCount, "4");
            assertEquals(other.getId(), UserStore.getCurrent(context).getId());
        } finally {
            if (replay != null) {
                final Activity activity = replay;
                onMain(activity::finish);
            }
            if (history != null) {
                final Activity activity = history;
                onMain(activity::finish);
            }
            instrumentation.waitForIdleSync();
            UserStore.delete(context, child);
            UserStore.delete(context, other);
            if (original != null && UserStore.getById(context, original.getId()) != null) {
                UserStore.setCurrent(context, original);
            }
        }
    }
}
