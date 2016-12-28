package com.mlibrary.patch.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;

import com.mlibrary.patch.util.LogUtil;
import com.mlibrary.patch.MLibraryPatch;


/**
 * Created by yb.wang on 15/1/6.
 * Android Context Hook 挂载载系统的Context中，拦截相应的方法
 */
public class ContextWrapperHook extends ContextWrapper {
    public static final String TAG = MLibraryPatch.TAG + ":" + ContextWrapperHook.class.getName();

    public ContextWrapperHook(Context context) {
        super(context);
    }

    @Override
    public Resources getResources() {
        LogUtil.d(TAG, "getResources() is invoking");
        return RuntimeArgs.delegateResources;
    }

    @Override
    public AssetManager getAssets() {
        LogUtil.d(TAG, "getAssets() is invoking");
        return RuntimeArgs.delegateResources.getAssets();
    }
}