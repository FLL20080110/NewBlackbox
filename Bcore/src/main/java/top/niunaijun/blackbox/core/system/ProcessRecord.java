package top.niunaijun.blackbox.core.system;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.IInterface;
import android.os.Process;
import android.os.SystemClock;

import java.util.Arrays;

import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;

public class ProcessRecord extends Binder {
    public final ApplicationInfo info;
    final public String processName;
    public IBActivityThread bActivityThread;
    public IInterface appThread;
    public int uid;
    public int pid;
    public int buid;
    public int bpid;
    public int callingBUid;
    public int userId;

    /**
     * Initialization is deliberately tracked per record so callers never need to hold the global
     * process-manager lock while a stub provider/Binder handshake is in progress.
     */
    public final ConditionVariable initLock = new ConditionVariable();
    public volatile boolean initializing;
    public volatile boolean initSucceeded;
    public volatile long initStartedAt;

    public ProcessRecord(ApplicationInfo info, String processName) {
        this.info = info;
        this.processName = processName;
    }

    public synchronized void beginInitialization() {
        initLock.close();
        initSucceeded = false;
        initializing = true;
        initStartedAt = SystemClock.uptimeMillis();
    }

    /**
     * Finish the current initialization attempt exactly once. A death callback can race with the
     * provider handshake; in that case the first completion wins so a late successful return can
     * never resurrect a record that has already been removed as dead.
     */
    public synchronized void finishInitialization(boolean success) {
        if (!initializing) {
            initLock.open();
            return;
        }
        initSucceeded = success;
        initializing = false;
        initLock.open();
    }

    public int getCallingBUid() {
        return callingBUid;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{processName, pid, buid, bpid, uid, pid, userId});
    }

    public String getProviderAuthority() {
        return ProxyManifest.getProxyAuthorities(bpid);
    }

    public AppConfig getClientConfig() {
        AppConfig config = new AppConfig();
        config.packageName = info.packageName;
        config.processName = processName;
        config.bpid = bpid;
        config.buid = buid;
        config.uid = uid;
        config.callingBUid = callingBUid;
        config.userId = userId;
        config.token = this;
        return config;
    }

    public void kill() {
        if (pid > 0) {
            try {
                Process.killProcess(pid);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public String getPackageName() {
        return info.packageName;
    }
}
