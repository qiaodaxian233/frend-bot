package com.frend.system;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 服务端延迟任务调度器(END_SERVER_TICK 驱动,零线程,全部在服务器主线程执行)。
 * 用途:让 frend 说话带随机延迟,像人一样不秒回。
 */
public final class FrendScheduler {
    private FrendScheduler() {}

    private record Task(long runAtTick, Runnable action) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static long tickCounter = 0;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (TASKS.isEmpty()) return;
            Iterator<Task> it = TASKS.iterator();
            while (it.hasNext()) {
                Task t = it.next();
                if (tickCounter >= t.runAtTick()) {
                    it.remove();
                    try {
                        t.action().run();
                    } catch (Exception e) {
                        com.frend.Frend.LOGGER.error("[frend] 延迟任务执行失败", e);
                    }
                }
            }
        });
    }

    /** delayTicks 个 tick 后在服务器主线程执行 action。 */
    public static void schedule(int delayTicks, Runnable action) {
        TASKS.add(new Task(tickCounter + Math.max(0, delayTicks), action));
    }
}
