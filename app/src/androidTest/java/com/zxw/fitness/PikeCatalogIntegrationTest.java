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
import com.zxw.fitness.ui.ExerciseListActivity;
import com.zxw.fitness.ui.HistoryActivity;
import com.zxw.fitness.ui.ReplayActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** 真机检查合并后的入口、筛选、历史成绩和回放播放列表，不启用相机。 */
@RunWith(AndroidJUnit4.class)
public class PikeCatalogIntegrationTest {
    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static Activity open(Context context, Class<? extends Activity> type) {
        Intent intent = new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    }

    private static RecyclerView.ViewHolder exerciseAt(Activity screen, int index) throws Exception {
        AtomicReference<RecyclerView.ViewHolder> result = new AtomicReference<>();
        onMain(() -> ((RecyclerView) screen.findViewById(R.id.rvExercises)).scrollToPosition(index));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain(() -> result.set(((RecyclerView) screen.findViewById(R.id.rvExercises))
                    .findViewHolderForAdapterPosition(index)));
            if (result.get() != null) return result.get();
            Thread.sleep(100);
        }
        fail("动作未显示：" + index);
        return null;
    }

    private static void waitText(Activity screen, int id, String expected) throws Exception {
        AtomicReference<String> actual = new AtomicReference<>("");
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain(() -> {
                TextView view = screen.findViewById(id);
                actual.set(view == null ? "" : view.getText().toString());
            });
            if (expected.equals(actual.get())) return;
            Thread.sleep(100);
        }
        assertEquals(expected, actual.get());
    }

    private static void chip(Activity screen, String label) {
        onMain(() -> {
            LinearLayout group = screen.findViewById(R.id.chipGroup);
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView view = (TextView) group.getChildAt(i);
                if (label.contentEquals(view.getText())) { view.performClick(); return; }
            }
            fail("缺少筛选项 " + label);
        });
    }

    @Test public void legacyAndNormalShareUiButKeepOriginalScoreAndPikeIsIndependent() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UserProfile original = UserStore.getCurrent(context);
        UserProfile child = UserStore.addUser(context, "折刀页面测试");
        Activity menu = null, history = null, replay = null;
        try {
            long now = System.currentTimeMillis();
            Session legacy = new Session(); legacy.setId(SessionStore.newSessionId());
            legacy.setUserId(child.getId()); legacy.setExerciseType(ExerciseType.BAD_PUSHUP);
            legacy.setStartedAt(now - 2000); legacy.setReps(3); legacy.setBadReps(11);
            SessionStore.save(context, legacy);
            Session normal = new Session(); normal.setId(SessionStore.newSessionId());
            normal.setUserId(child.getId()); normal.setExerciseType(ExerciseType.PUSHUP);
            normal.setStartedAt(now - 1000); normal.setReps(5); SessionStore.save(context, normal);
            Session pike = new Session(); pike.setId(SessionStore.newSessionId());
            pike.setUserId(child.getId()); pike.setExerciseType(ExerciseType.PIKE_PUSHUP);
            pike.setStartedAt(now); pike.setReps(7); SessionStore.save(context, pike);

            menu = open(context, ExerciseListActivity.class);
            final Activity menuScreen = menu;
            onMain(() -> assertEquals(10,
                    ((RecyclerView) menuScreen.findViewById(R.id.rvExercises)).getAdapter().getItemCount()));
            RecyclerView.ViewHolder normalCard = exerciseAt(menu, 3);
            onMain(() -> {
                assertEquals("俯卧撑", ((TextView) normalCard.itemView.findViewById(R.id.tvTitle)).getText().toString());
                assertEquals("最佳 5 次 · 共 2 次训练",
                        ((TextView) normalCard.itemView.findViewById(R.id.tvBest)).getText().toString());
            });
            RecyclerView.ViewHolder pikeCard = exerciseAt(menu, 4);
            onMain(() -> {
                assertEquals("折刀俯卧撑", ((TextView) pikeCard.itemView.findViewById(R.id.tvTitle)).getText().toString());
                assertEquals("最佳 7 次 · 共 1 次训练",
                        ((TextView) pikeCard.itemView.findViewById(R.id.tvBest)).getText().toString());
            });
            onMain(menuScreen::finish); menu = null;
            history = open(context, HistoryActivity.class);
            final Activity historyScreen = history;
            waitText(history, R.id.tvExerciseKinds, "2");
            onMain(() -> {
                LinearLayout chips = historyScreen.findViewById(R.id.chipGroup);
                boolean sawPike = false;
                for (int i = 0; i < chips.getChildCount(); i++) {
                    String text = ((TextView) chips.getChildAt(i)).getText().toString();
                    assertNotEquals("不标准俯卧撑", text);
                    sawPike |= "折刀俯卧撑".equals(text);
                }
                assertTrue(sawPike);
            });
            chip(history, "俯卧撑");
            onMain(() -> assertTrue(((TextView) historyScreen.findViewById(R.id.tvSubtitle))
                    .getText().toString().contains("筛选 2 条")));
            chip(history, "折刀俯卧撑");
            onMain(() -> assertTrue(((TextView) historyScreen.findViewById(R.id.tvSubtitle))
                    .getText().toString().contains("筛选 1 条")));

            Intent intent = new Intent(context, ReplayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(ReplayActivity.EXTRA_SESSION_ID, legacy.getId());
            intent.putExtra(ReplayActivity.EXTRA_USER_ID, child.getId());
            replay = instrumentation.startActivitySync(intent);
            final Activity replayScreen = replay;
            onMain(() -> {
                assertEquals("俯卧撑 · 旧不标准记录", ((TextView) replayScreen.findViewById(R.id.tvTitle)).getText().toString());
                assertTrue(((TextView) replayScreen.findViewById(R.id.tvRecordedSummary))
                        .getText().toString().contains("不标准 11 次"));
                assertEquals(View.VISIBLE, replayScreen.findViewById(R.id.btnPrev).getVisibility());
                replayScreen.findViewById(R.id.btnPrev).performClick();
                assertEquals("俯卧撑", ((TextView) replayScreen.findViewById(R.id.tvTitle)).getText().toString());
            });
        } finally {
            if (replay != null) { final Activity a = replay; onMain(a::finish); }
            if (history != null) { final Activity a = history; onMain(a::finish); }
            if (menu != null) { final Activity a = menu; onMain(a::finish); }
            instrumentation.waitForIdleSync();
            UserStore.delete(context, child);
            if (original != null && UserStore.getById(context, original.getId()) != null) {
                UserStore.setCurrent(context, original);
            }
        }
    }
}
