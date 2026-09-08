package top.niunaijun.blackbox.core.system.location;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.SparseArray;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import black.android.location.BRILocationListener;
import black.android.location.BRILocationListenerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.entity.location.BCell;
import top.niunaijun.blackbox.entity.location.BLocation;
import top.niunaijun.blackbox.entity.location.BLocationConfig;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.utils.CloseUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;


public class BLocationManagerService extends IBLocationManagerService.Stub implements ISystemService {
    public static final String TAG = "BLocationManagerService";

    private static final BLocationManagerService sService = new BLocationManagerService();
    private final SparseArray<HashMap<String, BLocationConfig>> mLocationConfigs = new SparseArray<>();
    private final BLocationConfig mGlobalConfig = new BLocationConfig();
    private final Map<IBinder, LocationRecord> mLocationListeners = new HashMap<>();
    private final Executor mThreadPool = Executors.newCachedThreadPool();

    /**
     * LocationSpoofer deliberately stays outside the BlackBox LSPosed scope.  It publishes its
     * current configuration as a small root-managed JSON file and BlackBox consumes that file as
     * a read-only bridge.  This avoids injecting the LSPosed module into :p0 guest processes.
     */
    private static final String[] LOCATION_SPOOFER_CONFIG_PATHS = new String[]{
            "/data/local/tmp/locationspoofer_config.json",
            "/data/system/locationspoofer_config.json",
            "/data/data/com.suseoaa.locationspoofer/files/locationspoofer_config.json"
    };
    private static final long LOCATION_SPOOFER_POLL_MS = 500L;
    private final Object mLocationSpooferLock = new Object();
    private long mLocationSpooferLastPoll;
    private long mLocationSpooferLastModified = Long.MIN_VALUE;
    private String mLocationSpooferLastPath;
    private boolean mLocationSpooferConfigAvailable;
    private boolean mLocationSpooferActive;
    private BLocation mLocationSpooferLocation;
    private long mLocationSpooferLastErrorLog;

    public static BLocationManagerService get() {
        return sService;
    }

    private BLocationConfig getOrCreateConfig(int userId, String pkg) {
        synchronized (mLocationConfigs) {
            HashMap<String, BLocationConfig> pkgs = mLocationConfigs.get(userId);
            if (pkgs == null) {
                pkgs = new HashMap<>();
                mLocationConfigs.put(userId, pkgs);
            }
            BLocationConfig config = pkgs.get(pkg);
            if (config == null) {
                config = new BLocationConfig();
                config.pattern = BLocationManager.CLOSE_MODE;
                pkgs.put(pkg, config);
            }
            return config;
        }
    }

    public int getPattern(int userId, String pkg) {
        synchronized (mLocationConfigs) {
            BLocationConfig config = getOrCreateConfig(userId, pkg);
            return config.pattern;
        }
    }

    @Override
    public void setPattern(int userId, String pkg, int pattern) {
        synchronized (mLocationConfigs) {
            getOrCreateConfig(userId, pkg).pattern = pattern;
            save();
        }
    }

    @Override
    public void setCell(int userId, String pkg, BCell cell) {
        synchronized (mLocationConfigs) {
            getOrCreateConfig(userId, pkg).cell = cell;
            save();
        }
    }

    @Override
    public void setAllCell(int userId, String pkg, List<BCell> cells) {
        synchronized (mLocationConfigs) {
            getOrCreateConfig(userId, pkg).allCell = cells;
            save();
        }
    }

    @Override
    public void setNeighboringCell(int userId, String pkg, List<BCell> cells) {
        synchronized (mLocationConfigs) {
            getOrCreateConfig(userId, pkg).allCell = cells;
            save();
        }
    }

    @Override
    public List<BCell> getNeighboringCell(int userId, String pkg) {
        synchronized (mLocationConfigs) {
            return getOrCreateConfig(userId, pkg).allCell;
        }
    }

    @Override
    public void setGlobalCell(BCell cell) {
        synchronized (mGlobalConfig) {
            mGlobalConfig.cell = cell;
            save();
        }
    }

    @Override
    public void setGlobalAllCell(List<BCell> cells) {
        synchronized (mGlobalConfig) {
            mGlobalConfig.allCell = cells;
            save();
        }
    }

    @Override
    public void setGlobalNeighboringCell(List<BCell> cells) {
        synchronized (mGlobalConfig) {
            mGlobalConfig.neighboringCellInfo = cells;
            save();
        }
    }

    @Override
    public List<BCell> getGlobalNeighboringCell() {
        synchronized (mGlobalConfig) {
            return mGlobalConfig.neighboringCellInfo;
        }
    }

    @Override
    public BCell getCell(int userId, String pkg) {
        BLocationConfig config = getOrCreateConfig(userId, pkg);
        switch (config.pattern) {
            case BLocationManager.OWN_MODE:
                return config.cell;
            case BLocationManager.GLOBAL_MODE:
                return mGlobalConfig.cell;
            case BLocationManager.CLOSE_MODE:
            default:
                return null;
        }
    }

    @Override
    public List<BCell> getAllCell(int userId, String pkg) {
        BLocationConfig config = getOrCreateConfig(userId, pkg);
        switch (config.pattern) {
            case BLocationManager.OWN_MODE:
                return config.allCell;
            case BLocationManager.GLOBAL_MODE:
                return mGlobalConfig.allCell;
            case BLocationManager.CLOSE_MODE:
            default:
                return null;
        }
    }

    @Override
    public void setLocation(int userId, String pkg, BLocation location) {
        synchronized (mLocationConfigs) {
            getOrCreateConfig(userId, pkg).location = location;
            save();
        }
    }

    private BLocation getLocationSpooferLocationIfAvailable() {
        refreshLocationSpooferBridge();
        synchronized (mLocationSpooferLock) {
            if (!mLocationSpooferConfigAvailable || !mLocationSpooferActive) {
                return null;
            }
            return mLocationSpooferLocation;
        }
    }

    private boolean isLocationSpooferConfigAvailable() {
        refreshLocationSpooferBridge();
        synchronized (mLocationSpooferLock) {
            return mLocationSpooferConfigAvailable;
        }
    }

    private void refreshLocationSpooferBridge() {
        final long now = System.currentTimeMillis();
        synchronized (mLocationSpooferLock) {
            if ((now - mLocationSpooferLastPoll) < LOCATION_SPOOFER_POLL_MS) {
                return;
            }
            mLocationSpooferLastPoll = now;

            File readableConfig = null;
            for (String path : LOCATION_SPOOFER_CONFIG_PATHS) {
                File candidate = new File(path);
                if (candidate.exists() && candidate.isFile() && candidate.canRead()) {
                    readableConfig = candidate;
                    break;
                }
            }

            if (readableConfig == null) {
                mLocationSpooferConfigAvailable = false;
                mLocationSpooferActive = false;
                mLocationSpooferLocation = null;
                mLocationSpooferLastPath = null;
                mLocationSpooferLastModified = Long.MIN_VALUE;
                return;
            }

            final String path = readableConfig.getAbsolutePath();
            final long modified = readableConfig.lastModified();
            if (mLocationSpooferConfigAvailable
                    && path.equals(mLocationSpooferLastPath)
                    && modified == mLocationSpooferLastModified) {
                return;
            }

            FileInputStream inputStream = null;
            try {
                inputStream = new FileInputStream(readableConfig);
                byte[] bytes = FileUtils.toByteArray(inputStream);
                JSONObject config = new JSONObject(new String(bytes, StandardCharsets.UTF_8));

                final boolean active = config.optBoolean("active", false);
                BLocation location = null;
                if (active) {
                    // Android Location is WGS-84.  LocationSpoofer already exports derived WGS-84
                    // coordinates; fall back to lat/lng for compatibility with older configs.
                    final double latitude = config.has("wgs84_lat")
                            ? config.optDouble("wgs84_lat", Double.NaN)
                            : config.optDouble("lat", Double.NaN);
                    final double longitude = config.has("wgs84_lng")
                            ? config.optDouble("wgs84_lng", Double.NaN)
                            : config.optDouble("lng", Double.NaN);
                    if (Double.isNaN(latitude) || Double.isInfinite(latitude)
                            || Double.isNaN(longitude) || Double.isInfinite(longitude)
                            || latitude < -90.0 || latitude > 90.0
                            || longitude < -180.0 || longitude > 180.0) {
                        throw new IllegalArgumentException("invalid LocationSpoofer coordinates");
                    }
                    location = new BLocation(latitude, longitude);
                }

                mLocationSpooferConfigAvailable = true;
                mLocationSpooferActive = active;
                mLocationSpooferLocation = location;
                mLocationSpooferLastPath = path;
                mLocationSpooferLastModified = modified;
                Slog.d(TAG, "LocationSpoofer bridge: source=" + path
                        + ", active=" + active
                        + (location == null ? "" : ", location=" + location));
            } catch (Throwable e) {
                mLocationSpooferConfigAvailable = false;
                mLocationSpooferActive = false;
                mLocationSpooferLocation = null;
                if ((now - mLocationSpooferLastErrorLog) > 10000L) {
                    mLocationSpooferLastErrorLog = now;
                    Slog.d(TAG, "LocationSpoofer bridge read failed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            } finally {
                CloseUtils.close(inputStream);
            }
        }
    }

    @Override
    public BLocation getLocation(int userId, String pkg) {
        BLocation bridged = getLocationSpooferLocationIfAvailable();
        if (bridged != null) {
            return bridged;
        }
        // If a readable LocationSpoofer config exists but spoofing is disabled, treat that as an
        // authoritative "real location" state instead of falling through to a stale BlackBox
        // global location saved during earlier tests.
        if (isLocationSpooferConfigAvailable()) {
            return null;
        }

        synchronized (mGlobalConfig) {
            if (mGlobalConfig.location != null) {
                return mGlobalConfig.location;
            }
        }

        BLocationConfig config = getOrCreateConfig(userId, pkg);
        switch (config.pattern) {
            case BLocationManager.OWN_MODE:
                return config.location;
            case BLocationManager.GLOBAL_MODE:
                return mGlobalConfig.location;
            case BLocationManager.CLOSE_MODE:
            default:
                return null;
        }
    }

    @Override
    public void setGlobalLocation(BLocation location) {
        synchronized (mGlobalConfig) {
            mGlobalConfig.location = location;
            save();
        }
    }

    @Override
    public BLocation getGlobalLocation() {
        BLocation bridged = getLocationSpooferLocationIfAvailable();
        if (bridged != null) {
            return bridged;
        }
        if (isLocationSpooferConfigAvailable()) {
            return null;
        }
        synchronized (mGlobalConfig) {
            return mGlobalConfig.location;
        }
    }

    @Override
    public void requestLocationUpdates(IBinder listener, String packageName, int userId) throws RemoteException {
        if (listener == null || !listener.pingBinder()) {
            return;
        }
        if (mLocationListeners.containsKey(listener))
            return;
        listener.linkToDeath(new DeathRecipient() {
            @Override
            public void binderDied() {
                listener.unlinkToDeath(this, 0);
                mLocationListeners.remove(listener);
            }
        }, 0);
        LocationRecord record = new LocationRecord(packageName, userId);
        mLocationListeners.put(listener, record);
        addTask(listener);
    }

    @Override
    public void removeUpdates(IBinder listener) throws RemoteException {
        if (listener == null || !listener.pingBinder()) {
            return;
        }
        mLocationListeners.remove(listener);
    }

    private void addTask(IBinder locationListener) {
        mThreadPool.execute(() -> {
            BLocation lastLocation = null;
            long l = System.currentTimeMillis();
            while (locationListener.pingBinder()) {
                IInterface iInterface = BRILocationListenerStub.get().asInterface(locationListener);
                LocationRecord locationRecord = mLocationListeners.get(locationListener);
                if (locationRecord == null)
                    continue;
                BLocation location = getLocation(locationRecord.userId, locationRecord.packageName);
                if (location == null)
                    continue;
                if (location.equals(lastLocation) && (System.currentTimeMillis() - l) < 3000) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                    continue;
                }
                lastLocation = location;
                l = System.currentTimeMillis();
                BlackBoxCore.get().getHandler().post(() -> BRILocationListener.get(iInterface).onLocationChanged(location.convert2SystemLocation()));
            }
        });
    }

    public void save() {
        synchronized (mGlobalConfig) {
            synchronized (mLocationConfigs) {
                Parcel parcel = Parcel.obtain();
                AtomicFile atomicFile = new AtomicFile(BEnvironment.getFakeLocationConf());
                FileOutputStream fileOutputStream = null;
                try {
                    mGlobalConfig.writeToParcel(parcel, 0);

                    parcel.writeInt(mLocationConfigs.size());
                    for (int i = 0; i < mLocationConfigs.size(); i++) {
                        int tmpUserId = mLocationConfigs.keyAt(i);
                        HashMap<String, BLocationConfig> configArrayMap = mLocationConfigs.valueAt(i);
                        parcel.writeInt(tmpUserId);
                        parcel.writeMap(configArrayMap);
                    }
                    parcel.setDataPosition(0);
                    fileOutputStream = atomicFile.startWrite();
                    FileUtils.writeParcelToOutput(parcel, fileOutputStream);
                    atomicFile.finishWrite(fileOutputStream);
                } catch (Throwable e) {
                    e.printStackTrace();
                    atomicFile.failWrite(fileOutputStream);
                } finally {
                    parcel.recycle();
                    CloseUtils.close(fileOutputStream);
                }
            }
        }
    }

    public void loadConfig() {
        Parcel parcel = Parcel.obtain();
        InputStream is = null;
        try {
            File fakeLocationConf = BEnvironment.getFakeLocationConf();
            if (!fakeLocationConf.exists()) {
                return;
            }
            is = new FileInputStream(BEnvironment.getFakeLocationConf());
            byte[] bytes = FileUtils.toByteArray(is);
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);

            synchronized (mGlobalConfig) {
                mGlobalConfig.refresh(parcel);
            }

            synchronized (mLocationConfigs) {
                mLocationConfigs.clear();
                int size = parcel.readInt();
                for (int i = 0; i < size; i++) {
                    int userId = parcel.readInt();
                    HashMap<String, BLocationConfig> configArrayMap = parcel.readHashMap(BLocationConfig.class.getClassLoader());
                    mLocationConfigs.put(userId, configArrayMap);
                    Slog.d(TAG, "load userId: " + userId + ", config: " + configArrayMap);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Slog.d(TAG, "bad config");
            FileUtils.deleteDir(BEnvironment.getFakeLocationConf());
        } finally {
            parcel.recycle();
            CloseUtils.close(is);
        }
    }

    @Override
    public void systemReady() {
        loadConfig();
        refreshLocationSpooferBridge();
        for (IBinder iBinder : mLocationListeners.keySet()) {
            addTask(iBinder);
        }
    }
}
