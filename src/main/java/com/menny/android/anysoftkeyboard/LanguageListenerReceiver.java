package com.menny.android.anysoftkeyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.anysoftkeyboard.AnySoftKeyboard;

public class LanguageListenerReceiver extends BroadcastReceiver {
    public LanguageListenerReceiver() {
        Log.i("OLAF", "STARTING UP RECEIVER");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("OLAF", "GOT SOMETHING");
        if(intent != null) {
            Log.i("OLAF", "THE ACITON IS " + intent.getAction());
            if (intent.hasExtra("language") ) {
                Log.i("OLAF/ELSA", "Sending it out");
                Intent sendOut = new Intent();
                sendOut.setAction(LanguageListenerService.CHANGE_LANGUAGE);
                context.sendBroadcast(sendOut);
                AnySoftKeyboard.currentInstance.changeLanguageBias(intent.getStringExtra("language"));
            }
        }
    }
}
