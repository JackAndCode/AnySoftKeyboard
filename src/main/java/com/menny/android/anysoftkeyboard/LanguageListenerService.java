package com.menny.android.anysoftkeyboard;

import android.app.IntentService;
import android.content.Intent;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p/>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class LanguageListenerService extends IntentService {
    // TODO: Rename actions, choose action names that describe tasks that this
    // IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS
    private static final String ACTION_FOO = "com.menny.android.anysoftkeyboard.action.FOO";
    private static final String ACTION_BAZ = "com.menny.android.anysoftkeyboard.action.BAZ";

    // TODO: Rename parameters
    private static final String EXTRA_PARAM1 = "com.menny.android.anysoftkeyboard.extra.PARAM1";
    private static final String EXTRA_PARAM2 = "com.menny.android.anysoftkeyboard.extra.PARAM2";
    public static final String CHANGE_LANGUAGE = "com.dnuon.CHANGELANG";
    public static final String GET_CHANGE_LANGUAGE = "com.dnuon.GET_CHANGELANG";

    public LanguageListenerService() {
        super("LanguageListenerService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {

        }
    }

}
