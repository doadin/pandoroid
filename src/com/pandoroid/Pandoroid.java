package com.pandoroid;

import android.app.Application;
import android.content.Context;

public class Pandoroid extends Application {

    private static Application sApplication;

    public static Application getApplication() {
        return sApplication;
    }

    public static Context getContext() {
        return getApplication().getApplicationContext();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApplication = this;
        System.setProperty("java.net.preferIPv4Stack", "true");
    }
}
