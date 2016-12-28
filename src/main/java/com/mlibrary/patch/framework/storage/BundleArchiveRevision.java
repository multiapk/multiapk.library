package com.mlibrary.patch.framework.storage;

import android.content.res.AssetManager;

import com.mlibrary.patch.hack.SysHacks;
import com.mlibrary.patch.framework.BundleDexInstaller;
import com.mlibrary.patch.runtime.RuntimeArgs;
import com.mlibrary.patch.util.FileUtil;
import com.mlibrary.patch.util.LogUtil;
import com.mlibrary.patch.MDynamicLib;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Created by yb.wang on 14/12/31.
 * Bundle 存储文件：bundle.zip，bundle.dex
 * 采用PathClassLoader 加载 dex文件，并opt释放优化后的dex
 */
class BundleArchiveRevision {
    private static final String TAG = MDynamicLib.TAG + ":BundleArchiveRevision";
    private static final String BUNDLE_FILE_NAME = "bundle.zip";
    private static final String BUNDLE_DEX_FILE = "bundle.dex";
    private static final String FILE_PROTOCOL = "file:";

    private final long revisionNum;
    private File revisioDir;
    private File bundleFile;
    private String revisionLocation;

    BundleArchiveRevision(long revisionNumber, File file, InputStream inputStream) throws IOException {
        this.revisionNum = revisionNumber;
        this.revisioDir = file;
        if (!this.revisioDir.exists())
            //noinspection ResultOfMethodCallIgnored
            this.revisioDir.mkdirs();
        this.revisionLocation = FILE_PROTOCOL;
        this.bundleFile = new File(file, BUNDLE_FILE_NAME);
        FileUtil.copyInputStreamToFile(inputStream, this.bundleFile);
        updateMetaData();
    }

    BundleArchiveRevision(long revisionNumber, File file) throws IOException {
        File fileMeta = new File(file, "meta");
        if (fileMeta.exists()) {
            DataInputStream dataInputStream = new DataInputStream(new FileInputStream(fileMeta));
            this.revisionLocation = dataInputStream.readUTF();
            dataInputStream.close();
            this.revisionNum = revisionNumber;
            this.revisioDir = file;
            if (!this.revisioDir.exists())
                //noinspection ResultOfMethodCallIgnored
                this.revisioDir.mkdirs();
            this.bundleFile = new File(file, BUNDLE_FILE_NAME);
            return;
        }
        throw new IOException("Can not find meta file in " + file.getAbsolutePath());
    }

    private void updateMetaData() throws IOException {
        File file = new File(this.revisioDir, "meta");
        DataOutputStream dataOutputStream = null;
        try {
            if (!file.getParentFile().exists())
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
            dataOutputStream = new DataOutputStream(new FileOutputStream(file));
            dataOutputStream.writeUTF(this.revisionLocation);
            dataOutputStream.flush();
        } catch (IOException ex) {
            throw new IOException("Can not save meta data " + file.getAbsolutePath());
        } finally {
            try {
                if (dataOutputStream != null) dataOutputStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public long getRevisionNum() {
        return this.revisionNum;
    }

    File getRevisionDir() {
        return this.revisioDir;
    }

    File getRevisionFile() {
        return this.bundleFile;
    }

    boolean isDexOptimized() {
        return new File(this.revisioDir, BUNDLE_DEX_FILE).exists();
    }

    boolean isBundleInstalled() {
        return bundleFile.exists() && verifyZipFile(bundleFile);
    }

    private boolean verifyZipFile(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            try {
                zipFile.close();
                return true;
            } catch (IOException e) {
                LogUtil.e(TAG, "Failed to close zip file: " + file.getAbsolutePath(), e);
            }
        } catch (ZipException ex) {
            LogUtil.e(TAG, "File " + file.getAbsolutePath() + " is not a valid zip file.", ex);
        } catch (IOException ex) {
            LogUtil.e(TAG, "Got an IOException trying to open zip file: " + file.getAbsolutePath(), ex);
        }
        return false;
    }

    void installBundleDex() throws Exception {
        List<File> files = new ArrayList<>();//additionalClassPathEntries
        files.add(this.bundleFile);
        BundleDexInstaller.installBundleDex(RuntimeArgs.androidApplication.getClassLoader(), revisioDir, files, false);
    }

    InputStream openAssetInputStream(String fileName) throws IOException {
        try {
            AssetManager assetManager = AssetManager.class.newInstance();
            if ((Integer) SysHacks.AssetManager_addAssetPath.invoke(assetManager, this.bundleFile.getAbsoluteFile()) != 0)
                return assetManager.open(fileName);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    InputStream openNonAssetInputStream(String fileName) throws IOException {
        try {
            AssetManager assetManager = AssetManager.class.newInstance();
            int intValue = (Integer) SysHacks.AssetManager_addAssetPath.invoke(assetManager, this.bundleFile.getAbsoluteFile());
            if (intValue != 0)
                return assetManager.openNonAssetFd(intValue, fileName).createInputStream();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
