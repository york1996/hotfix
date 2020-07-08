package com.york1996.hotfix;

import android.app.Application;
import android.content.Context;

import androidx.multidex.MultiDex;

public class CustomApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        MultiDex.install(this);
        HotFixManager.loadDex(base);
        super.attachBaseContext(base);
    }
}
