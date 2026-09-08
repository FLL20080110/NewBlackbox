package top.niunaijun.blackbox.core.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.notification.BNotificationManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ApplicationThreadCompat;
import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.utils.provider.ProviderCall;

public class BProcessManagerService implements ISystemService {
    public static final String TAG = "BProcessManager";
    private static final long INIT_WAIT_TIMEOUT_MS = 3000L;

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid) {
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null) return null;

        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        ProcessRecord app = null;
        ProcessRecord pending = null;

        synchronized (mProcessLock) {
            Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);
            if (bProcess == null) {
                bProcess = new HashMap<>();
                mProcessMap.put(buid, bProcess);
            }

            if (bpid == -1) {
                ProcessRecord existing = bProcess.get(processName);
                if (isRecordAlive(existing)) return existing;

                if (existing != null && existing.initializing) {
                    pending = existing;
                } else {
                    if (existing != null) {
                        Slog.w(TAG, "Discarding stale process slot: " + processName + " bPid=" + existing.bpid);
                        removeRecordLocked(existing, true);
                    }
                    bpid = getUsingBPidL();
                    Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
                }
            }

            if (pending == null) {
                if (bpid == -1) {
                    Slog.e(TAG, "No virtual process slot available for " + packageName + "/" + processName);
                    if (bProcess.isEmpty()) mProcessMap.remove(buid);
                    return null;
                }

                app = new ProcessRecord(info, processName);
                app.uid = Process.myUid();
                app.bpid = bpid;
                app.buid = BPackageManagerService.get().getAppId(packageName);
                app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
                app.userId = userId;
                app.beginInitialization();

                bProcess.put(processName, app);
                mPidsSelfLocked.add(app);
            }
        }

        // Never block the global process map while another caller performs the provider/Binder
        // handshake. Wait only on that specific process record and always with a hard timeout.
        if (pending != null) {
            boolean signalled = pending.initLock.block(INIT_WAIT_TIMEOUT_MS);
            if (!signalled) {
                Slog.w(TAG, "Timed out waiting for guest process initialization: " + processName);
                return null;
            }
            return pending.initSucceeded && isRecordAlive(pending) ? pending : null;
        }

        boolean initialized = false;
        try {
            initialized = initAppProcessL(app);
            return finalizeProcessInitialization(app, buid, processName, initialized);
        } finally {
            app.finishInitialization(initialized);
        }
    }

    private ProcessRecord finalizeProcessInitialization(ProcessRecord app, int buid, String processName, boolean initialized) {
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> processMap = mProcessMap.get(buid);
            boolean stillCurrent = processMap != null && processMap.get(processName) == app;
            if (!initialized || !stillCurrent) {
                if (stillCurrent) removeRecordLocked(app, true);
                return null;
            }

            app.pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(app.bpid));
            if (app.pid <= 0) {
                Slog.w(TAG, "Guest process initialized without a visible pid: " + processName);
            }
            return app;
        }
    }

    /**
     * Select a free stub from live container records rather than trusting ActivityManager alone.
     * OEM Android versions may keep a dead :pN process visible briefly; treating that stale OS
     * entry as permanently occupied eventually exhausts all virtual slots.
     */
    private int getUsingBPidL() {
        Set<Integer> using = new HashSet<>();
        List<ProcessRecord> snapshot = new ArrayList<>(mPidsSelfLocked);
        for (ProcessRecord record : snapshot) {
            if (record.initializing) {
                if (record.bpid >= 0) using.add(record.bpid);
            } else if (isRecordAlive(record)) {
                if (record.bpid >= 0) using.add(record.bpid);
            } else {
                Slog.w(TAG, "Reclaiming dead virtual slot bPid=" + record.bpid + " process=" + record.processName);
                removeRecordLocked(record, true);
            }
        }

        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (using.contains(i)) continue;

            int stalePid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(i));
            if (stalePid > 0) {
                boolean tracked = false;
                for (ProcessRecord record : mPidsSelfLocked) {
                    if (record.bpid == i && (record.initializing || (record.pid == stalePid && isRecordAlive(record)))) {
                        tracked = true;
                        break;
                    }
                }
                if (!tracked) {
                    Slog.w(TAG, "Killing untracked stale stub process " + stalePid + " for bPid=" + i);
                    try {
                        Process.killProcess(stalePid);
                    } catch (Throwable e) {
                        Slog.w(TAG, "Unable to kill stale stub " + stalePid + ": " + e.getMessage());
                        continue;
                    }
                }
            }
            return i;
        }
        return -1;
    }

    private boolean isRecordAlive(ProcessRecord record) {
        if (record == null || record.initializing) return false;
        try {
            return record.bActivityThread != null
                    && record.bActivityThread.asBinder() != null
                    && record.bActivityThread.asBinder().isBinderAlive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void removeRecordLocked(ProcessRecord record, boolean kill) {
        if (record == null) return;
        if (record.initializing) record.finishInitialization(false);
        if (kill) {
            try { record.kill(); } catch (Throwable ignored) {}
        }
        mPidsSelfLocked.remove(record);
        int appId = BPackageManagerService.get().getAppId(record.getPackageName());
        int key = BUserHandle.getUid(record.userId, appId);
        Map<String, ProcessRecord> process = mProcessMap.get(key);
        if (process != null && process.get(record.processName) == record) {
            process.remove(record.processName);
            if (process.isEmpty()) mProcessMap.remove(key);
        }
        removeProc(record);
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        int callingPid = Binder.getCallingPid();
        ProcessRecord app = findProcessByPid(callingPid);
        if (app != null) return;

        String stubProcessName;
        try {
            stubProcessName = getProcessName(BlackBoxCore.getContext(), callingPid);
        } catch (Throwable e) {
            Slog.w(TAG, "Unable to resolve caller process during restart: " + e.getMessage());
            return;
        }
        int bpid = parseBPid(stubProcessName);
        startProcessLocked(packageName, processName, userId, bpid, callingPid);
    }

    private int parseBPid(String stubProcessName) {
        if (stubProcessName == null) return -1;
        String prefix = BlackBoxCore.getHostPkg() + ":p";
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private boolean initAppProcessL(ProcessRecord record) {
        Log.d(TAG, "initProcess: " + record.processName);
        try {
            AppConfig appConfig = record.getClientConfig();
            Bundle bundle = new Bundle();
            bundle.putParcelable(AppConfig.KEY, appConfig);
            Bundle init = ProviderCall.callSafely(record.getProviderAuthority(), "_Black_|_init_process_", null, bundle);
            if (init == null) {
                Slog.w(TAG, "init process provider returned null: " + record.processName);
                return false;
            }
            IBinder appThread = BundleCompat.getBinder(init, "_Black_|_client_");
            if (appThread == null || !appThread.isBinderAlive()) {
                Slog.w(TAG, "init process returned dead client: " + record.processName);
                return false;
            }
            attachClientL(record, appThread);
            if (record.bActivityThread == null) return false;
            createProc(record);
            return true;
        } catch (Throwable e) {
            Slog.e(TAG, "Unable to initialize guest process " + record.processName + ": " + e.getMessage());
            return false;
        }
    }

    private void attachClientL(final ProcessRecord app, final IBinder appThread) {
        IBActivityThread activityThread = IBActivityThread.Stub.asInterface(appThread);
        if (activityThread == null) {
            app.kill();
            return;
        }
        try {
            appThread.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.d(TAG, "App Died: " + app.processName);
                    try { appThread.unlinkToDeath(this, 0); } catch (Throwable ignored) {}
                    onProcessDie(app);
                }
            }, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to link guest death recipient: " + e.getMessage());
        }
        app.bActivityThread = activityThread;
        try {
            app.appThread = ApplicationThreadCompat.asInterface(activityThread.getActivityThread());
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to obtain application thread: " + e.getMessage());
        }
    }

    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            removeRecordLocked(record, true);
            try {
                BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
            } catch (Throwable ignored) {}
        }
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int appId = BPackageManagerService.get().getAppId(packageName);
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null) return null;
            ProcessRecord record = processRecordMap.get(processName);
            if (record != null && !record.initializing && !isRecordAlive(record)) {
                removeRecordLocked(record, true);
                return null;
            }
            return record != null && !record.initializing ? record : null;
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            List<ProcessRecord> snapshot = new ArrayList<>(mPidsSelfLocked);
            int appId = BPackageManagerService.get().getAppId(packageName);
            for (ProcessRecord record : snapshot) {
                if (appId == BUserHandle.getAppId(record.buid)) removeRecordLocked(record, true);
            }
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null) return;
            for (ProcessRecord value : new ArrayList<>(process.values())) removeRecordLocked(value, true);
        }
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null) return new ArrayList<>();
            List<ProcessRecord> result = new ArrayList<>();
            for (ProcessRecord record : new ArrayList<>(process.values())) {
                if (record.initializing) continue;
                if (isRecordAlive(record)) result.add(record);
                else removeRecordLocked(record, true);
            }
            return result;
        }
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        ProcessRecord callingProcess = findProcessByPid(pid);
        if (callingProcess == null) return BPackageManagerService.get().getAppId(packageName);
        return BUserHandle.getAppId(callingProcess.buid);
    }

    public int getUserIdByCallingPid(int callingPid) {
        ProcessRecord callingProcess = findProcessByPid(callingPid);
        return callingProcess == null ? 0 : callingProcess.userId;
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mProcessLock) {
            for (ProcessRecord record : new ArrayList<>(mPidsSelfLocked)) {
                if (record.initializing) continue;
                if (!isRecordAlive(record)) {
                    removeRecordLocked(record, true);
                    continue;
                }
                if (record.pid == pid) return record;
            }
            return null;
        }
    }

    private static String getProcessName(Context context, int pid) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
        if (running != null) {
            for (ActivityManager.RunningAppProcessInfo info : running) {
                if (info.pid == pid) return info.processName;
            }
        }
        throw new RuntimeException("processName = null");
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
            if (running != null) {
                for (ActivityManager.RunningAppProcessInfo info : running) {
                    if (processName.equals(info.processName)) return info.pid;
                }
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Unable to resolve pid for " + processName + ": " + e.getMessage());
        }
        return -1;
    }

    private static void createProc(ProcessRecord record) {
        File cmdline = new File(BEnvironment.getProcDir(record.bpid), "cmdline");
        try {
            FileUtils.writeToFile(record.processName.getBytes(), cmdline);
        } catch (IOException ignored) {
        }
    }

    private static void removeProc(ProcessRecord record) {
        if (record == null) return;
        try {
            FileUtils.deleteDir(BEnvironment.getProcDir(record.bpid));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void systemReady() {
        FileUtils.deleteDir(BEnvironment.getProcDir());
    }
}
