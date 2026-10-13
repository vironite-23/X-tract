package com.vironite.retrievex.download;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.storage.FileStore;
import com.vironite.retrievex.util.NetUtil;
import com.vironite.retrievex.util.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Download queue: owns task state, enforces the concurrency limit and Wi-Fi-only policy, persists tasks and
 * keeps the foreground service running while work is in progress.
 */
public final class DownloadManager {
    private static final String TAG = "RetrieveX";

    public interface Listener {
        void onTaskChanged(DownloadTask snapshot);

        void onTaskRemoved(String id);

        void onReset();
    }

    private static DownloadManager instance;

    public static synchronized void init(Context c) {
        if (instance == null) {
            instance = new DownloadManager(c.getApplicationContext());
            instance.loadFromDb();
        }
    }

    public static DownloadManager get() {
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, DownloadTask> tasks = new LinkedHashMap<>();
    private final Map<String, DownloadJob> jobs = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ExecutorService dbExec = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean pumpScheduled;

    private final Runnable pumpRunnable = new Runnable() {
        @Override
        public void run() {
            pumpScheduled = false;
            pump();
        }
    };

    private DownloadManager(Context c) {
        ctx = c;
    }

    // ---------------------------------------------------------------- listeners

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void dispatchChanged(final DownloadTask t) {
        final DownloadTask snap = t.copy();
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onTaskChanged(snap);
            }
        });
    }

    private void dispatchRemoved(final String id) {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onTaskRemoved(id);
            }
        });
    }

    private void dispatchReset() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onReset();
            }
        });
    }

    // ---------------------------------------------------------------- persistence

    private void loadFromDb() {
        dbExec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<DownloadTask> saved = Repo.get().listTasks();
                    Set<String> keep = new HashSet<>();
                    synchronized (DownloadManager.this) {
                        for (DownloadTask t : saved) {
                            if (t.isActive() || t.status == DownloadTask.WAITING) {
                                t.status = DownloadTask.PAUSED;
                                t.errorMessage = "Interrupted. Resume to continue.";
                                Repo.get().saveTask(t);
                            }
                            if (!tasks.containsKey(t.id)) tasks.put(t.id, t);
                            if (t.tempPath != null) keep.add(t.tempPath);
                            if (t.tempPath2 != null) keep.add(t.tempPath2);
                        }
                    }
                    if (Prefs.getBool(Prefs.AUTO_CLEANUP, true)) {
                        FileStore.clearTemp(ctx, keepResumable(keep));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not restore download tasks", e);
                }
                dispatchReset();
            }
        });
    }

    private Set<String> keepResumable(Set<String> candidates) {
        Set<String> keep = new HashSet<>();
        synchronized (this) {
            for (DownloadTask t : tasks.values()) {
                if (t.status == DownloadTask.PAUSED || t.status == DownloadTask.FAILED) {
                    if (t.tempPath != null) keep.add(t.tempPath);
                    if (t.tempPath2 != null) keep.add(t.tempPath2);
                }
            }
        }
        return keep;
    }

    private void persist(DownloadTask t) {
        final DownloadTask copy = t.copy();
        dbExec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Repo.get().saveTask(copy);
                } catch (Exception e) {
                    Log.w(TAG, "Could not save task", e);
                }
            }
        });
    }

    // ---------------------------------------------------------------- queries

    public synchronized List<DownloadTask> snapshot() {
        List<DownloadTask> out = new ArrayList<>(tasks.size());
        for (DownloadTask t : tasks.values()) out.add(t.copy());
        return out;
    }

    public synchronized DownloadTask find(String id) {
        DownloadTask t = tasks.get(id);
        return t == null ? null : t.copy();
    }

    public synchronized int activeCount() {
        int n = 0;
        for (DownloadTask t : tasks.values()) if (t.isActive()) n++;
        return n;
    }

    public synchronized int waitingCount() {
        int n = 0;
        for (DownloadTask t : tasks.values()) if (t.status == DownloadTask.WAITING) n++;
        return n;
    }

    public synchronized boolean hasRunningWork() {
        return !jobs.isEmpty();
    }

    /** Average progress of active tasks in percent, or -1 when unknown. */
    public synchronized int aggregatePercent() {
        int sum = 0;
        int n = 0;
        for (DownloadTask t : tasks.values()) {
            if (!t.isActive()) continue;
            int p = t.percent();
            if (p >= 0) {
                sum += p;
                n++;
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    // ---------------------------------------------------------------- actions

    public void enqueue(List<DownloadTask> list) {
        synchronized (this) {
            for (DownloadTask t : list) {
                t.status = DownloadTask.WAITING;
                tasks.put(t.id, t);
                persist(t);
                dispatchChanged(t);
            }
        }
        pump();
    }

    public void pause(String id) {
        synchronized (this) {
            DownloadTask t = tasks.get(id);
            if (t == null) return;
            DownloadJob j = jobs.get(id);
            if (j != null) {
                j.requestStop(DownloadJob.PAUSE);
            } else if (t.status == DownloadTask.WAITING) {
                t.status = DownloadTask.PAUSED;
                t.note = null;
                persist(t);
                dispatchChanged(t);
            }
        }
    }

    public void resume(String id) {
        synchronized (this) {
            DownloadTask t = tasks.get(id);
            if (t == null || t.status != DownloadTask.PAUSED) return;
            t.status = DownloadTask.WAITING;
            t.errorMessage = null;
            persist(t);
            dispatchChanged(t);
        }
        pump();
    }

    public void cancel(String id) {
        synchronized (this) {
            DownloadTask t = tasks.get(id);
            if (t == null || t.isTerminal()) return;
            DownloadJob j = jobs.get(id);
            if (j != null) {
                j.requestStop(DownloadJob.CANCEL);
            } else {
                deleteTemps(t);
                t.status = DownloadTask.CANCELED;
                t.bytesDownloaded = 0;
                t.note = null;
                persist(t);
                dispatchChanged(t);
            }
        }
    }

    public void cancelAllActive() {
        List<String> ids = new ArrayList<>();
        synchronized (this) {
            for (DownloadTask t : tasks.values()) if (!t.isTerminal()) ids.add(t.id);
        }
        for (String id : ids) cancel(id);
    }

    public void retry(String id) {
        synchronized (this) {
            DownloadTask t = tasks.get(id);
            if (t == null || (t.status != DownloadTask.FAILED && t.status != DownloadTask.CANCELED)) return;
            String code = t.errorCode == null ? "" : t.errorCode;
            boolean keepPartial = t.status == DownloadTask.FAILED
                    && (code.equals("NETWORK") || code.equals("TIMEOUT") || code.equals("INTERRUPTED") || code.equals("HTTP"));
            if (!keepPartial) {
                deleteTemps(t);
                t.bytesDownloaded = 0;
            }
            t.status = DownloadTask.WAITING;
            t.errorMessage = null;
            t.errorCode = null;
            persist(t);
            dispatchChanged(t);
        }
        pump();
    }

    /** Removes a finished task from the visible history. The downloaded file is not touched. */
    public void remove(String id) {
        synchronized (this) {
            DownloadTask t = tasks.get(id);
            if (t == null || !t.isTerminal()) return;
            deleteTemps(t);
            tasks.remove(id);
        }
        dbExec.execute(new Runnable() {
            @Override
            public void run() {
                Repo.get().deleteTask(id);
            }
        });
        dispatchRemoved(id);
    }

    public void clearFinished() {
        List<String> ids = new ArrayList<>();
        synchronized (this) {
            for (DownloadTask t : tasks.values()) if (t.isTerminal()) ids.add(t.id);
        }
        for (String id : ids) remove(id);
    }

    /** Clears every terminal task from history; used by Settings. Returns the number removed. */
    public int clearHistory() {
        int n;
        synchronized (this) {
            n = 0;
            for (DownloadTask t : tasks.values()) if (t.isTerminal()) n++;
        }
        clearFinished();
        return n;
    }

    void deleteTemps(DownloadTask t) {
        if (t.tempPath != null) //noinspection ResultOfMethodCallIgnored
            new File(t.tempPath).delete();
        if (t.tempPath2 != null) //noinspection ResultOfMethodCallIgnored
            new File(t.tempPath2).delete();
        t.tempPath = null;
        t.tempPath2 = null;
    }

    // ---------------------------------------------------------------- scheduling

    private void pump() {
        boolean startService = false;
        synchronized (this) {
            int limit = Prefs.maxConcurrent();
            boolean blocked = Prefs.getBool(Prefs.WIFI_ONLY, false) && NetUtil.isMetered(ctx);
            boolean anyWaiting = false;
            for (DownloadTask t : tasks.values()) {
                if (t.status != DownloadTask.WAITING || jobs.containsKey(t.id)) continue;
                anyWaiting = true;
                if (jobs.size() >= limit) continue;
                if (blocked) {
                    if (!"wifi".equals(t.note)) {
                        t.note = "wifi";
                        dispatchChanged(t);
                    }
                    continue;
                }
                t.note = null;
                DownloadJob job = new DownloadJob(this, ctx, t);
                jobs.put(t.id, job);
                t.status = DownloadTask.FETCHING;
                dispatchChanged(t);
                pool.execute(job);
                startService = true;
            }
            if (anyWaiting && blocked && !pumpScheduled) {
                pumpScheduled = true;
                main.postDelayed(pumpRunnable, 5000);
            }
            if (!jobs.isEmpty()) startService = true;
        }
        if (startService && !DownloadService.isRunning()) startForegroundService();
    }

    private void startForegroundService() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(ctx, DownloadService.class);
                    androidx.core.content.ContextCompat.startForegroundService(ctx, i);
                } catch (Exception e) {
                    // Starting a foreground service can be refused when the app is in the background.
                    // Downloads still proceed while the process is alive.
                    Log.w(TAG, "Could not start the download service", e);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    // ---------------------------------------------------------------- callbacks from jobs

    void notifyTask(DownloadTask t, boolean save) {
        if (save) persist(t);
        dispatchChanged(t);
    }

    void jobFinished(String id) {
        synchronized (this) {
            jobs.remove(id);
        }
        pump();
        DownloadTask t;
        synchronized (this) {
            t = tasks.get(id);
        }
        if (t != null) dispatchChanged(t);
    }
}
