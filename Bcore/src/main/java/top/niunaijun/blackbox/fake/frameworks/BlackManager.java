package top.niunaijun.blackbox.fake.frameworks;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.ParameterizedType;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Reflector;

/** Base client facade for BlackBox binder services. */
public abstract class BlackManager<Service extends IInterface> {
    public static final String TAG = "BlackManager";

    private final Object mServiceLock = new Object();
    private final AtomicBoolean mRefreshInFlight = new AtomicBoolean(false);
    private volatile Service mService;
    private volatile long mLastFailureTime;
    private volatile boolean mEverConnected;
    private static final long FAILURE_BACKOFF_MS = 150;

    protected abstract String getServiceName();

    public Service getService() {
        Service cached = mService;
        if (isAlive(cached)) return cached;
        if (cached != null) clearIfSame(cached);

        /*
         * Initial service bootstrap is still allowed to be synchronous because many callers need
         * a service immediately during BlackBox startup. After a service has connected once,
         * however, a dead core process must not make an Activity/UI thread enter
         * BlackBoxCore.getService(), whose provider/process recovery can block on OEM Android.
         * Fail this single call quickly and refresh the binder in a background thread instead.
         */
        if (mEverConnected && Looper.myLooper() == Looper.getMainLooper()) {
            scheduleAsyncRefresh();
            return null;
        }
        return getServiceBlocking();
    }

    private Service getServiceBlocking() {
        Service cached = mService;
        if (isAlive(cached)) return cached;

        synchronized (mServiceLock) {
            cached = mService;
            if (isAlive(cached)) return cached;
            if (cached != null) mService = null;

            long now = android.os.SystemClock.uptimeMillis();
            if (now - mLastFailureTime < FAILURE_BACKOFF_MS) return null;

            try {
                IBinder binder = BlackBoxCore.get().getService(getServiceName());
                if (binder == null || !binder.isBinderAlive()) {
                    markFailure(now, "Service binder unavailable: " + getServiceName(), null);
                    return null;
                }

                String stubClassName = getTClass().getName() + "$Stub";
                Service created = Reflector.on(stubClassName)
                        .method("asInterface", IBinder.class)
                        .call(binder);
                if (!isAlive(created)) {
                    markFailure(now, "Created dead service: " + getServiceName(), null);
                    return null;
                }

                final Service serviceRef = created;
                try {
                    serviceRef.asBinder().linkToDeath(() -> {
                        clearIfSame(serviceRef);
                        scheduleAsyncRefresh();
                        Log.w(TAG, "Service died: " + getServiceName());
                    }, 0);
                } catch (Throwable e) {
                    // A binder can die between isBinderAlive() and linkToDeath(). Never cache it.
                    markFailure(now, "Unable to link service death recipient: " + getServiceName(), e);
                    return null;
                }

                mService = created;
                mEverConnected = true;
                mLastFailureTime = 0;
                return created;
            } catch (Throwable e) {
                markFailure(now, "Error creating service " + getServiceName(), e);
                return null;
            }
        }
    }

    private void scheduleAsyncRefresh() {
        if (!mRefreshInFlight.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                Service service = getServiceBlocking();
                if (service == null) {
                    Log.w(TAG, "Async service refresh did not reconnect: " + getServiceName());
                } else {
                    Log.d(TAG, "Async service refresh connected: " + getServiceName());
                }
            } catch (Throwable e) {
                Log.w(TAG, "Async service refresh failed: " + getServiceName(), e);
            } finally {
                mRefreshInFlight.set(false);
            }
        }, "BlackBox-ServiceRefresh-" + getServiceName());
        worker.setDaemon(true);
        worker.start();
    }

    private boolean isAlive(Service service) {
        if (service == null) return false;
        try {
            IBinder binder = service.asBinder();
            return binder != null && binder.isBinderAlive() && binder.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void clearIfSame(Service service) {
        synchronized (mServiceLock) {
            if (mService == service) mService = null;
        }
    }

    private void markFailure(long now, String message, Throwable error) {
        mService = null;
        mLastFailureTime = now;
        if (error == null) Log.w(TAG, message);
        else Log.w(TAG, message, error);
    }

    public void clearServiceCache() {
        synchronized (mServiceLock) {
            mService = null;
            mLastFailureTime = 0;
        }
        if (mEverConnected) scheduleAsyncRefresh();
        Log.d(TAG, "Cleared service cache for " + getServiceName());
    }

    public boolean isServiceHealthy() {
        return isAlive(mService);
    }

    @SuppressWarnings("unchecked")
    private Class<Service> getTClass() {
        return (Class<Service>) ((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0];
    }
}
