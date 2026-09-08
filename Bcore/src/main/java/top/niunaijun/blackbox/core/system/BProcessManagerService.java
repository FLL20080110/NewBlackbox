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

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid) {
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null)
            return null;
        ProcessRecord app;
        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);
            if (bProcess == null) {
                bProcess = new HashMap<>();
                mProcessMap.put(buid, bProcess);
            }

            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    boolean alive = false;
                    try {
                        alive = app.bActivityThread != null
                                && app.bActivityThread.asBinder() != null
                                && app.bActivityThread.asBinder().isBinderAlive();
                    } catch (Throwable ignored) {
                    }
                    if (alive) {
                        return app;
                    }

                    // Mature virtual containers replace dead/stale process records instead of
                    // waiting forever on an initialization latch. A stale record here used to be
                    // able to freeze every later launch of the same virtual process.
                    Slog.w(TAG, "Discarding stale process slot: " + processName + " bPid=" + app.bpid);
                    try {
                        app.kill();
                    } catch (Throwable ignored) {
                    }
                    bProcess.remove(processName);
                    mPidsSelfLocked.remove(app);
                    removeProc(app);
                }

                bpid = getUsingBPidL();
                Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
            }
            if (bpid == -1) {
                throw new RuntimeException("No processes available");
            }

            app = new ProcessRecord(info, processName);
            app.uid = Process.myUid();
            app.bpid = bpid;
            app.buid = BPackageManagerService.get().getAppId(packageName);
            app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
            app.userId = userId;

            bProcess.put(processName, app);
            mPidsSelfLocked.add(app);

            if (!initAppProcessL(app)) {
                bProcess.remove(processName);
                mPidsSelfLocked.remove(app);
                removeProc(app);
                if (bProcess.isEmpty()) {
                    mProcessMap.remove(buid);
                }
                app = null;
            } else {
                app.pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(app.bpid));
                if (app.pid <= 0) {
                    Slog.w(TAG, "Guest process initialized without a visible pid: " + processName);
                }
            }
        }
        return app;
    }

    private int getUsingBPidL() {
        ActivityManager manager = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        if (runningAppProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                int i = parseBPid(runningAppProcess.processName);
                if (i >= 0) usingPs.add(i);
            }
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (usingPs.contains(i)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int callingPid = Binder.getCallingPid();
            ProcessRecord app = findProcessByPid(callingPid);
            if (app == null) {
                String stubProcessName = getProcessName(BlackBoxCore.getContext(), callingPid);
                int bpid = parseBPid(stubProcessName);
                startProcessLocked(packageName, processName, userId, bpid, callingPid);
            }
        }
    }

    private int parseBPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else {
            prefix = BlackBoxCore.getHostPkg() + ":p";
        }
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
            if (record.bActivityThread == null) {
                return false;
            }
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
                    try {
                        appThread.unlinkToDeath(this, 0);
                    } catch (Throwable ignored) {
                    }
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
        app.initLock.open();
    }

    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            try {
                record.kill();
            } catch (Throwable ignored) {
            }
            int key = BUserHandle.getUid(record.userId, BPackageManagerService.get().getAppId(record.getPackageName()));
            Map<String, ProcessRecord> process = mProcessMap.get(key);
            if (process != null) {
                ProcessRecord current = process.get(record.processName);
                if (current == record) {
                    process.remove(record.processName);
                    if (process.isEmpty()) {
                        mProcessMap.remove(key);
                    }
                }
            }
            mPidsSelfLocked.remove(record);
            removeProc(record);
            BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
        }
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int appId = BPackageManagerService.get().getAppId(packageName);
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null)
                return null;
            return processRecordMap.get(processName);
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            List<ProcessRecord> tmp = new ArrayList<>(mPidsSelfLocked);
            int appId = BPackageManagerService.get().getAppId(packageName);
            for (ProcessRecord processRecord : tmp) {
                int processAppId = BUserHandle.getAppId(processRecord.buid);
                if (appId == processAppId) {
                    try {
                        processRecord.kill();
                    } catch (Throwable ignored) {
                    }
                    mPidsSelfLocked.remove(processRecord);
                    removeProc(processRecord);
                    int key = BUserHandle.getUid(processRecord.userId, appId);
                    Map<String, ProcessRecord> map = mProcessMap.get(key);
                    if (map != null) {
                        map.remove(processRecord.processName);
                        if (map.isEmpty()) mProcessMap.remove(key);
                    }
                }
            }
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.remove(buid);
            if (process == null)
                return;
            for (ProcessRecord value : new ArrayList<>(process.values())) {
                try {
                    value.kill();
                } catch (Throwable ignored) {
                }
                mPidsSelfLocked.remove(value);
                removeProc(value);
            }
        }
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return new ArrayList<>();
            return new ArrayList<>(process.values());
        }
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        ProcessRecord callingProcess = findProcessByPid(pid);
        if (callingProcess == null) {
            return BPackageManagerService.get().getAppId(packageName);
        }
        return BUserHandle.getAppId(callingProcess.buid);
    }

    public int getUserIdByCallingPid(int callingPid) {
        ProcessRecord callingProcess = findProcessByPid(callingPid);
        if (callingProcess == null) {
            return 0;
        }
        return callingProcess.userId;
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mProcessLock) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private static String getProcessName(Context context, int pid) {
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
        if (running != null) {
            for (ActivityManager.RunningAppProcessInfo info : running) {
                if (info.pid == pid) {
                    processName = info.processName;
                    break;
                }
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
            if (runningAppProcesses != null) {
                for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                    if (runningAppProcess.processName.equals(processName)) {
                        return runningAppProcess.pid;
                    }
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
