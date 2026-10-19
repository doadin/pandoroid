package com.pandoroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        Intent serviceIntent = new Intent(context, PandoraRadioService.class);
        serviceIntent.setAction(intent.getAction());
        context.startService(serviceIntent);
    }
}
