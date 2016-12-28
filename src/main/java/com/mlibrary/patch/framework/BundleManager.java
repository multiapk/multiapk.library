package com.mlibrary.patch.framework;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import com.mlibrary.patch.MDynamicLib;
import com.mlibrary.patch.hack.AndroidHack;
import com.mlibrary.patch.hack.SysHacks;
import com.mlibrary.patch.hotpatch.HotPatchManager;
import com.mlibrary.patch.runtime.InstrumentationHook;
import com.mlibrary.patch.runtime.ResourcesHook;
import com.mlibrary.patch.runtime.RuntimeArgs;
import com.mlibrary.patch.util.FileUtil;
import com.mlibrary.patch.util.LogUtil;
import com.mlibrary.patch.util.PreferencesUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class BundleManager {
    private static final String TAG = MDynamicLib.TAG + ":BundleManager";
    private static final String BUNDLE_LIB_PATH = "assets/baseres/";
    private static final String BUNDLE_SUFFIX = ".so";

    private final Map<String, Bundle> bundles = new ConcurrentHashMap<>();
    private String storageLocation;
    private long nextBundleID = 1;

    private static BundleManager instance = null;

    private BundleManager() {
    }

    public static BundleManager getInstance() {
        if (instance == null) {
            synchronized (BundleManager.class) {
                if (instance == null)
                    instance = new BundleManager();
            }
        }
        return instance;
    }

    public void init(Application application, boolean isOpenLog) {
        long startTime = System.currentTimeMillis();
        LogUtil.setDebugAble(isOpenLog);
        LogUtil.w(TAG, "======================================================================");
        LogUtil.w(TAG, "******** mdynamiclib init start **************************************");
        LogUtil.w(TAG, "======================================================================");
        try {
            SysHacks.defineAndVerify();
            RuntimeArgs.androidApplication = application;
            RuntimeArgs.delegateResources = application.getResources();
            AndroidHack.injectInstrumentationHook(new InstrumentationHook(AndroidHack.getInstrumentation(), application.getBaseContext()));

            //init storageLocation start
            String baseDir = null;
            //String baseDir = RuntimeArgs.androidApplication.getFilesDir().getAbsolutePath();
            //为了方便调试，暂时优先放到SDCard
            File externalFile = RuntimeArgs.androidApplication.getExternalFilesDir(null);
            if (externalFile != null)
                baseDir = externalFile.getAbsolutePath();
            if (baseDir == null)
                baseDir = RuntimeArgs.androidApplication.getFilesDir().getAbsolutePath();
            storageLocation = baseDir + File.separatorChar + "storage" + File.separatorChar;
            LogUtil.w(TAG, "bundle location:" + storageLocation);
            //init storageLocation end

            checkStatus(application);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            LogUtil.w(TAG, "======================================================================");
            LogUtil.w(TAG, "******** mdynamiclib init end [耗时: " + (System.currentTimeMillis() - startTime) + "ms]");
            LogUtil.w(TAG, "======================================================================");
        }
    }

    private void checkStatus(final Application application) {
        LogUtil.e(TAG, "checkStatus: start :check is need reCopyInstall bundles");
        LogUtil.e(TAG, ">>>>-------------------------------------------------------------->>>>");
        final long startTime = System.currentTimeMillis();
        boolean isLocalBundlesValid = isLocalBundlesValid();
        if (!TextUtils.equals(getCurrentBundleKey(application), getLastBundleKey(application)) || !isLocalBundlesValid) {
            LogUtil.d(TAG, "checkStatus: currentBundleKey != lastBundleKey , clean local and reCopyInstall bundles, isLocalBundlesValid==" + isLocalBundlesValid);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    LogUtil.w(TAG, "checkStatus: start new thread to reCopyInstall bundles");
                    HotPatchManager.getInstance().clean();
                    cleanLocal();
                    copyBundles(application);
                    installBundleDexs();
                    LogUtil.e(TAG, "checkStatus: end : 耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                    LogUtil.e(TAG, "<<<<--------------------------------------------------------------<<<<");
                }
            }).start();
        } else {
            LogUtil.d(TAG, "checkStatus: currentBundleKey == lastBundleKey , restore from local hotfix and bundles, isLocalBundlesValid==" + isLocalBundlesValid);
            restoreFromProfile();
            HotPatchManager.getInstance().installHotFixDexs();
            installBundleDexs();
            LogUtil.e(TAG, "checkStatus: end : 耗时: " + (System.currentTimeMillis() - startTime) + "ms");
            LogUtil.e(TAG, "<<<<--------------------------------------------------------------<<<<");
        }
    }

    private boolean isLocalBundlesValid() {
        //校验local所有数据正确性，如果不正确 cleanLocal，重新 copyToLocal
        //验证meta是否存在
        //验证bundles md5，最好在md5正确的bundle.zip里重新释放bundle.dex,确保万无一失，防止被恶意修改
        return true;
    }

    public void cleanLocal() {
        LogUtil.w(TAG, "cleanLocal:" + storageLocation);
        File file = new File(storageLocation);
        if (file.exists())
            FileUtil.deleteDirectory(file);
        file.mkdirs();
        saveToProfile();
    }

    public void installBundleDexs() {
        LogUtil.w(TAG, "installBundleDexs：start");
        long startTime = System.currentTimeMillis();
        for (Bundle bundle : getBundles()) {
            try {
                bundle.installBundleDexs();
            } catch (Exception e) {
                LogUtil.e(TAG, "installBundleDex exception", e);
            }
        }
        try {
            ResourcesHook.newResourcesHook(RuntimeArgs.androidApplication, RuntimeArgs.delegateResources);
        } catch (Exception e) {
            LogUtil.e(TAG, "DelegateResources.newResourcesHook exception", e);
        }
        LogUtil.w(TAG, "installBundleDexs：end 总耗时: " + String.valueOf(System.currentTimeMillis() - startTime) + "ms");
    }

    public List<String> getBundleList(ZipFile zipFile, String prefix, String suffix) {
        List<String> arrayList = new ArrayList<>();
        Enumeration entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            String name = ((ZipEntry) entries.nextElement()).getName();
            if (!TextUtils.isEmpty(name) && name.startsWith(prefix) && name.endsWith(suffix))
                arrayList.add(name);
        }
        return arrayList;
    }

    public void copyBundles(Application application) {
        LogUtil.w(TAG, "copyBundles:start:allBundles:\n" + getInstance().getBundles().toString());
        long startTime = System.currentTimeMillis();
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(application.getApplicationInfo().sourceDir);
            List<String> bundleList = getBundleList(zipFile, BUNDLE_LIB_PATH, BUNDLE_SUFFIX);
            if (bundleList.size() > 0) {
                for (String bundleItem : bundleList) {
                    String packageNameFromEntryName = bundleItem.substring(bundleItem.indexOf(BUNDLE_LIB_PATH) + BUNDLE_LIB_PATH.length(), bundleItem.indexOf(".so")).replace("_", ".");
                    if (!isLocalBundleExists(packageNameFromEntryName))
                        copyToLocal(packageNameFromEntryName, zipFile.getInputStream(zipFile.getEntry(bundleItem)));
                }
                saveBundleKey(application, getCurrentBundleKey(application));
            } else {
                LogUtil.w(TAG, "find no bundles at " + BUNDLE_LIB_PATH);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (zipFile != null)
                    zipFile.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        LogUtil.w(TAG, "copyBundles:end:allBundles:耗时: " + (System.currentTimeMillis() - startTime) + "ms \n" + getInstance().getBundles().toString());
    }

    private boolean isLocalBundleExists(String packageName) {
        boolean isLocalBundleExists = getBundle(packageName) != null;
        LogUtil.d(TAG, packageName + " isLocalBundleExists == " + isLocalBundleExists);
        return isLocalBundleExists;
    }

    public Bundle copyToLocal(String location, InputStream inputStream) {
        LogUtil.d(TAG, "copyToLocal start: " + location);
        long startTime = System.currentTimeMillis();
        Bundle bundle = null;
        try {
            bundle = getBundle(location);
            if (bundle != null)
                return bundle;
            long bundleID = nextBundleID;
            nextBundleID = 1 + bundleID;
            bundle = new Bundle(new File(storageLocation, String.valueOf(bundleID)), location, bundleID, inputStream);
            bundles.put(bundle.getLocation(), bundle);
            saveToMetadata();
        } catch (Exception e) {
            LogUtil.e(TAG, "copyToLocal failure: " + location, e);
        }
        LogUtil.d(TAG, "copyToLocal end: 耗时: " + (System.currentTimeMillis() - startTime) + "ms ");
        return bundle;
    }

    public void updateBundle(String location, InputStream inputStream) throws Exception {
        Bundle bundle = getBundle(location);
        if (bundle != null) {
            bundle.update(inputStream);
            return;
        }
        throw new IllegalStateException("Could not update bundle " + location + ", because could not find it");
    }

    public void uninstallBundle(String location) throws Exception {
        Bundle bundle = getBundle(location);
        if (bundle != null)
            bundle.getArchive().clean();
    }

    public List<Bundle> getBundles() {
        List<Bundle> arrayList = new ArrayList<>(bundles.size());
        synchronized (bundles) {
            arrayList.addAll(bundles.values());
        }
        return arrayList;
    }

    public Bundle getBundle(String str) {
        return bundles.get(str);
    }

    public Bundle getBundle(long id) {
        synchronized (bundles) {
            for (Bundle bundle : bundles.values())
                if (bundle.getBundleId() == id)
                    return bundle;
            return null;
        }
    }

    private void saveToProfile() {
        LogUtil.d(TAG, "saveToProfile start");
        Bundle[] bundleArray = getBundles().toArray(new Bundle[bundles.size()]);
        for (Bundle bundle : bundleArray)
            bundle.updateMetadata();
        saveToMetadata();
        LogUtil.d(TAG, "saveToProfile end");
    }

    private void saveToMetadata() {
        LogUtil.d(TAG, "saveToMetadata:start:" + storageLocation + "meta" + ", save nextBundleID==" + nextBundleID);
        try {
            DataOutputStream dataOutputStream = new DataOutputStream(new FileOutputStream(new File(storageLocation, "meta")));
            dataOutputStream.writeLong(nextBundleID);
            dataOutputStream.flush();
            dataOutputStream.close();
        } catch (Throwable e) {
            LogUtil.e(TAG, "could not save meta data.", e);
        }
        LogUtil.d(TAG, "saveToMetadata:end");
    }

    private int restoreFromProfile() {
        LogUtil.w(TAG, "restoreFromProfile start");
        try {
            File file = new File(storageLocation, "meta");
            if (file.exists()) {
                DataInputStream dataInputStream = new DataInputStream(new FileInputStream(file));
                nextBundleID = dataInputStream.readLong();
                dataInputStream.close();
                File file2 = new File(storageLocation);
                File[] listFiles = file2.listFiles();
                int i = 0;
                while (i < listFiles.length) {
                    if (listFiles[i].isDirectory() && new File(listFiles[i], "meta").exists()) {
                        try {
                            Bundle bundle = new Bundle(listFiles[i]);
                            bundles.put(bundle.getLocation(), bundle);
                            LogUtil.v(TAG, "success to restore bundle: " + bundle.getLocation());
                        } catch (Exception e) {
                            LogUtil.e(TAG, e.getMessage(), e.getCause());
                        }
                    }
                    i++;
                }
                LogUtil.w(TAG, "restoreFromProfile end , return 1(成功)");
                return 1;
            }
            LogUtil.w(TAG, "restoreFromProfile end , return -1(meta不存在)");
            return -1;
        } catch (Exception e) {
            LogUtil.e(TAG, "restoreFromProfile end , return 0(异常)", e);
            return 0;
        }
    }

    public String getCurrentBundleKey(Application application) {
        String bundleKey = null;
        try {
            PackageInfo packageInfo = application.getPackageManager().getPackageInfo(application.getPackageName(), PackageManager.GET_CONFIGURATIONS);
            bundleKey = String.valueOf(packageInfo.versionCode) + "_" + packageInfo.versionName;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bundleKey;
    }

    private static final String KEY_LAST_BUNDLE = "KEY_LAST_BUNDLE";

    private static void saveBundleKey(Application application, String bundleKey) {
        LogUtil.w(TAG, "saveBundleKey:" + bundleKey);
        PreferencesUtil.getInstance(application).putString(KEY_LAST_BUNDLE, bundleKey);
    }

    public String getLastBundleKey(Application application) {
        return PreferencesUtil.getInstance(application).getString(KEY_LAST_BUNDLE);
    }
}
