/*
 * Copyright (c) 2013 Menny Even-Danan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.anysoftkeyboard;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.anysoftkeyboard.LayoutSwitchAnimationListener.AnimationType;
import com.anysoftkeyboard.api.KeyCodes;
import com.anysoftkeyboard.devicespecific.Clipboard;
import com.anysoftkeyboard.dictionaries.DictionaryAddOnAndBuilder;
import com.anysoftkeyboard.dictionaries.EditableDictionary;
import com.anysoftkeyboard.dictionaries.ExternalDictionaryFactory;
import com.anysoftkeyboard.dictionaries.Suggest;
import com.anysoftkeyboard.dictionaries.TextEntryState;
import com.anysoftkeyboard.dictionaries.TextEntryState.State;
import com.anysoftkeyboard.dictionaries.sqlite.AutoDictionary;
import com.anysoftkeyboard.keyboards.AnyKeyboard;
import com.anysoftkeyboard.keyboards.AnyKeyboard.AnyKey;
import com.anysoftkeyboard.keyboards.AnyKeyboard.HardKeyboardTranslator;
import com.anysoftkeyboard.keyboards.CondenseType;
import com.anysoftkeyboard.keyboards.GenericKeyboard;
import com.anysoftkeyboard.keyboards.Keyboard.Key;
import com.anysoftkeyboard.keyboards.KeyboardAddOnAndBuilder;
import com.anysoftkeyboard.keyboards.KeyboardSwitcher;
import com.anysoftkeyboard.keyboards.KeyboardSwitcher.NextKeyboardType;
import com.anysoftkeyboard.keyboards.physical.HardKeyboardActionImpl;
import com.anysoftkeyboard.keyboards.physical.MyMetaKeyKeyListener;
import com.anysoftkeyboard.keyboards.views.AnyKeyboardView;
import com.anysoftkeyboard.keyboards.views.CandidateView;
import com.anysoftkeyboard.keyboards.views.OnKeyboardActionListener;
import com.anysoftkeyboard.quicktextkeys.QuickTextKey;
import com.anysoftkeyboard.quicktextkeys.QuickTextKeyFactory;
import com.anysoftkeyboard.receivers.PackagesChangedReceiver;
import com.anysoftkeyboard.receivers.SoundPreferencesChangedReceiver;
import com.anysoftkeyboard.receivers.SoundPreferencesChangedReceiver.SoundPreferencesChangedListener;
import com.anysoftkeyboard.theme.KeyboardTheme;
import com.anysoftkeyboard.theme.KeyboardThemeFactory;
import com.anysoftkeyboard.ui.dev.DeveloperUtils;
import com.anysoftkeyboard.ui.settings.MainSettingsActivity;
import com.anysoftkeyboard.ui.tutorials.TipLayoutsSupport;
import com.anysoftkeyboard.ui.tutorials.TutorialsProvider;
import com.anysoftkeyboard.utils.IMEUtil.GCUtils;
import com.anysoftkeyboard.utils.IMEUtil.GCUtils.MemRelatedOperation;
import com.anysoftkeyboard.utils.Log;
import com.anysoftkeyboard.utils.ModifierKeyState;
import com.anysoftkeyboard.utils.Workarounds;
import com.anysoftkeyboard.voice.VoiceInput;
import com.menny.android.anysoftkeyboard.AnyApplication;
import com.menny.android.anysoftkeyboard.BuildConfig;
import com.menny.android.anysoftkeyboard.FeaturesSet;
import com.menny.android.anysoftkeyboard.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class AnySoftKeyboard extends InputMethodService implements
        OnKeyboardActionListener, OnSharedPreferenceChangeListener,
        AnyKeyboardContextProvider, SoundPreferencesChangedListener,
        OnKeyboardChangeListener {

    private final static String TAG = "ASK";

    private SharedPreferences mPrefs;
    private final com.anysoftkeyboard.Configuration mConfig;

    private final ModifierKeyState mShiftKeyState = new ModifierKeyState();
    private final ModifierKeyState mControlKeyState = new ModifierKeyState();

    private LayoutSwitchAnimationListener mSwitchAnimator;
    private boolean mDistinctMultiTouch = true;
    private AnyKeyboardView mInputView;
    private View mCandidatesParent;
    private CandidateView mCandidateView;
    // private View mRestartSuggestionsView;
    private static final long MINIMUM_REFRESH_TIME_FOR_DICTIONARIES = 30 * 1000;
    private long mLastDictionaryRefresh = -1;
    private int mMinimumWordCorrectionLength = 2;
    private Suggest mSuggest;
    private CompletionInfo[] mCompletions;

    private AlertDialog mOptionsDialog;
    private AlertDialog mQuickTextKeyDialog;

    KeyboardSwitcher mKeyboardSwitcher;
    private final HardKeyboardActionImpl mHardKeyboardAction = new HardKeyboardActionImpl();
    private long mMetaState;

    private HashSet<Character> mSentenceSeparators = new HashSet<Character>();

    // private BTreeDictionary mContactsDictionary;
    private EditableDictionary mUserDictionary;
    private AutoDictionary mAutoDictionary;

    private WordComposer mWord = new WordComposer();

    private int mOrientation = Configuration.ORIENTATION_PORTRAIT;

    private int mCommittedLength;
    /*
     * Do we do prediction now
	 */
    private boolean mPredicting;
    /*
	 * is prediction needed for the current input connection
	 */
    private boolean mPredictionOn;
    /*
	 * is out-side completions needed
	 */
    private boolean mCompletionOn;
    private boolean mAutoSpace;
    private boolean mAutoCorrectOn;
    private boolean mAllowSuggestionsRestart = true;
    private boolean mCurrentlyAllowSuggestionRestart = true;
    private boolean mJustAutoAddedWord = false;
    /*
	 * This will help us detect multi-tap on the SHIFT key for caps-lock
	 */
    private long mShiftStartTime = 0;
    private boolean mCapsLock;

    private static final String SMILEY_PLUGIN_ID = "0077b34d-770f-4083-83e4-081957e06c27";
    private boolean mSmileyOnShortPress;
    private String mOverrideQuickTextText = null;
    private boolean mAutoCap;
    private boolean mQuickFixes;
    /*
	 * Configuration flag. Should we support dictionary suggestions
	 */
    private boolean mShowSuggestions = false;

    private boolean mAutoComplete;
    // private int mCorrectionMode;
    private String mKeyboardChangeNotificationType;
    private static final String KEYBOARD_NOTIFICATION_ALWAYS = "1";
    private static final String KEYBOARD_NOTIFICATION_ON_PHYSICAL = "2";
    private static final String KEYBOARD_NOTIFICATION_NEVER = "3";

    /*
	 * This will help us find out if UNDO_COMMIT is still possible to be done
	 */
    private int mUndoCommitCursorPosition = -2;

    private AudioManager mAudioManager;
    private boolean mSilentMode;
    private boolean mSoundOn;
    // between 0..100. This is the custom volume
    private int mSoundVolume;

    private Vibrator mVibrator;
    private int mVibrationDuration;

    private CondenseType mKeyboardInCondensedMode = CondenseType.None;

    private final Handler mHandler = new KeyboardUIStateHanlder(this);

    private boolean mJustAddedAutoSpace;

    private CharSequence mJustAddOnText = null;

    private boolean mLastCharacterWasShifted = false;

    protected IBinder mImeToken = null;

    private InputMethodManager mInputMethodManager;

    private final boolean mConnectbotTabHack = true;

    private VoiceInput mVoiceRecognitionTrigger;

    public AnySoftKeyboard() {
        mConfig = AnyApplication.getConfig();
    }

    @Override
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return new InputMethodImpl() {
            @Override
            public void attachToken(IBinder token) {
                super.attachToken(token);
                mImeToken = token;
            }
        };
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        if (DeveloperUtils.hasTracingRequested(getApplicationContext())) {
            try {
                DeveloperUtils.startTracing();
                Toast.makeText(getApplicationContext(),
                        R.string.debug_tracing_starting, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                //see issue https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/105
                //I might get a "Permission denied" error.
                e.printStackTrace();
                Toast.makeText(getApplicationContext(),
                        R.string.debug_tracing_starting_failed, Toast.LENGTH_LONG).show();
            }
        }
        Log.i(TAG, "****** AnySoftKeyboard v%s (%d) service started.", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
	    if (!BuildConfig.DEBUG && BuildConfig.VERSION_NAME.endsWith("-SNAPSHOT"))
		    throw new RuntimeException("You can not run a 'RELEASE' build with a SNAPSHOT postfix!");

        // I'm handling animations. No need for any nifty ROMs assistance.
        // I can't use this function with my own animations, since the
        // WindowManager can
        // only use system resources.
		/*
		 * Not right now... performance of my animations is lousy..
		 * getWindow().getWindow().setWindowAnimations(0);
		 */
        mInputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        updateRingerMode();
        // register to receive ringer mode changes for silent mode
        registerReceiver(mSoundPreferencesChangedReceiver,
                mSoundPreferencesChangedReceiver.createFilterToRegisterOn());
        // register to receive packages changes
        registerReceiver(mPackagesChangedReceiver,
                mPackagesChangedReceiver.createFilterToRegisterOn());
        mVibrator = ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE));
        loadSettings();
        mConfig.addChangedListener(this);
        mKeyboardSwitcher = new KeyboardSwitcher(this);

        mOrientation = getResources().getConfiguration().orientation;

        mSentenceSeparators = getCurrentKeyboard().getSentenceSeparators();

        if (mSuggest == null) {
            initSuggest(/* getResources().getConfiguration().locale.toString() */);
        }

        if (mKeyboardChangeNotificationType
                .equals(KEYBOARD_NOTIFICATION_ALWAYS)) {
            notifyKeyboardChangeIfNeeded();
        }

        mVoiceRecognitionTrigger = AnyApplication.getFrankenRobot().embody(
                new VoiceInput.VoiceInputDiagram(this));

        mSwitchAnimator = new LayoutSwitchAnimationListener(this);
    }

    private void initSuggest() {
        mSuggest = new Suggest(this);
        mSuggest.setCorrectionMode(mQuickFixes, mShowSuggestions);
        mSuggest.setMinimumWordLengthForCorrection(mMinimumWordCorrectionLength);
        setDictionariesForCurrentKeyboard();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "AnySoftKeyboard has been destroyed! Cleaning resources..");

        mSwitchAnimator.onDestory();

        mConfig.removeChangedListener(this);

        unregisterReceiver(mSoundPreferencesChangedReceiver);
        unregisterReceiver(mPackagesChangedReceiver);

        mInputMethodManager.hideStatusIcon(mImeToken);

        if (mInputView != null)
            mInputView.onViewNotRequired();
        mInputView = null;

        mKeyboardSwitcher.setInputView(null);

        mSuggest.setAutoDictionary(null);
        mSuggest.setContactsDictionary(getApplicationContext(), false);
        mSuggest.setMainDictionary(getApplicationContext(), null);
        mSuggest.setUserDictionary(null);

        if (DeveloperUtils.hasTracingStarted()) {
            DeveloperUtils.stopTracing();
            Toast.makeText(
                    getApplicationContext(),
                    getString(R.string.debug_tracing_finished,
                            DeveloperUtils.getTraceFile()), Toast.LENGTH_SHORT)
                    .show();
        }

        super.onDestroy();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        if (!mKeyboardChangeNotificationType
                .equals(KEYBOARD_NOTIFICATION_ALWAYS)) {
            mInputMethodManager.hideStatusIcon(mImeToken);
        }
        // Remove pending messages related to update suggestions
        abortCorrection(true, false);
    }

    AnyKeyboardView getInputView() {
        return mInputView;
    }

    @Override
    public View onCreateInputView() {
        if (mInputView != null)
            mInputView.onViewNotRequired();
        mInputView = null;

        GCUtils.getInstance().peformOperationWithMemRetry(TAG,
                new MemRelatedOperation() {
                    public void operation() {
                        mInputView = (AnyKeyboardView) getLayoutInflater()
                                .inflate(R.layout.main_keyboard_layout, null);
                    }
                }, true);
        // reseting token users
        mOptionsDialog = null;
        mQuickTextKeyDialog = null;

        mKeyboardSwitcher.setInputView(mInputView);
        mInputView.setOnKeyboardActionListener(this);

        mDistinctMultiTouch = mInputView.hasDistinctMultitouch();

        return mInputView;
    }

    @Override
    public void setInputView(View view) {
        super.setInputView(view);
        ViewParent parent = view.getParent();
        if (parent instanceof View) {
            // this is required for animations, so the background will be
            // consist.
            ((View) parent).setBackgroundResource(R.drawable.ask_wallpaper);
        } else {
            Log.w(TAG,
                    "*** It seams that the InputView parent is not a View!! This is very strange.");
        }
    }

    @Override
    public View onCreateCandidatesView() {
        mKeyboardSwitcher.makeKeyboards(false);
        final ViewGroup candidateViewContainer = (ViewGroup) getLayoutInflater()
                .inflate(R.layout.candidates, null);
        mCandidatesParent = null;
        mCandidateView = (CandidateView) candidateViewContainer
                .findViewById(R.id.candidates);
        mCandidateView.setService(this);
        setCandidatesViewShown(false);

        final KeyboardTheme theme = KeyboardThemeFactory
                .getCurrentKeyboardTheme(getApplicationContext());
        final TypedArray a = theme.getPackageContext().obtainStyledAttributes(
                null, R.styleable.AnyKeyboardViewTheme, 0,
                theme.getThemeResId());
        int closeTextColor = getResources().getColor(R.color.candidate_other);
        float fontSizePixel = getResources().getDimensionPixelSize(
                R.dimen.candidate_font_height);
        try {
            closeTextColor = a.getColor(
                    R.styleable.AnyKeyboardViewTheme_suggestionOthersTextColor,
                    closeTextColor);
            fontSizePixel = a.getDimension(
                    R.styleable.AnyKeyboardViewTheme_suggestionTextSize,
                    fontSizePixel);
        } catch (Exception e) {
            e.printStackTrace();
        }
        a.recycle();

        mCandidateCloseText = (TextView) candidateViewContainer
                .findViewById(R.id.close_suggestions_strip_text);
        View closeIcon = candidateViewContainer
                .findViewById(R.id.close_suggestions_strip_icon);

        if (mCandidateCloseText != null && closeIcon != null) {// why? In API3
            // it is not
            // supported
            closeIcon.setOnClickListener(new OnClickListener() {
                // two seconds is enough.
                private final static long DOUBLE_TAP_TIMEOUT = 2 * 1000;

                public void onClick(View v) {
                    mHandler.removeMessages(KeyboardUIStateHanlder.MSG_REMOVE_CLOSE_SUGGESTIONS_HINT);
                    mCandidateCloseText.setVisibility(View.VISIBLE);
                    mCandidateCloseText.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(), R.anim.close_candidates_hint_in));
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(KeyboardUIStateHanlder.MSG_REMOVE_CLOSE_SUGGESTIONS_HINT), DOUBLE_TAP_TIMEOUT - 50);
                }
            });

            mCandidateCloseText.setTextColor(closeTextColor);
            mCandidateCloseText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    fontSizePixel);
            mCandidateCloseText.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    mHandler.removeMessages(KeyboardUIStateHanlder.MSG_REMOVE_CLOSE_SUGGESTIONS_HINT);
                    mCandidateCloseText.setVisibility(View.GONE);
                    abortCorrection(true, true);
                }
            });
        }

        final TextView tipsNotification = (TextView) candidateViewContainer
                .findViewById(R.id.tips_notification_on_candidates);
        if (tipsNotification != null) {// why? in API 3 it is not supported
            if (mConfig.getShowTipsNotification()
                    && TutorialsProvider.shouldShowTips(getApplicationContext())) {

                final String TIPS_NOTIFICATION_KEY = "TIPS_NOTIFICATION_KEY";
                TipLayoutsSupport.addTipToCandidate(getApplicationContext(), tipsNotification, TIPS_NOTIFICATION_KEY, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TutorialsProvider.showTips(getApplicationContext());
                    }
                });
            }
        }
		/*
		 * At some point I wanted the user to click a View to restart the
		 * suggestions. I don't any more. mRestartSuggestionsView =
		 * candidateViewContainer.findViewById(R.id.restart_suggestions); if
		 * (mRestartSuggestionsView != null) {
		 * mRestartSuggestionsView.setOnClickListener(new OnClickListener() {
		 * public void onClick(View v) { v.setVisibility(View.GONE);
		 * InputConnection ic = getCurrentInputConnection();
		 * performRestartWordSuggestion(ic, getCursorPosition(ic)); } }); }
		 */
        return candidateViewContainer;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        Log.d(TAG, "onStartInput(EditorInfo:" + attribute.imeOptions + ","
                    + attribute.inputType + ", restarting:" + restarting + ")");

        super.onStartInput(attribute, restarting);

        abortCorrection(true, false);

        if (!restarting) {
            TextEntryState.newSession(this);
            // Clear shift states.
            mMetaState = 0;
            mCurrentlyAllowSuggestionRestart = mAllowSuggestionsRestart;
        } else {
            // something very fishy happening here...
            // this is the only way I can get around it.
            // it seems that when a onStartInput is called with restarting ==
            // true
            // suggestions restart fails :(
            // see Browser when editing multiline textbox
            mCurrentlyAllowSuggestionRestart = false;
        }
    }


    @Override
    public void onStartInputView(final EditorInfo attribute,
                                 final boolean restarting) {
        Log.d(TAG, "onStartInputView(EditorInfo:" + attribute.imeOptions
                    + "," + attribute.inputType + ", restarting:" + restarting
                    + ")");

        super.onStartInputView(attribute, restarting);
        writeTask();

        if (mVoiceRecognitionTrigger != null) {
            mVoiceRecognitionTrigger.onStartInputView();
        }

        if (mInputView == null) {
            return;
        }

        mInputView.setKeyboardActionType(attribute.imeOptions);
        mKeyboardSwitcher.makeKeyboards(false);

        mPredictionOn = false;
        mCompletionOn = false;
        mCompletions = null;
        mCapsLock = false;

        String topActivity = getTopActivity().baseActivity.getPackageName();
        if(mKeyActivityMap.containsKey(topActivity)) {
            mKeyboardSwitcher.setKeyboard(attribute, mKeyActivityMap.get(topActivity) );
            return;
        }

        switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
            case EditorInfo.TYPE_CLASS_DATETIME:
                Log.d(TAG,
                        "Setting MODE_DATETIME as keyboard due to a TYPE_CLASS_DATETIME input.");
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_DATETIME,
                        attribute, restarting);
                break;
            case EditorInfo.TYPE_CLASS_NUMBER:
                Log.d(TAG,
                        "Setting MODE_NUMBERS as keyboard due to a TYPE_CLASS_NUMBER input.");
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_NUMBERS,
                        attribute, restarting);
                break;
            case EditorInfo.TYPE_CLASS_PHONE:
                Log.d(TAG,
                        "Setting MODE_PHONE as keyboard due to a TYPE_CLASS_PHONE input.");
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_PHONE,
                        attribute, restarting);
                break;
            case EditorInfo.TYPE_CLASS_TEXT:
                Log.d(TAG, "A TYPE_CLASS_TEXT input.");
                final int variation = attribute.inputType
                        & EditorInfo.TYPE_MASK_VARIATION;
                switch (variation) {
                    case EditorInfo.TYPE_TEXT_VARIATION_PASSWORD:
                    case EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                    case 0xe0:// API 11 EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD:
                        Log.d(TAG,
                                "A password TYPE_CLASS_TEXT input with no prediction");
                        mPredictionOn = false;
                        break;
                    default:
                        mPredictionOn = true;
                }

                if (mConfig.getInsertSpaceAfterCandidatePick()) {
                    switch (variation) {
                        case EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                        case EditorInfo.TYPE_TEXT_VARIATION_URI:
                        case 0xd0:// API 11
                            // EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                            mAutoSpace = false;
                            break;
                        default:
                            mAutoSpace = true;
                    }
                } else {
                    // some users don't want auto-space
                    mAutoSpace = false;
                }

                switch (variation) {
                    case EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                    case 0xd0:// API 11
                        // EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                        Log.d(TAG,
                                "Setting MODE_EMAIL as keyboard due to a TYPE_TEXT_VARIATION_EMAIL_ADDRESS input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_EMAIL,
                                attribute, restarting);
                        mPredictionOn = false;
                        break;
                    case EditorInfo.TYPE_TEXT_VARIATION_URI:
                        Log.d(TAG,
                                "Setting MODE_URL as keyboard due to a TYPE_TEXT_VARIATION_URI input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_URL,
                                attribute, restarting);
                        mPredictionOn = false;
                        break;
                    case EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE:
                        Log.d(TAG,
                                "Setting MODE_IM as keyboard due to a TYPE_TEXT_VARIATION_SHORT_MESSAGE input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_IM,
                                attribute, restarting);
                        break;
                    default:
                        Log.d(TAG,
                                "Setting MODE_TEXT as keyboard due to a default input.");
                        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                                attribute, restarting);
                }

                final int textFlag = attribute.inputType
                        & EditorInfo.TYPE_MASK_FLAGS;
                switch (textFlag) {
                    case 0x00080000:// FROM API
                        // 5:EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS:
                    case EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE:
                        Log.d(TAG,
                                "Input requested NO_SUGGESTIONS, or it is AUTO_COMPLETE by itself.");
                        mPredictionOn = false;
                        break;
                    default:
                        // we'll keep the previous mPredictionOn value
                }

                break;
            default:
                Log.d(TAG,
                        "Setting MODE_TEXT as keyboard due to a default input.");
                // No class. Probably a console window, or no GUI input
                // connection
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                        attribute, restarting);
                mPredictionOn = false;
                mAutoSpace = true;
        }

        mPredicting = false;
        // mDeleteCount = 0;
        mJustAddedAutoSpace = false;
        setCandidatesViewShown(false);
        // loadSettings();
        updateShiftKeyState(attribute);

        if (mSuggest != null) {
            mSuggest.setCorrectionMode(mQuickFixes, mShowSuggestions);
        }

        mPredictionOn = mPredictionOn && (mShowSuggestions/* || mQuickFixes */);

        if (mCandidateView != null)
            mCandidateView.setSuggestions(null, false, false, false);

        if (mPredictionOn) {
            if ((SystemClock.elapsedRealtime() - mLastDictionaryRefresh) > MINIMUM_REFRESH_TIME_FOR_DICTIONARIES)
                setDictionariesForCurrentKeyboard();
        } else {
            // this will release memory
            setDictionariesForCurrentKeyboard();
        }
    }

    @Override
    public void hideWindow() {
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        if (mQuickTextKeyDialog != null && mQuickTextKeyDialog.isShowing()) {
            mQuickTextKeyDialog.dismiss();
            mQuickTextKeyDialog = null;
        }

        super.hideWindow();

        TextEntryState.endSession();
    }

    @Override
    public void onFinishInput() {
        Log.d(TAG, "onFinishInput()");
        super.onFinishInput();

        if (mInputView != null) {
            mInputView.closing();
        }

        if (!mKeyboardChangeNotificationType
                .equals(KEYBOARD_NOTIFICATION_ALWAYS)) {
            mInputMethodManager.hideStatusIcon(mImeToken);
        }
    }

    /*
	 * this function is called EVERYTIME them selection is changed. This also
	 * includes the underlined suggestions.
	 */
    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd, int candidatesStart,
                                  int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);

        Log.d(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose="
                    + oldSelEnd + ", nss=" + newSelStart + ", nse=" + newSelEnd
                    + ", cs=" + candidatesStart + ", ce=" + candidatesEnd);

        mWord.setGlobalCursorPosition(newSelEnd);

        if (!isPredictionOn()/* || mInputView == null || !mInputView.isShown() */)
            return;// not relevant if no prediction is needed.

        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;// well, I can't do anything without this connection

        Log.d(TAG, "onUpdateSelection: ok, let's see what can be done");

        if (newSelStart != newSelEnd) {
            // text selection. can't predict in this mode
            Log.d(TAG, "onUpdateSelection: text selection.");
            abortCorrection(true, false);
        } else {
            // we have the following options (we are in an input which requires
            // predicting (mPredictionOn == true):
            // 1) predicting and moved inside the word
            // 2) predicting and moved outside the word
            // 2.1) to a new word
            // 2.2) to no word land
            // 3) not predicting
            // 3.1) to a new word
            // 3.2) to no word land

            // so, 1 and 2 requires that predicting is currently done, and the
            // cursor moved
            if (mPredicting) {
                if (newSelStart >= candidatesStart
                        && newSelStart <= candidatesEnd) {
                    // 1) predicting and moved inside the word - just update the
                    // cursor position and shift state
                    // inside the currently selected word
                    int cursorPosition = newSelEnd - candidatesStart;
                    if (mWord.setCursorPostion(cursorPosition)) {
                        Log.d(TAG,
                                    "onUpdateSelection: cursor moving inside the predicting word");
                    }
                } else {
                    Log.d(TAG,
                                "onUpdateSelection: cursor moving outside the currently predicting word");
                    abortCorrection(true, false);
                    // ask user whether to restart
                    postRestartWordSuggestion();
                    // there has been a cursor movement. Maybe a shift state is
                    // required too?
                    postUpdateShiftKeyState();
                }
            } else {
                Log.d(TAG,
                            "onUpdateSelection: not predicting at this moment, maybe the cursor is now at a new word?");
                if (TextEntryState.getState() == State.ACCEPTED_DEFAULT) {
                    if (mUndoCommitCursorPosition == oldSelStart
                            && mUndoCommitCursorPosition != newSelStart) {
                        Log.d(TAG,
                                    "onUpdateSelection: I am in ACCEPTED_DEFAULT state, but the user moved the cursor, so it is not possible to undo_commit now.");
                        abortCorrection(true, false);
                    } else if (mUndoCommitCursorPosition == -2) {
                        Log.d(TAG,
                                    "onUpdateSelection: I am in ACCEPTED_DEFAULT state, time to store the position - I can only undo-commit from here.");
                        mUndoCommitCursorPosition = newSelStart;
                    }
                }
                postRestartWordSuggestion();
                // there has been a cursor movement. Maybe a shift state is
                // required too?
                postUpdateShiftKeyState();
            }
        }
    }

    private void postRestartWordSuggestion() {
        mHandler.removeMessages(KeyboardUIStateHanlder.MSG_RESTART_NEW_WORD_SUGGESTIONS);
		/*
		 * if (mRestartSuggestionsView != null)
		 * mRestartSuggestionsView.setVisibility(View.GONE);
		 */
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(KeyboardUIStateHanlder.MSG_RESTART_NEW_WORD_SUGGESTIONS), 500);
    }

    private static int getCursorPosition(InputConnection connection) {
        if (connection == null)
            return 0;
        ExtractedText extracted = connection.getExtractedText(
                new ExtractedTextRequest(), 0);
        if (extracted == null)
            return 0;
        return extracted.startOffset + extracted.selectionStart;
    }

    private boolean canRestartWordSuggestion(final InputConnection ic) {
        if (mPredicting || !isPredictionOn() || !mAllowSuggestionsRestart
                || !mCurrentlyAllowSuggestionRestart || mInputView == null
                || !mInputView.isShown()) {
            // why?
            // mPredicting - if I'm predicting a word, I can not restart it..
            // right? I'm inside that word!
            // isPredictionOn() - this is obvious.
            // mAllowSuggestionsRestart - config settings
            // mCurrentlyAllowSuggestionRestart - workaround for
            // onInputStart(restarting == true)
            // mInputView == null - obvious, no?
            Log.d(TAG, "performRestartWordSuggestion: no need to restart: mPredicting=%s, isPredictionOn=%s, mAllowSuggestionsRestart=%s, mCurrentlyAllowSuggestionRestart=%s"
                    , mPredicting, isPredictionOn(), mAllowSuggestionsRestart,  mCurrentlyAllowSuggestionRestart);
            return false;
        } else if (!isCursorTouchingWord()) {
            Log.d(TAG, "User moved cursor to no-man land. Bye bye.");
            return false;
        }

        return true;
    }

    public void performRestartWordSuggestion(final InputConnection ic) {
        // I assume ASK DOES NOT predict at this moment!

        // 2) predicting and moved outside the word - abort predicting, update
        // shift state
        // 2.1) to a new word - restart predicting on the new word
        // 2.2) to no word land - nothing else

        // this means that the new cursor position is outside the candidates
        // underline
        // this can be either because the cursor is really outside the
        // previously underlined (suggested)
        // or nothing was suggested.
        // in this case, we would like to reset the prediction and restart
        // if the user clicked inside a different word
        // restart required?
        if (canRestartWordSuggestion(ic)) {// 2.1
            ic.beginBatchEdit();// don't want any events till I finish handling
            // this touch
            Log.d(TAG,
                        "User moved cursor to a word. Should I restart predition?");
            abortCorrection(true, false);

            // locating the word
            CharSequence toLeft = "";
            CharSequence toRight = "";
            while (true) {
                Log.d(TAG, "Checking left offset " + toLeft.length()
                        + ". Currently have '" + toLeft + "'");
                CharSequence newToLeft = ic.getTextBeforeCursor(
                        toLeft.length() + 1, 0);
                if (TextUtils.isEmpty(newToLeft)
                        || isWordSeparator(newToLeft.charAt(0))
                        || newToLeft.length() == toLeft.length()) {
                    break;
                }
                toLeft = newToLeft;
            }
            while (true) {
                Log.d(TAG, "Checking right offset " + toRight.length()
                            + ". Currently have '" + toRight + "'");
                CharSequence newToRight = ic.getTextAfterCursor(
                        toRight.length() + 1, 0);
                if (TextUtils.isEmpty(newToRight)
                        || isWordSeparator(newToRight.charAt(newToRight
                        .length() - 1))
                        || newToRight.length() == toRight.length()) {
                    break;
                }
                toRight = newToRight;
            }
            CharSequence word = toLeft.toString() + toRight.toString();
            Log.d(TAG, "Starting new prediction on word '" + word + "'.");
            mPredicting = word.length() > 0;
            mUndoCommitCursorPosition = -2;// so it will be marked the next time
            mWord.reset();

            final int[] alekNearByKeys = new int[1];

            for (int index = 0; index < word.length(); index++) {
                final char c = word.charAt(index);
                if (index == 0)
                    mWord.setFirstCharCapitalized(Character.isUpperCase(c));

                alekNearByKeys[0] = c;
                mWord.add(c, alekNearByKeys);

                TextEntryState.typedCharacter((char) c, false);
            }
            ic.deleteSurroundingText(toLeft.length(), toRight.length());
            ic.setComposingText(word, 1);
            // repositioning the cursor
            if (toRight.length() > 0) {
                final int cursorPosition = getCursorPosition(ic)
                        - toRight.length();
                Log.d(TAG,
                            "Repositioning the cursor inside the word to position "
                                    + cursorPosition);
                ic.setSelection(cursorPosition, cursorPosition);
            }

            mWord.setCursorPostion(toLeft.length());
            ic.endBatchEdit();
            postUpdateSuggestions();
        } else {
            Log.d(TAG,
                    "performRestartWordSuggestion canRestartWordSuggestion == false");
        }
    }

    private void onPhysicalKeyboardKeyPressed() {
        if (mConfig.hideSoftKeyboardWhenPhysicalKeyPressed())
            hideWindow();

        // For all other keys, if we want to do transformations on
        // text being entered with a hard keyboard, we need to process
        // it and do the appropriate action.
        // using physical keyboard is more annoying with candidate view in
        // the way
        // so we disable it.

        // to clear the underline.
        abortCorrection(true, false);
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] completions) {
        if (FeaturesSet.DEBUG_LOG) {
            Log.d(TAG, "Received completions:");
            for (int i = 0; i < (completions != null ? completions.length : 0); i++) {
                Log.d(TAG, "  #" + i + ": " + completions[i]);
            }
        }

        // completions should be shown if dictionary requires, or if we are in
        // full-screen and have outside completions
        if (mCompletionOn || (isFullscreenMode() && (completions != null))) {
            Log.v(TAG, "Received completions: completion should be shown: "
                    + mCompletionOn + " fullscreen:" + isFullscreenMode());
            mCompletions = completions;
            // we do completions :)

            mCompletionOn = true;
            if (completions == null) {
                Log.v(TAG,
                        "Received completions: completion is NULL. Clearing suggestions.");
                mCandidateView.setSuggestions(null, false, false, false);
                return;
            }

            List<CharSequence> stringList = new ArrayList<CharSequence>();
            for (int i = 0; i < (completions != null ? completions.length : 0); i++) {
                CompletionInfo ci = completions[i];
                if (ci != null)
                    stringList.add(ci.getText());
            }
            Log.v(TAG, "Received completions: setting to suggestions view "
                    + stringList.size() + " completions.");
            // CharSequence typedWord = mWord.getTypedWord();
            setSuggestions(stringList, true, true, true);
            mWord.setPreferredWord(null);
            // I mean, if I'm here, it must be shown...
            setCandidatesViewShown(true);
        } else {
            Log.v(TAG, "Received completions: completions should not be shown.");
        }
    }

    @Override
    public void setCandidatesViewShown(boolean shown) {
        // we show predication only in on-screen keyboard
        // (onEvaluateInputViewShown)
        // or if the physical keyboard supports candidates
        // (mPredictionLandscape)
        final boolean shouldShow = shouldCandidatesStripBeShown() && shown;
        final boolean currentlyShown = mCandidatesParent != null
                && mCandidatesParent.getVisibility() == View.VISIBLE;
        super.setCandidatesViewShown(shouldShow);
        if (shouldShow != currentlyShown) {
            // I believe (can't confirm it) that candidates animation is kinda
            // rare,
            // and it is better to load it on demand, then to keep it in memory
            // always..
            if (shouldShow) {
                mCandidatesParent.setAnimation(AnimationUtils.loadAnimation(
                        getApplicationContext(),
                        R.anim.candidates_bottom_to_up_enter));
            } else {
                mCandidatesParent.setAnimation(AnimationUtils.loadAnimation(
                        getApplicationContext(),
                        R.anim.candidates_up_to_bottom_exit));
            }
        }
    }

    @Override
    public void setCandidatesView(View view) {
        super.setCandidatesView(view);
        mCandidatesParent = view.getParent() instanceof View ? (View) view
                .getParent() : null;
    }

    private void clearSuggestions() {
        setSuggestions(null, false, false, false);
    }

    private void setSuggestions(List<CharSequence> suggestions,
                                boolean completions, boolean typedWordValid,
                                boolean haveMinimalSuggestion) {

        if (mCandidateView != null) {
            mCandidateView.setSuggestions(suggestions, completions,
                    typedWordValid, haveMinimalSuggestion);
        }
    }

    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        switch (mOrientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                return mConfig.getUseFullScreenInputInLandscape();
            default:
                return mConfig.getUseFullScreenInputInPortrait();
        }
    }

    @Override
    public boolean onKeyDown(final int keyCode, KeyEvent event) {
        final boolean shouldTranslateSpecialKeys = isInputViewShown();
        Log.d(TAG, "isInputViewShown=" + shouldTranslateSpecialKeys);

        if (event.isPrintingKey())
            onPhysicalKeyboardKeyPressed();
        mHardKeyboardAction.initializeAction(event, mMetaState);

        InputConnection ic = getCurrentInputConnection();
        Log.d(TAG,
                "Event: Key:"
                        + event.getKeyCode()
                        + " Shift:"
                        + ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0)
                        + " ALT:"
                        + ((event.getMetaState() & KeyEvent.META_ALT_ON) != 0)
                        + " Repeats:" + event.getRepeatCount());

        switch (keyCode) {
            /****
             * SPEACIAL translated HW keys If you add new keys here, do not forget
             * to add to the
             */
            case KeyEvent.KEYCODE_CAMERA:
                if (shouldTranslateSpecialKeys
                        && mConfig.useCameraKeyForBackspaceBackword()) {
                    handleBackword(getCurrentInputConnection());
                    return true;
                }
                // DO NOT DELAY CAMERA KEY with unneeded checks in default mark
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_FOCUS:
                if (shouldTranslateSpecialKeys
                        && mConfig.useCameraKeyForBackspaceBackword()) {
                    handleDeleteLastCharacter(false);
                    return true;
                }
                // DO NOT DELAY FOCUS KEY with unneeded checks in default mark
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (shouldTranslateSpecialKeys
                        && mConfig.useVolumeKeyForLeftRight()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                    return true;
                }
                // DO NOT DELAY VOLUME UP KEY with unneeded checks in default
                // mark
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (shouldTranslateSpecialKeys
                        && mConfig.useVolumeKeyForLeftRight()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                    return true;
                }
                // DO NOT DELAY VOLUME DOWN KEY with unneeded checks in default
                // mark
                return super.onKeyDown(keyCode, event);
            /****
             * END of SPEACIAL translated HW keys code section
             */
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0 && mInputView != null) {
                    if (mInputView.handleBack()) {
                        // consuming the meta keys
                        if (ic != null) {
                            ic.clearMetaKeyStates(Integer.MAX_VALUE);// translated,
                            // so we
                            // also take
                            // care of
                            // the
                            // metakeys.
                        }
                        mMetaState = 0;
                        return true;
                    } /*
				 * else if (mTutorial != null) { mTutorial.close(); mTutorial =
				 * null; }
				 */
                }
                break;
            case 0x000000cc:// API 14: KeyEvent.KEYCODE_LANGUAGE_SWITCH
                switchToNextPhysicalKeyboard(ic);
                return true;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                if (event.isAltPressed()
                        && Workarounds.isAltSpaceLangSwitchNotPossible()) {
                    switchToNextPhysicalKeyboard(ic);
                    return true;
                }
                // NOTE: letting it fallthru to the other meta-keys
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_SYM:
                Log.d(TAG + "-meta-key",
                        getMetaKeysStates("onKeyDown before handle"));
                mMetaState = MyMetaKeyKeyListener.handleKeyDown(mMetaState,
                        keyCode, event);
                Log.d(TAG + "-meta-key",
                        getMetaKeysStates("onKeyDown after handle"));
                break;
            case KeyEvent.KEYCODE_SPACE:
                if ((event.isAltPressed() && !Workarounds
                        .isAltSpaceLangSwitchNotPossible())
                        || event.isShiftPressed()) {
                    switchToNextPhysicalKeyboard(ic);
                    return true;
                }
                // NOTE:
                // letting it fall through to the "default"
            default:

                // Fix issue 185, check if we should process key repeat
                if (!mConfig.getUseRepeatingKeys() && event.getRepeatCount() > 0)
                    return true;

                if (mKeyboardSwitcher.isCurrentKeyboardPhysical()) {
                    // sometimes, the physical keyboard will delete input, and
                    // then
                    // add some.
                    // we'll try to make it nice
                    if (ic != null)
                        ic.beginBatchEdit();
                    try {
                        // issue 393, backword on the hw keyboard!
                        if (mConfig.useBackword()
                                && keyCode == KeyEvent.KEYCODE_DEL
                                && event.isShiftPressed()) {
                            handleBackword(ic);
                            return true;
                        } else/* if (event.isPrintingKey()) */ {
                            // http://article.gmane.org/gmane.comp.handhelds.openmoko.android-freerunner/629
                            AnyKeyboard current = mKeyboardSwitcher
                                    .getCurrentKeyboard();

                            HardKeyboardTranslator keyTranslator = (HardKeyboardTranslator) current;

                            if (BuildConfig.DEBUG) {
                                final String keyboardName = current
                                        .getKeyboardName();

                                Log.d(TAG, "Asking '" + keyboardName
                                        + "' to translate key: " + keyCode);
                                Log.v(TAG,
                                        "Hard Keyboard Action before translation: Shift: "
                                                + mHardKeyboardAction
                                                .isShiftActive()
                                                + ", Alt: "
                                                + mHardKeyboardAction.isAltActive()
                                                + ", Key code: "
                                                + mHardKeyboardAction.getKeyCode()
                                                + ", changed: "
                                                + mHardKeyboardAction
                                                .getKeyCodeWasChanged());
                            }

                            keyTranslator.translatePhysicalCharacter(
                                    mHardKeyboardAction, this);

                            Log.v(TAG,
                                    "Hard Keyboard Action after translation: Key code: "
                                            + mHardKeyboardAction.getKeyCode()
                                            + ", changed: "
                                            + mHardKeyboardAction
                                            .getKeyCodeWasChanged());
                            if (mHardKeyboardAction.getKeyCodeWasChanged()) {
                                final int translatedChar = mHardKeyboardAction
                                        .getKeyCode();
                                // typing my own.
                                onKey(translatedChar, null, -1,
                                        new int[]{translatedChar}, true/*
																	 * simualting
																	 * fromUI
																	 */);
                                // my handling
                                // we are at a regular key press, so we'll
                                // update
                                // our meta-state member
                                mMetaState = MyMetaKeyKeyListener
                                        .adjustMetaAfterKeypress(mMetaState);
                                Log.d(TAG + "-meta-key",
                                        getMetaKeysStates("onKeyDown after adjust - translated"));
                                return true;
                            }
                        }
                    } finally {
                        if (ic != null)
                            ic.endBatchEdit();
                    }
                }
                if (event.isPrintingKey()) {
                    // we are at a regular key press, so we'll update our
                    // meta-state
                    // member
                    mMetaState = MyMetaKeyKeyListener
                            .adjustMetaAfterKeypress(mMetaState);
                    Log.d(TAG + "-meta-key",
                            getMetaKeysStates("onKeyDown after adjust"));
                }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void switchToNextPhysicalKeyboard(InputConnection ic) {
        // consuming the meta keys
        if (ic != null) {
            ic.clearMetaKeyStates(Integer.MAX_VALUE);// translated, so
            // we also take
            // care of the
            // metakeys.
        }
        mMetaState = 0;
        // only physical keyboard
        nextKeyboard(getCurrentInputEditorInfo(),
                NextKeyboardType.AlphabetSupportsPhysical);
    }

    private void notifyKeyboardChangeIfNeeded() {
        // Log.d("anySoftKeyboard","notifyKeyboardChangeIfNeeded");
        // Thread.dumpStack();
        if (mKeyboardSwitcher == null)// happens on first onCreate.
            return;

        if ((mKeyboardSwitcher.isAlphabetMode())
                && !mKeyboardChangeNotificationType
                .equals(KEYBOARD_NOTIFICATION_NEVER)) {
            mInputMethodManager.showStatusIcon(mImeToken, getCurrentKeyboard()
                    .getKeyboardContext().getPackageName(),
                    getCurrentKeyboard().getKeyboardIconResId());
        }
    }

    public AnyKeyboard getCurrentKeyboard() {
        return mKeyboardSwitcher.getCurrentKeyboard();
    }

    public KeyboardSwitcher getKeyboardSwitcher() {
        return mKeyboardSwitcher;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // Issue 248
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (isInputViewShown() == false) {
                    return super.onKeyUp(keyCode, event);
                }
                if (mConfig.useVolumeKeyForLeftRight()) {
                    // no need of vol up/down sound
                    updateShiftKeyState(getCurrentInputEditorInfo());
                    return true;
                }
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mInputView != null && mInputView.isShown()
                        && mInputView.isShifted()) {
                    event = new KeyEvent(event.getDownTime(), event.getEventTime(),
                            event.getAction(), event.getKeyCode(),
                            event.getRepeatCount(), event.getDeviceId(),
                            event.getScanCode(), KeyEvent.META_SHIFT_LEFT_ON
                            | KeyEvent.META_SHIFT_ON);
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null)
                        ic.sendKeyEvent(event);

                    updateShiftKeyState(getCurrentInputEditorInfo());
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_SYM:
                mMetaState = MyMetaKeyKeyListener.handleKeyUp(mMetaState, keyCode,
                        event);
                Log.d("AnySoftKeyboard-meta-key", getMetaKeysStates("onKeyUp"));
                setInputConnectionMetaStateAsCurrentMetaKeyKeyListenerState();
                break;
        }
        boolean r = super.onKeyUp(keyCode, event);
        updateShiftKeyState(getCurrentInputEditorInfo());
        return r;
    }

    private String getMetaKeysStates(String place) {
        final int shiftState = MyMetaKeyKeyListener.getMetaState(mMetaState,
                MyMetaKeyKeyListener.META_SHIFT_ON);
        final int altState = MyMetaKeyKeyListener.getMetaState(mMetaState,
                MyMetaKeyKeyListener.META_ALT_ON);
        final int symState = MyMetaKeyKeyListener.getMetaState(mMetaState,
                MyMetaKeyKeyListener.META_SYM_ON);

        return "Meta keys state at " + place + "- SHIFT:" + shiftState
                + ", ALT:" + altState + " SYM:" + symState + " bits:"
                + MyMetaKeyKeyListener.getMetaState(mMetaState) + " state:"
                + mMetaState;
    }

    private void setInputConnectionMetaStateAsCurrentMetaKeyKeyListenerState() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            int clearStatesFlags = 0;
            if (MyMetaKeyKeyListener.getMetaState(mMetaState,
                    MyMetaKeyKeyListener.META_ALT_ON) == 0)
                clearStatesFlags += KeyEvent.META_ALT_ON;
            if (MyMetaKeyKeyListener.getMetaState(mMetaState,
                    MyMetaKeyKeyListener.META_SHIFT_ON) == 0)
                clearStatesFlags += KeyEvent.META_SHIFT_ON;
            if (MyMetaKeyKeyListener.getMetaState(mMetaState,
                    MyMetaKeyKeyListener.META_SYM_ON) == 0)
                clearStatesFlags += KeyEvent.META_SYM_ON;
            Log.d("AnySoftKeyboard-meta-key",
                    getMetaKeysStates("setInputConnectionMetaStateAsCurrentMetaKeyKeyListenerState with flags: "
                            + clearStatesFlags));
            ic.clearMetaKeyStates(clearStatesFlags);
        }
    }

    private boolean addToDictionaries(WordComposer suggestion,
                                      AutoDictionary.AdditionType type) {
        boolean added = checkAddToDictionary(suggestion, type);
        if (added) {
            Log.i(TAG, "Word '" + suggestion
                    + "' was added to the auto-dictionary.");
        }
        return added;
    }

    /**
     * Adds to the UserBigramDictionary and/or AutoDictionary
     *
     */
    private boolean checkAddToDictionary(WordComposer suggestion,
                                         AutoDictionary.AdditionType type/*
							 * , boolean addToBigramDictionary
							 */) {
        if (suggestion == null || suggestion.length() < 1)
            return false;
        // Only auto-add to dictionary if auto-correct is ON. Otherwise we'll be
        // adding words in situations where the user or application really
        // didn't
        // want corrections enabled or learned.
        if (!mQuickFixes && !mShowSuggestions)
            return false;

        if (suggestion != null && mAutoDictionary != null) {
            String suggestionToCheck = suggestion.getTypedWord().toString();
            if (/*
				 * !addToBigramDictionary &&
				 * mAutoDictionary.isValidWord(suggestionToCheck)//this check is
				 * for promoting from Auto to User ||
				 */(!mSuggest.isValidWord(suggestionToCheck))) {

                final boolean added = mAutoDictionary.addWord(suggestion, type, this);
                if (added && mCandidateView != null) {
                    mCandidateView.notifyAboutWordAdded(suggestion.getTypedWord());
                }
                return added;
            }
			/*
			 * if (mUserBigramDictionary != null) { CharSequence prevWord =
			 * EditingUtil.getPreviousWord(getCurrentInputConnection(),
			 * mSentenceSeparators); if (!TextUtils.isEmpty(prevWord)) {
			 * mUserBigramDictionary.addBigrams(prevWord.toString(),
			 * suggestion.toString()); } }
			 */
        }
        return false;
    }

    private void commitTyped(InputConnection inputConnection) {
        if (mPredicting) {
            mPredicting = false;
            if (mWord.length() > 0) {
                if (inputConnection != null) {
                    inputConnection.commitText(
					mWord.getTypedWord(), 1);
                }
                mCommittedLength = mWord.length();// mComposing.length();
                TextEntryState
                        .acceptedTyped(mWord.getTypedWord());
                addToDictionaries(mWord, AutoDictionary.AdditionType.Typed);
            }
            if (mHandler.hasMessages(KeyboardUIStateHanlder.MSG_UPDATE_SUGGESTIONS)) {
                postUpdateSuggestions(-1);
            }
        }
    }

    private void postUpdateShiftKeyState() {
        mHandler.removeMessages(KeyboardUIStateHanlder.MSG_UPDATE_SHIFT_STATE);
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(KeyboardUIStateHanlder.MSG_UPDATE_SHIFT_STATE), 150);
    }

    public void updateShiftKeyState(EditorInfo attr) {
        mHandler.removeMessages(KeyboardUIStateHanlder.MSG_UPDATE_SHIFT_STATE);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && attr != null && mKeyboardSwitcher.isAlphabetMode()
                && (mInputView != null)) {
            final boolean inputSaysCaps = getCursorCapsMode(ic, attr) != 0;
            if (inputSaysCaps)
                mShiftStartTime = SystemClock.elapsedRealtime();

            mInputView.setShifted(mShiftKeyState.isMomentary() || mCapsLock
                    || inputSaysCaps);
        }
    }

    private int getCursorCapsMode(InputConnection ic, EditorInfo attr) {
        int caps = 0;
        EditorInfo ei = getCurrentInputEditorInfo();
        if (mAutoCap && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
            caps = ic.getCursorCapsMode(attr.inputType);
        }
        return caps;
    }

    private void swapPunctuationAndSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        if (!mConfig.shouldswapPunctuationAndSpace())
            return;
        CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
        if (BuildConfig.DEBUG) {
            String seps = "";
            for (Character c : mSentenceSeparators)
                seps += c;
            Log.d(TAG, "swapPunctuationAndSpace: lastTwo: '" + lastTwo
                    + "', mSentenceSeparators " + mSentenceSeparators.size()
                    + " '" + seps + "'");
        }
        if (lastTwo != null && lastTwo.length() == 2
                && lastTwo.charAt(0) == KeyCodes.SPACE
                && mSentenceSeparators.contains(lastTwo.charAt(1))) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(lastTwo.charAt(1) + " ", 1);
            ic.endBatchEdit();
            updateShiftKeyState(getCurrentInputEditorInfo());
            mJustAddedAutoSpace = true;
            Log.d(TAG, "swapPunctuationAndSpace: YES");
        }
    }

    private void reswapPeriodAndSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && lastThree.charAt(0) == '.'
                && lastThree.charAt(1) == KeyCodes.SPACE
                && lastThree.charAt(2) == '.') {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(3, 0);
            ic.commitText(".. ", 1);
            ic.endBatchEdit();
            updateShiftKeyState(getCurrentInputEditorInfo());
        }
    }

    private void doubleSpace() {
        // if (!mAutoPunctuate) return;
        if (!mConfig.isDoubleSpaceChangesToPeriod())
            return;
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && Character.isLetterOrDigit(lastThree.charAt(0))
                && lastThree.charAt(1) == KeyCodes.SPACE
                && lastThree.charAt(2) == KeyCodes.SPACE) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(". ", 1);
            ic.endBatchEdit();
            updateShiftKeyState(getCurrentInputEditorInfo());
            mJustAddedAutoSpace = true;
        }
    }

    private void removeTrailingSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;

        CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
        if (lastOne != null && lastOne.length() == 1
                && lastOne.charAt(0) == KeyCodes.SPACE) {
            ic.deleteSurroundingText(1, 0);
        }
    }

    public boolean addWordToDictionary(String word) {
        if (mUserDictionary != null) {
            boolean added = mUserDictionary.addWord(word, 128);
            if (added && mCandidateView != null)
                mCandidateView.notifyAboutWordAdded(word);
            return added;
        } else {
            return false;
        }
    }

    public void removeFromUserDictionary(String word) {
        if (mUserDictionary != null) {
            mUserDictionary.deleteWord(word);
            abortCorrection(true, false);
            if (mCandidateView != null)
                mCandidateView.notifyAboutRemovedWord(word);
        }
    }

    /**
     * Helper to determine if a given character code is alphabetic.
     */
    private boolean isAlphabet(int code) {
        // inner letters have more options: ' in English. " in Hebrew, and more.
        if (mPredicting)
            return getCurrentKeyboard().isInnerWordLetter((char) code);
        else
            return getCurrentKeyboard().isStartOfWordLetter((char) code);
    }

    public void onMultiTapStarted() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null)
            ic.beginBatchEdit();
        handleDeleteLastCharacter(true);
        if (mInputView != null)
            mInputView.setShifted(mLastCharacterWasShifted);
    }

    public void onMultiTapEndeded() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null)
            ic.endBatchEdit();
    }

    public void onKey(int primaryCode, Key key, int multiTapIndex,
                      int[] nearByKeyCodes, boolean fromUI) {
        Log.d(TAG, "onKey " + primaryCode);
        // Thread.dumpStack();
        final InputConnection ic = getCurrentInputConnection();

        switch (primaryCode) {
            case KeyCodes.DELETE_WORD:
                if (ic == null)// if we don't want to do anything, lets check
                    // null first.
                    break;
                handleBackword(ic);
                break;
            case KeyCodes.DELETE:
                if (ic == null)// if we don't want to do anything, lets check
                    // null first.
                    break;
                // we do backword if the shift is pressed while pressing
                // backspace (like in a PC)
                // but this is true ONLY if the device has multitouch, or the
                // user specifically asked for it
                if (mInputView != null
                        && mInputView.isShifted()
                        && !mInputView.getKeyboard().isShiftLocked()
                        && ((mDistinctMultiTouch && mShiftKeyState.isMomentary()) || mConfig
                        .useBackword())) {
                    handleBackword(ic);
                } else {
                    handleDeleteLastCharacter(false);
                }
                break;
            case KeyCodes.CLEAR_INPUT:
                if (ic != null) {
                    ic.beginBatchEdit();
                    commitTyped(ic);
                    ic.deleteSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE);
                    ic.endBatchEdit();
                }
                break;
            case KeyCodes.SHIFT:
                if ((!mDistinctMultiTouch) || !fromUI)
                    handleShift(false);
                break;
            case KeyCodes.CTRL:
                if ((!mDistinctMultiTouch) || !fromUI)
                    handleControl(false);
                break;
            case KeyCodes.ARROW_LEFT:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                break;
            case KeyCodes.ARROW_RIGHT:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                break;
            case KeyCodes.ARROW_UP:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP);
                break;
            case KeyCodes.ARROW_DOWN:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN);
                break;
            case KeyCodes.MOVE_HOME:
                if (Workarounds.getApiLevel() >= 11) {
                    sendDownUpKeyEvents(0x0000007a/*
											 * API 11:
											 * KeyEvent.KEYCODE_MOVE_HOME
											 */);
                } else {
                    if (ic != null) {
                        CharSequence textBefore = ic.getTextBeforeCursor(1024, 0);
                        if (!TextUtils.isEmpty(textBefore)) {
                            int newPosition = textBefore.length() - 1;
                            while (newPosition > 0) {
                                char chatAt = textBefore.charAt(newPosition - 1);
                                if (chatAt == '\n' || chatAt == '\r') {
                                    break;
                                }
                                newPosition--;
                            }
                            if (newPosition < 0)
                                newPosition = 0;
                            ic.setSelection(newPosition, newPosition);
                        }
                    }
                }
                break;
            case KeyCodes.MOVE_END:
                if (Workarounds.getApiLevel() >= 11) {
                    //API 11: KeyEvent.KEYCODE_MOVE_END
                    sendDownUpKeyEvents(0x0000007b);
                } else {
                    if (ic != null) {
                        CharSequence textAfter = ic.getTextAfterCursor(1024, 0);
                        if (!TextUtils.isEmpty(textAfter)) {
                            int newPosition = 1;
                            while (newPosition < textAfter.length()) {
                                char chatAt = textAfter.charAt(newPosition);
                                if (chatAt == '\n' || chatAt == '\r') {
                                    break;
                                }
                                newPosition++;
                            }
                            if (newPosition > textAfter.length())
                                newPosition = textAfter.length();
                            try {
                                CharSequence textBefore = ic.getTextBeforeCursor(Integer.MAX_VALUE, 0);
                                if (!TextUtils.isEmpty(textBefore)) {
                                    newPosition = newPosition + textBefore.length();
                                }
                                ic.setSelection(newPosition, newPosition);
                            } catch (Throwable e/*I'm using Integer.MAX_VALUE, it's scary.*/) {
                                Log.w(TAG, "Failed to getTextBeforeCursor.", e);
                            }
                        }
                    }
                }
                break;
            case KeyCodes.VOICE_INPUT:
                if (mVoiceRecognitionTrigger != null)
                    mVoiceRecognitionTrigger
                            .startVoiceRecognition(getCurrentKeyboard()
                                    .getDefaultDictionaryLocale());
                break;
            case KeyCodes.CANCEL:
                if (mOptionsDialog == null || !mOptionsDialog.isShowing()) {
                    handleClose();
                }
                break;
            case KeyCodes.SETTINGS:
                showOptionsMenu();
                break;
            case KeyCodes.SPLIT_LAYOUT:
            case KeyCodes.MERGE_LAYOUT:
            case KeyCodes.COMPACT_LAYOUT_TO_RIGHT:
            case KeyCodes.COMPACT_LAYOUT_TO_LEFT:
                if (getCurrentKeyboard() != null && mInputView != null) {
                    mKeyboardInCondensedMode = CondenseType.fromKeyCode(primaryCode);
                    AnyKeyboard currentKeyboard = getCurrentKeyboard();
                    setKeyboardStuffBeforeSetToView(currentKeyboard);
                    mInputView.setKeyboard(currentKeyboard);
                }
                break;
            case KeyCodes.DOMAIN:
                onText(mConfig.getDomainText());
                break;
            case KeyCodes.QUICK_TEXT:
                QuickTextKey quickTextKey = QuickTextKeyFactory
                        .getCurrentQuickTextKey(this);

                if (mSmileyOnShortPress) {
                    if (TextUtils.isEmpty(mOverrideQuickTextText))
                        onText(quickTextKey.getKeyOutputText());
                    else
                        onText(mOverrideQuickTextText);
                } else {
                    if (quickTextKey.isPopupKeyboardUsed()) {
                        showQuickTextKeyPopupKeyboard(quickTextKey);
                    } else {
                        showQuickTextKeyPopupList(quickTextKey);
                    }
                }
                break;
            case KeyCodes.QUICK_TEXT_POPUP:
                quickTextKey = QuickTextKeyFactory.getCurrentQuickTextKey(this);
                if (quickTextKey.getId().equals(SMILEY_PLUGIN_ID)
                        && !mSmileyOnShortPress) {
                    if (TextUtils.isEmpty(mOverrideQuickTextText))
                        onText(quickTextKey.getKeyOutputText());
                    else
                        onText(mOverrideQuickTextText);
                } else {
                    if (quickTextKey.isPopupKeyboardUsed()) {
                        showQuickTextKeyPopupKeyboard(quickTextKey);
                    } else {
                        showQuickTextKeyPopupList(quickTextKey);
                    }
                }
                break;
            case KeyCodes.MODE_SYMOBLS:
                nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.Symbols);
                break;
            case KeyCodes.MODE_ALPHABET:
                if (mKeyboardSwitcher.shouldPopupForLanguageSwitch()) {
                    showLanguageSelectionDialog();
                } else
                    nextKeyboard(getCurrentInputEditorInfo(),
                            NextKeyboardType.Alphabet);
                break;
            case KeyCodes.UTILITY_KEYBOARD:
                mInputView.openUtilityKeyboard();
                break;
            case KeyCodes.MODE_ALPHABET_POPUP:
                showLanguageSelectionDialog();
                break;
            case KeyCodes.ALT:
                nextAlterKeyboard(getCurrentInputEditorInfo());
                break;
            case KeyCodes.KEYBOARD_CYCLE:
                nextKeyboard(getCurrentInputEditorInfo(), NextKeyboardType.Any);
                break;
            case KeyCodes.KEYBOARD_REVERSE_CYCLE:
                nextKeyboard(getCurrentInputEditorInfo(),
                        NextKeyboardType.PreviousAny);
                break;
            case KeyCodes.KEYBOARD_CYCLE_INSIDE_MODE:
                nextKeyboard(getCurrentInputEditorInfo(),
                        NextKeyboardType.AnyInsideMode);
                break;
            case KeyCodes.KEYBOARD_MODE_CHANGE:
                nextKeyboard(getCurrentInputEditorInfo(),
                        NextKeyboardType.OtherMode);
                break;
            case KeyCodes.CLIPBOARD:
                Clipboard cp = AnyApplication.getFrankenRobot().embody(
                        new Clipboard.ClipboardDiagram(getApplicationContext()));
                CharSequence clipboardText = cp.getText();
                if (!TextUtils.isEmpty(clipboardText)) {
                    onText(clipboardText);
                }
                break;
            case KeyCodes.TAB:
                sendTab();
                break;
            case KeyCodes.ESCAPE:
                sendEscape();
                break;
            default:
                // Issue 146: Right to left langs require reversed parenthesis
                if (mKeyboardSwitcher.isRightToLeftMode()) {
                    if (primaryCode == (int) ')')
                        primaryCode = (int) '(';
                    else if (primaryCode == (int) '(')
                        primaryCode = (int) ')';
                }

                if (isWordSeparator(primaryCode)) {
                    handleSeparator(primaryCode);
                } else {

                    if (mInputView != null && mInputView.isControl()
                            && primaryCode >= 32 && primaryCode < 127) {
                        // http://en.wikipedia.org/wiki/Control_character#How_control_characters_map_to_keyboards
                        int controlCode = primaryCode & 31;
                        Log.d(TAG, "CONTROL state: Char was " + primaryCode
                                + " and now it is " + controlCode);
                        if (controlCode == 9) {
                            sendTab();
                        } else {
                            ic.commitText(Character.toString((char) controlCode), 1);
                        }
                    } else {
                        handleCharacter(primaryCode, key, multiTapIndex,
                                nearByKeyCodes);
                    }
                    // reseting the mSpaceSent, which is set to true upon
                    // selecting
                    // candidate
                    mJustAddedAutoSpace = false;
                }
                // Cancel the just reverted state
                // mJustRevertedSeparator = null;
                if (mKeyboardSwitcher.isKeyCodeRequireSwitchingToAlphabet(primaryCode)) {
                    mKeyboardSwitcher.nextKeyboard(getCurrentInputEditorInfo(),
                            NextKeyboardType.Alphabet);
                }
                break;
        }
    }

    private boolean isConnectbot() {
        EditorInfo ei = getCurrentInputEditorInfo();
        String pkg = ei.packageName;
        return ((pkg.equalsIgnoreCase("org.connectbot")
                || pkg.equalsIgnoreCase("org.woltage.irssiconnectbot") || pkg
                .equalsIgnoreCase("com.pslib.connectbot")) && ei.inputType == 0); // FIXME
    }

    private void sendTab() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        boolean tabHack = isConnectbot() && mConnectbotTabHack;

        // FIXME: tab and ^I don't work in connectbot, hackish workaround
        if (tabHack) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_CENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_I));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I));
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_TAB));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_TAB));
        }
    }

    private void sendEscape() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        if (isConnectbot()) {
            sendKeyChar((char) 27);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, 111 /* KEYCODE_ESCAPE */));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, 111 /* KEYCODE_ESCAPE */));
        }
    }

    public void setKeyboardStuffBeforeSetToView(AnyKeyboard currentKeyboard) {
        currentKeyboard.setCondensedKeys(mKeyboardInCondensedMode);
    }

    private void showLanguageSelectionDialog() {
        KeyboardAddOnAndBuilder[] builders = mKeyboardSwitcher
                .getEnabledKeyboardsBuilders();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_launcher);
        builder.setTitle(getResources().getString(
                R.string.select_keyboard_popup_title));
        builder.setNegativeButton(android.R.string.cancel, null);
        ArrayList<CharSequence> keyboardsIds = new ArrayList<CharSequence>();
        ArrayList<CharSequence> keyboards = new ArrayList<CharSequence>();
        // going over all enabled keyboards
        for (KeyboardAddOnAndBuilder keyboardBuilder : builders) {
            keyboardsIds.add(keyboardBuilder.getId());
            String name = keyboardBuilder.getName();

            keyboards.add(name);
        }

        final CharSequence[] ids = new CharSequence[keyboardsIds.size()];
        final CharSequence[] items = new CharSequence[keyboards.size()];
        keyboardsIds.toArray(ids);
        keyboards.toArray(items);

        builder.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface di, int position) {
                di.dismiss();

                if ((position < 0) || (position >= items.length)) {
                    Log.d(TAG, "Keyboard selection popup canceled");
                } else {
                    CharSequence id = ids[position];
                    Log.d(TAG, "User selected " + items[position]
                                + " with id " + id);
                    EditorInfo currentEditorInfo = getCurrentInputEditorInfo();
                    AnyKeyboard currentKeyboard = mKeyboardSwitcher
                            .nextAlphabetKeyboard(currentEditorInfo,
                                    id.toString());
                    setKeyboardFinalStuff(currentEditorInfo,
                            NextKeyboardType.Alphabet, currentKeyboard);
                }
            }
        });

        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mInputView.getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    public void onText(CharSequence text) {
        Log.d(TAG, "onText: '" + text + "'");
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        ic.beginBatchEdit();
        if (mPredicting) {
            commitTyped(ic);
        }
        abortCorrection(true, false);
        ic.commitText(text, 1);
        ic.endBatchEdit();
        updateShiftKeyState(getCurrentInputEditorInfo());
        // mJustRevertedSeparator = null;
        mJustAddedAutoSpace = false;
        mJustAddOnText = text;
    }

    private boolean performOnTextDeletion(InputConnection ic) {
        if (mJustAddOnText != null && ic != null) {
            final CharSequence onTextText = mJustAddOnText;
            mJustAddOnText = null;
            //just now, the user had cause onText to add text to input.
            //but after that, immediately pressed delete. So I'm guessing deleting the entire text is needed
            final int onTextLength = onTextText.length();
            Log.d(TAG, "Deleting the entire 'onText' input "+onTextText);
            CharSequence cs = ic.getTextBeforeCursor(onTextLength, 0);
            if (onTextText.equals(cs)) {
                ic.deleteSurroundingText(onTextLength, 0);
                postUpdateShiftKeyState();
                return true;
            }
        }

        return false;
    }

    private static boolean isBackwordStopChar(int c) {
        return !Character.isLetter(c);// c == 32 ||
        // PUNCTUATION_CHARACTERS.contains(c);
    }

    private void handleBackword(InputConnection ic) {
        if (ic == null) {
            return;
        }

        if (performOnTextDeletion(ic))
            return;

        if (mPredicting) {
            mWord.reset();
            mPredicting = false;
            ic.setComposingText("", 1);
            postUpdateSuggestions();
            postUpdateShiftKeyState();
            return;
        }
        // I will not delete more than 128 characters. Just a safe-guard.
        // this will also allow me do just one call to getTextBeforeCursor!
        // Which is alway good. This is a part of issue 951.
        CharSequence cs = ic.getTextBeforeCursor(128, 0);
        if (TextUtils.isEmpty(cs)) {
            return;// nothing to delete
        }
        // TWO OPTIONS
        // 1) Either we do like Linux and Windows (and probably ALL desktop
        // OSes):
        // Delete all the characters till a complete word was deleted:
		/*
		 * What to do: We delete until we find a separator (the function
		 * isBackwordStopChar). Note that we MUST delete a delete a whole word!
		 * So if the backword starts at separators, we'll delete those, and then
		 * the word before: "test this,       ," -> "test "
		 */
        // Pro: same as desktop
        // Con: when auto-caps is on (the default), this will delete the
        // previous word, which can be annoying..
        // E.g., Writing a sentence, then a period, then ASK will auto-caps,
        // then when the user press backspace (for some reason),
        // the entire previous word deletes.

        // 2) Or we delete all the characters till we encounter a separator, but
        // delete at least one character.
		/*
		 * What to do: We delete until we find a separator (the function
		 * isBackwordStopChar). Note that we MUST delete a delete at least one
		 * character "test this, " -> "test this," -> "test this" -> "test "
		 */
        // Pro: Supports auto-caps, and mostly similar to desktop OSes
        // Con: Not all desktop use-cases are here.

        // For now, I go with option 2, but I'm open for discussion.

        // 2b) "test this, " -> "test this"

        final int inputLength = cs.length();
        int idx = inputLength - 1;// it's OK since we checked whether cs is
        // empty after retrieving it.
        while (idx > 0 && !isBackwordStopChar((int) cs.charAt(idx))) {
            idx--;
        }
		/*
		 * while (true) { cs = ic.getTextBeforeCursor(idx, 0); //issue 951 if
		 * (TextUtils.isEmpty(cs)) {//it seems that it is possible that
		 * getTextBeforeCursor will return NULL return;//nothing to
		 * delete//issue 951 } csl = cs.length(); if (csl < idx) { // read text
		 * is smaller than requested. We are at start break; } ++idx; int cc =
		 * cs.charAt(0); boolean isBackwordStopChar = isBackwordStopChar(cc); if
		 * (stopCharAtTheEnd) { if (!isBackwordStopChar){ --csl; break; }
		 * continue; } if (isBackwordStopChar) { --csl; break; } }
		 */
        // we want to delete at least one character
        // ic.deleteSurroundingText(csl == 0 ? 1 : csl, 0);
        ic.deleteSurroundingText(inputLength - idx, 0);// it is always > 0 !
        postUpdateShiftKeyState();
    }

    private void handleDeleteLastCharacter(boolean forMultitap) {
        InputConnection ic = getCurrentInputConnection();

        if (!forMultitap && performOnTextDeletion(ic))
            return;

        boolean deleteChar = false;
        if (mPredicting) {
            final boolean wordManipulation = mWord.length() > 0
                    && mWord.cursorPosition() > 0;// mComposing.length();
            if (wordManipulation) {
                mWord.deleteLast();
                final int cursorPosition;
                if (mWord.cursorPosition() != mWord.length())
                    cursorPosition = getCursorPosition(ic);
                else
                    cursorPosition = -1;

                if (cursorPosition >= 0)
                    ic.beginBatchEdit();

                ic.setComposingText(mWord.getTypedWord(), 1);
                if (mWord.length() == 0) {
                    mPredicting = false;
                } else if (cursorPosition >= 0) {
                    ic.setSelection(cursorPosition - 1, cursorPosition - 1);
                }

                if (cursorPosition >= 0)
                    ic.endBatchEdit();

                postUpdateSuggestions();
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        } else {
            deleteChar = true;
        }

        TextEntryState.backspace();
        if (TextEntryState.getState() == TextEntryState.State.UNDO_COMMIT) {
            revertLastWord(deleteChar);
            return;
        } else if (deleteChar) {
            if (mCandidateView != null
                    && mCandidateView.dismissAddToDictionaryHint()) {
                // Go back to the suggestion mode if the user canceled the
                // "Touch again to save".
                // NOTE: In gerenal, we don't revert the word when backspacing
                // from a manual suggestion pick. We deliberately chose a
                // different behavior only in the case of picking the first
                // suggestion (typed word). It's intentional to have made this
                // inconsistent with backspacing after selecting other
                // suggestions.
                revertLastWord(deleteChar);
            } else {
                if (!forMultitap) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                } else {
                    // this code tries to delete the text in a different way,
                    // because of multi-tap stuff
                    // using "deleteSurroundingText" will actually get the input
                    // updated faster!
                    // but will not handle "delete all selected text" feature,
                    // hence the "if (!forMultitap)" above
                    final CharSequence beforeText = ic == null? null : ic.getTextBeforeCursor(1, 0);
                    final int textLengthBeforeDelete = (TextUtils.isEmpty(beforeText)) ? 0 : beforeText.length();
                    if (textLengthBeforeDelete > 0) 
                        ic.deleteSurroundingText(1, 0);
                    else
                        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                }
            }
        }
        // mJustRevertedSeparator = null;
        // handleShiftStateAfterBackspace();
    }

    /*
	 * private void handleShiftStateAfterBackspace() {
	 * switch(mLastCharacterShiftState) { //this code will help use in the case
	 * that //a double/triple tap occur while first one was shifted case
	 * LAST_CHAR_SHIFT_STATE_SHIFTED: if (mInputView != null)
	 * mInputView.setShifted(true); mLastCharacterShiftState =
	 * LAST_CHAR_SHIFT_STATE_DEFAULT; break; // case
	 * LAST_CHAR_SHIFT_STATE_UNSHIFTED: // if (mInputView != null) //
	 * mInputView.setShifted(false); // mLastCharacterShiftState =
	 * LAST_CHAR_SHIFT_STATE_DEFAULT; // break; default:
	 * updateShiftKeyState(getCurrentInputEditorInfo()); break; } }
	 */
    private void handleControl(boolean reset) {
        if (mInputView == null)
            return;
        if (reset) {
            mInputView.setControl(false);
        } else {
            mInputView.setControl(!mInputView.isControl());
        }
    }

    private void handleShift(boolean reset) {
        // user is above anything automatic.
        mHandler.removeMessages(KeyboardUIStateHanlder.MSG_UPDATE_SHIFT_STATE);

        if (mInputView != null && mKeyboardSwitcher.isAlphabetMode()) {
            // shift pressed and this is an alphabet keyboard
            // we want to do:
            // 1)if keyboard is unshifted -> shift view and keyboard
            // 2)if keyboard is shifted -> capslock keyboard
            // 3)if keyboard is capslocked -> unshift view and keyboard
            // final AnyKeyboard currentKeyboard =
            // mKeyboardSwitcher.getCurrentKeyboard();

            final boolean caps;
            if (reset) {
                mInputView.setShifted(false);
                caps = false;
            } else {
                if (!mInputView.isShifted()) {
                    mShiftStartTime = SystemClock.elapsedRealtime();
                    mInputView.setShifted(true);
                    caps = false;
                } else {
                    if (mInputView.isShiftLocked()) {
                        mInputView.setShifted(false);
                        caps = false;
                    } else {
                        // if this is a quick tap, then move to caps locks, else
                        // back to unshifted.
                        if ((SystemClock.elapsedRealtime() - mShiftStartTime) < mConfig
                                .getMultiTapTimeout()) {
                            Log.d(TAG, "handleShift: current keyboard is shifted, within multi-tap period.");
                            mInputView.setShifted(true);
                            caps = true;
                        } else {
                            Log.d(TAG, "handleShift: current keyboard is shifted, not within multi-tap period.");
                            mInputView.setShifted(false);
                            caps = false;
                        }

                    }
                }
            }
            mCapsLock = caps;
            mInputView.setShiftLocked(mCapsLock);
        }
    }

    private void abortCorrection(boolean force, boolean forever) {
        if (force || TextEntryState.isCorrecting()) {
            Log.d(TAG, "abortCorrection will actually abort correct");
            mHandler.removeMessages(KeyboardUIStateHanlder.MSG_UPDATE_SUGGESTIONS);
            mHandler.removeMessages(KeyboardUIStateHanlder.MSG_RESTART_NEW_WORD_SUGGESTIONS);

            final InputConnection ic = getCurrentInputConnection();
            if (ic != null)
                ic.finishComposingText();

            clearSuggestions();

            TextEntryState.reset();
            mUndoCommitCursorPosition = -2;
            mWord.reset();
            mPredicting = false;
            mJustAddedAutoSpace = false;
            if (forever) {
                Log.d(TAG, "abortCorrection will abort correct forever");
                mPredictionOn = false;
                setCandidatesViewShown(false);
                if (mSuggest != null) {
                    mSuggest.setCorrectionMode(false, false);
                }
            }
        }
    }

    private void handleCharacter(final int primaryCode, Key key,
                                 int multiTapIndex, int[] nearByKeyCodes) {
        Log.d(TAG, "handleCharacter: " + primaryCode + ", isPredictionOn:"
                    + isPredictionOn() + ", mPredicting:" + mPredicting);
        if (!mPredicting && isPredictionOn() && isAlphabet(primaryCode)
                && !isCursorTouchingWord()) {
            mPredicting = true;
            mUndoCommitCursorPosition = -2;// so it will be marked the next time
            mWord.reset();
        }

        mLastCharacterWasShifted = (mInputView != null)
                && mInputView.isShifted();

        // if (mLastSelectionStart == mLastSelectionEnd &&
        // TextEntryState.isCorrecting()) {
        // abortCorrection(false);
        // }

        final int primaryCodeForShow;
        if (mInputView != null) {
            if (mInputView.isShifted()) {
                if (key != null && key instanceof AnyKey) {
                    AnyKey anyKey = (AnyKey) key;
                    int[] shiftCodes = anyKey.shiftedCodes;
                    primaryCodeForShow = shiftCodes != null
                            && shiftCodes.length > multiTapIndex ? shiftCodes[multiTapIndex]
                            : Character.toUpperCase(primaryCode);
                } else {
                    primaryCodeForShow = Character.toUpperCase(primaryCode);
                }
            } else {
                primaryCodeForShow = primaryCode;
            }
        } else {
            primaryCodeForShow = primaryCode;
        }

        if (mPredicting) {
            if ((mInputView != null) && mInputView.isShifted()
                    && mWord.cursorPosition() == 0) {
                mWord.setFirstCharCapitalized(true);
            }

            final InputConnection ic = getCurrentInputConnection();
            if (mWord.add(primaryCodeForShow, nearByKeyCodes)) {
                Toast note = Toast
                        .makeText(
                                getApplicationContext(),
                                "Check the logcat for a note from AnySoftKeyboard developers!",
                                Toast.LENGTH_LONG);
                note.show();

                Log.i(TAG,
                        "*******************"
                                + "\nNICE!!! You found the our easter egg! http://www.dailymotion.com/video/x3zg90_gnarls-barkley-crazy-2006-mtv-star_music\n"
                                + "\nAnySoftKeyboard R&D team would like to thank you for using our keyboard application."
                                + "\nWe hope you enjoying it, we enjoyed making it."
                                + "\nWhile developing this application, we heard Gnarls Barkley's Crazy quite a lot, and would like to share it with you."
                                + "\n"
                                + "\nThanks."
                                + "\nMenny Even Danan, Hezi Cohen, Hugo Lopes, Henrik Andersson, Sami Salonen, and Lado Kumsiashvili."
                                + "\n*******************");

                Intent easterEgg = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("http://www.dailymotion.com/video/x3zg90_gnarls-barkley-crazy-2006-mtv-star_music"));
                easterEgg.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(easterEgg);
            }
            if (ic != null) {
                final int cursorPosition;
                if (mWord.cursorPosition() != mWord.length()) {
                    Log.d(TAG,
                        "Cursor is not at the end of the word. I'll need to reposition");
                    cursorPosition = getCursorPosition(ic);
                } else {
                    cursorPosition = -1;
                }

                if (cursorPosition >= 0)
                    ic.beginBatchEdit();

                ic.setComposingText(mWord.getTypedWord(), 1);
                if (cursorPosition >= 0) {
                    ic.setSelection(cursorPosition + 1, cursorPosition + 1);
                    ic.endBatchEdit();
                }
            }
            // this should be done ONLY if the key is a letter, and not a inner
            // character (like ').
            if (Character.isLetter((char) primaryCodeForShow)) {
                postUpdateSuggestions();
            } else {
                // just replace the typed word in the candidates view
                if (mCandidateView != null)
                    mCandidateView.replaceTypedWord(mWord.getTypedWord());
            }
        } else {
            sendKeyChar((char) primaryCodeForShow);
        }
        // updateShiftKeyState(getCurrentInputEditorInfo());
        // measureCps();
        TextEntryState.typedCharacter((char) primaryCodeForShow, false);
    }

    private void handleSeparator(int primaryCode) {
        Log.d(TAG, "handleSeparator: " + primaryCode);

        // Should dismiss the "Touch again to save" message when handling
        // separator
        if (mCandidateView != null
                && mCandidateView.dismissAddToDictionaryHint()) {
            postUpdateSuggestions();
        }

        boolean pickedDefault = false;
        // Handle separator
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        // this is a special case, when the user presses a separator WHILE
        // inside the predicted word.
        // in this case, I will want to just dump the separator.
        final boolean separatorInsideWord = (mWord.cursorPosition() < mWord.length());

        if (mPredicting && !separatorInsideWord) {
            // In certain languages where single quote is a separator, it's
            // better
            // not to auto correct, but accept the typed word. For instance,
            // in Italian dov' should not be expanded to dove' because the
            // elision
            // requires the last vowel to be removed.
            //Also, ACTION does not invoke default picking. See https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/198
            if (mAutoCorrectOn && primaryCode != '\'' && primaryCode != KeyCodes.ENTER
			/*
			 * && (mJustRevertedSeparator == null ||
			 * mJustRevertedSeparator.length() == 0 ||
			 * mJustRevertedSeparator.charAt(0) != primaryCode)
			 */) {
                pickedDefault = pickDefaultSuggestion();
                // Picked the suggestion by the space key. We consider this
                // as "added an auto space".
                if (primaryCode == KeyCodes.SPACE) {
                    mJustAddedAutoSpace = true;
                }
            } else {
                commitTyped(ic);
                abortCorrection(true, false);
            }
        } else if (separatorInsideWord) {
            // when puting a separator in the middile of a word, there is no
            // need to do correction, or keep knowledge
            abortCorrection(true, false);
        }

        if (mJustAddedAutoSpace && primaryCode == KeyCodes.ENTER) {
            removeTrailingSpace();
            mJustAddedAutoSpace = false;
        }

        sendKeyChar((char) primaryCode);

        // Handle the case of ". ." -> " .." with auto-space if necessary
        // before changing the TextEntryState.
        if (TextEntryState.getState() == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED
                && primaryCode == '.') {
            reswapPeriodAndSpace();
        }

        TextEntryState.typedCharacter((char) primaryCode, true);
        if (TextEntryState.getState() == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED
                && primaryCode != KeyCodes.ENTER) {
            swapPunctuationAndSpace();
        } else if (/* isPredictionOn() && */primaryCode == ' ') {
            doubleSpace();
        }
        if (pickedDefault && mWord.getPreferredWord() != null) {
            TextEntryState.acceptedDefault(mWord.getTypedWord(),
                    mWord.getPreferredWord());
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
        if (ic != null) {
            ic.endBatchEdit();
        }
    }

    private void handleClose() {
        boolean closeSelf = true;

        if (mInputView != null)
            closeSelf = mInputView.closing();

        if (closeSelf) {
            commitTyped(getCurrentInputConnection());
            requestHideSelf(0);
            abortCorrection(true, true);
            TextEntryState.endSession();
        }
    }

    // private void checkToggleCapsLock() {
    // if (mKeyboardSwitcher.getCurrentKeyboard().isShifted()) {
    // toggleCapsLock();
    // }
    // }

    private void postUpdateSuggestions() {
        postUpdateSuggestions(100);
    }

    // private void postRestartWordSuggestion(int cursorPosition)
    // {
    // mHandler.removeMessages(MSG_RESTART_NEW_WORD_SUGGESTIONS);
    // Message msg = mHandler.obtainMessage(MSG_RESTART_NEW_WORD_SUGGESTIONS);
    // msg.arg1 = cursorPosition;
    // mHandler.sendMessageDelayed(msg, 600);
    // }

    /**
     * posts an update suggestions request to the messages queue. Removes any previous request.
     * @param delay negative value will cause the call to be done now, in this thread.
     */
    private void postUpdateSuggestions(long delay) {
        mHandler.removeMessages(KeyboardUIStateHanlder.MSG_UPDATE_SUGGESTIONS);
        if (delay > 0)
            mHandler.sendMessageDelayed(mHandler.obtainMessage(KeyboardUIStateHanlder.MSG_UPDATE_SUGGESTIONS), delay);
        else if (delay == 0)
            mHandler.sendMessage(mHandler.obtainMessage(KeyboardUIStateHanlder.MSG_UPDATE_SUGGESTIONS));
        else
            performUpdateSuggestions();
    }

    private boolean isPredictionOn() {
        boolean predictionOn = mPredictionOn;
        // if (!onEvaluateInputViewShown()) predictionOn &=
        // mPredictionLandscape;
        return predictionOn;
    }

    private boolean shouldCandidatesStripBeShown() {
        return mShowSuggestions && onEvaluateInputViewShown();
    }

    /*package*/ void performUpdateSuggestions() {
        Log.d(TAG, "performUpdateSuggestions: has mSuggest:"
                + (mSuggest != null) + ", isPredictionOn:"
                + isPredictionOn() + ", mPredicting:" + mPredicting
                + ", mQuickFixes:" + mQuickFixes + " mShowSuggestions:"
                + mShowSuggestions);
        // Check if we have a suggestion engine attached.
        if (mSuggest == null) {
            return;
        }

        // final boolean showSuggestions = (mCandidateView != null &&
        // mPredicting
        // && isPredictionOn() && shouldCandidatesStripBeShown());

        if (mCandidateCloseText != null)// in API3 this variable is null
            mCandidateCloseText.setVisibility(View.GONE);

        if (!mPredicting) {
            if (mCandidateView != null)
                mCandidateView.setSuggestions(null, false, false, false);
            return;
        }

        List<CharSequence> stringList = mSuggest.getSuggestions(/* mInputView, */mWord, false);
        boolean correctionAvailable = mSuggest.hasMinimalCorrection();
        // || mCorrectionMode == mSuggest.CORRECTION_FULL;
        CharSequence typedWord = mWord.getTypedWord();
        // If we're in basic correct
        boolean typedWordValid = mSuggest.isValidWord(typedWord);/*
                || (preferCapitalization() && mSuggest.isValidWord(typedWord
                .toString().toLowerCase()));*/

        if (mShowSuggestions || mQuickFixes) {
            correctionAvailable |= typedWordValid;
        }

        // Don't auto-correct words with multiple capital letter
        correctionAvailable &= !mWord.isMostlyCaps();
        correctionAvailable &= !TextEntryState.isCorrecting();

        mCandidateView.setSuggestions(stringList, false, typedWordValid,
                correctionAvailable);
        if (stringList.size() > 0) {
            if (correctionAvailable && !typedWordValid && stringList.size() > 1) {
                mWord.setPreferredWord(stringList.get(1));
            } else {
                mWord.setPreferredWord(typedWord);
            }
        } else {
            mWord.setPreferredWord(null);
        }
        setCandidatesViewShown(shouldCandidatesStripBeShown() || mCompletionOn);
    }

    private boolean pickDefaultSuggestion() {

        // Complete any pending candidate query first
        if (mHandler.hasMessages(KeyboardUIStateHanlder.MSG_UPDATE_SUGGESTIONS)) {
            postUpdateSuggestions(-1);
        }

        final CharSequence bestWord = mWord.getPreferredWord();
        Log.d(TAG, "pickDefaultSuggestion: bestWord:" + bestWord);
        
        if (!TextUtils.isEmpty(bestWord)) {
            final CharSequence typedWord = mWord.getTypedWord();
            TextEntryState.acceptedDefault(typedWord, bestWord);
            // mJustAccepted = true;
            final boolean fixed = !typedWord.equals(pickSuggestion(bestWord, !bestWord.equals(typedWord)));
            if (!fixed) {//if the word typed was auto-replaced, we should not learn it.
                // Add the word to the auto dictionary if it's not a known word
                addToDictionaries(mWord, AutoDictionary.AdditionType.Typed);
            }
            return true;
        }
        return false;
    }

    public void pickSuggestionManually(int index, CharSequence suggestion) {
        Log.d(TAG, "pickSuggestionManually: index " + index
                + " suggestion " + suggestion);
        final boolean correcting = TextEntryState.isCorrecting();
        final InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        try {
            if (mCompletionOn && mCompletions != null && index >= 0
                    && index < mCompletions.length) {
                CompletionInfo ci = mCompletions[index];
                if (ic != null) {
                    ic.commitCompletion(ci);
                }
                mCommittedLength = suggestion.length();
                if (mCandidateView != null) {
                    mCandidateView.clear();
                }
                updateShiftKeyState(getCurrentInputEditorInfo());
                return;
            }
            pickSuggestion(suggestion, correcting);

            TextEntryState.acceptedSuggestion(mWord.getTypedWord(), suggestion);
            // Follow it with a space
            if (mAutoSpace && !correcting) {
                sendSpace();
                mJustAddedAutoSpace = true;
            }
            // Add the word to the auto dictionary if it's not a known word
            mJustAutoAddedWord = false;
            if (index == 0) {
                mJustAutoAddedWord = addToDictionaries(mWord, AutoDictionary.AdditionType.Picked);
            }

            final boolean showingAddToDictionaryHint = !mJustAutoAddedWord
                    && index == 0
                    && (mQuickFixes || mShowSuggestions)
                    && !mSuggest.isValidWord(suggestion)// this is for the case
                    // that the word was
                    // auto-added upon
                    // picking
                    && !mSuggest.isValidWord(suggestion.toString()
                    .toLowerCase());

            if (!mJustAutoAddedWord) {
				/*
				 * if (!correcting) { // Fool the state watcher so that a
				 * subsequent backspace will // not do a revert, unless // we
				 * just did a correction, in which case we need to stay in //
				 * TextEntryState.State.PICKED_SUGGESTION state.
				 * TextEntryState.typedCharacter((char) KeyCodes.SPACE, true);
				 * setNextSuggestions(); } else if (!showingAddToDictionaryHint)
				 * { // If we're not showing the "Touch again to save", then
				 * show // corrections again. // In case the cursor position
				 * doesn't change, make sure we show // the suggestions again.
				 * clearSuggestions(); // postUpdateOldSuggestions(); }
				 */
                if (showingAddToDictionaryHint && mCandidateView != null) {
                    mCandidateView.showAddToDictionaryHint(suggestion);
                }
            }
        } finally {
            if (ic != null) {
                ic.endBatchEdit();
            }
        }
    }

    /**
     * Commits the chosen word to the text field and saves it for later
     * retrieval.
     *
     * @param suggestion the suggestion picked by the user to be committed to the text
     *                   field
     * @param correcting whether this is due to a correction of an existing word.
     */
    private CharSequence pickSuggestion(CharSequence suggestion,
                                        boolean correcting) {
        if (mCapsLock) {
            suggestion = suggestion.toString().toUpperCase();
        } else if (preferCapitalization()
                || (mKeyboardSwitcher.isAlphabetMode() && (mInputView != null) && mInputView
                .isShifted())) {
            suggestion = Character.toUpperCase(suggestion.charAt(0))
                    + suggestion.subSequence(1, suggestion.length()).toString();
        }

        mWord.setPreferredWord(suggestion);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (correcting) {
                AnyApplication.getDeviceSpecific()
                        .commitCorrectionToInputConnection(ic, mWord);
                // and drawing popout text
                mInputView.popTextOutOfKey(mWord.getPreferredWord());
            } else {
                ic.commitText(suggestion, 1);
            }
        }
        mPredicting = false;
        mCommittedLength = suggestion.length();
        if (mCandidateView != null) {
            mCandidateView.setSuggestions(null, false, false, false);
        }
        // If we just corrected a word, then don't show punctuations
        if (!correcting) {
            setNextSuggestions();
        }

        updateShiftKeyState(getCurrentInputEditorInfo());

        return suggestion;
    }

    private boolean isCursorTouchingWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return false;

        CharSequence toLeft = ic.getTextBeforeCursor(1, 0);
        // It is not exactly clear to me why, but sometimes, although I request
        // 1 character, I get
        // the entire text. This causes me to incorrectly detect restart
        // suggestions...
        if (!TextUtils.isEmpty(toLeft) && toLeft.length() == 1
                && !isWordSeparator(toLeft.charAt(0))) {
            return true;
        }

        CharSequence toRight = ic.getTextAfterCursor(1, 0);
        if (!TextUtils.isEmpty(toRight) && toRight.length() == 1
                && !isWordSeparator(toRight.charAt(0))) {
            return true;
        }

        return false;
    }

    public void revertLastWord(boolean deleteChar) {
        Log.d(TAG, "revertLastWord deleteChar:" + deleteChar
                + ", mWord.size:" + mWord.length() + " mPredicting:"
                + mPredicting + " mCommittedLength" + mCommittedLength);

        final int length = mWord.length();// mComposing.length();
        if (!mPredicting && length > 0) {
            final CharSequence typedWord = mWord.getTypedWord();
            final InputConnection ic = getCurrentInputConnection();
            mPredicting = true;
            mUndoCommitCursorPosition = -2;
            ic.beginBatchEdit();
            // mJustRevertedSeparator = ic.getTextBeforeCursor(1, 0);
            if (deleteChar)
                ic.deleteSurroundingText(1, 0);
            int toDelete = mCommittedLength;
            CharSequence toTheLeft = ic
                    .getTextBeforeCursor(mCommittedLength, 0);
            if (toTheLeft != null && toTheLeft.length() > 0
                    && isWordSeparator(toTheLeft.charAt(0))) {
                toDelete--;
            }
            ic.deleteSurroundingText(toDelete, 0);
            ic.setComposingText(typedWord/* mComposing */, 1);
            TextEntryState.backspace();
            ic.endBatchEdit();
            postUpdateSuggestions(-1);
            if (mJustAutoAddedWord && mUserDictionary != null) {
                // we'll also need to REMOVE the word from the user dictionary
                // now...
                // Since the user revert the commited word, and ASK auto-added
                // that word, this word will need to be removed.
                Log.i(TAG,
                        "Since the word '"
                                + typedWord
                                + "' was auto-added to the user-dictionary, it will not be deleted.");
                removeFromUserDictionary(typedWord.toString());
            }
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            // mJustRevertedSeparator = null;
        }
    }

    // private void setOldSuggestions() {
    // //mShowingVoiceSuggestions = false;
    // if (mCandidateView != null &&
    // mCandidateView.isShowingAddToDictionaryHint()) {
    // return;
    // }
    // InputConnection ic = getCurrentInputConnection();
    // if (ic == null) return;
    // if (!mPredicting) {
    // // Extract the selected or touching text
    // EditingUtil.SelectedWord touching =
    // EditingUtil.getWordAtCursorOrSelection(ic,
    // mLastSelectionStart, mLastSelectionEnd, mWordSeparators);
    //
    // if (touching != null && touching.word.length() > 1) {
    // ic.beginBatchEdit();
    //
    // if (!applyVoiceAlternatives(touching) &&
    // !applyTypedAlternatives(touching)) {
    // abortCorrection(true);
    // } else {
    // TextEntryState.selectedForCorrection();
    // EditingUtil.underlineWord(ic, touching);
    // }
    //
    // ic.endBatchEdit();
    // } else {
    // abortCorrection(true);
    // setNextSuggestions(); // Show the punctuation suggestions list
    // }
    // } else {
    // abortCorrection(true);
    // }
    // }

    private static final List<CharSequence> msEmptyNextSuggestions = new ArrayList<CharSequence>(
            0);

    private void setNextSuggestions() {
        setSuggestions(
		/* mSuggest.getInitialSuggestions() */msEmptyNextSuggestions, false,
                false, false);
    }

    public boolean isWordSeparator(int code) {
        return (!isAlphabet(code));
    }

    private void sendSpace() {
        sendKeyChar((char) KeyCodes.SPACE);
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    public boolean preferCapitalization() {
        return mWord.isFirstCharCapitalized();
    }

    private void nextAlterKeyboard(EditorInfo currentEditorInfo) {
        Log.d(TAG, "nextAlterKeyboard: currentEditorInfo.inputType="
                + currentEditorInfo.inputType);

        // AnyKeyboard currentKeyboard = mKeyboardSwitcher.getCurrentKeyboard();
        if (getCurrentKeyboard() == null) {
            Log.d(TAG,
                    "nextKeyboard: Looking for next keyboard. No current keyboard.");
        } else {
            Log.d(TAG,
                    "nextKeyboard: Looking for next keyboard. Current keyboard is:"
                            + getCurrentKeyboard().getKeyboardName());
        }

        mKeyboardSwitcher.nextAlterKeyboard(currentEditorInfo);

        Log.i(TAG, "nextAlterKeyboard: Setting next keyboard to: "
                + getCurrentKeyboard().getKeyboardName());
    }

    private void nextKeyboard(EditorInfo currentEditorInfo,
                              KeyboardSwitcher.NextKeyboardType type) {
        Log.d(TAG, "nextKeyboard: currentEditorInfo.inputType="
                + currentEditorInfo.inputType + " type:" + type);

        // in numeric keyboards, the LANG key will go back to the original
        // alphabet keyboard-
        // so no need to look for the next keyboard, 'mLastSelectedKeyboard'
        // holds the last
        // keyboard used.
        AnyKeyboard keyboard = mKeyboardSwitcher.nextKeyboard(
                currentEditorInfo, type);

        if (!(keyboard instanceof GenericKeyboard)) {
            mSentenceSeparators = keyboard.getSentenceSeparators();
        }
        setKeyboardFinalStuff(currentEditorInfo, type, keyboard);
    }

    private void setKeyboardFinalStuff(EditorInfo currentEditorInfo,
                                       KeyboardSwitcher.NextKeyboardType type, AnyKeyboard currentKeyboard) {
        updateShiftKeyState(currentEditorInfo);
        mCapsLock = currentKeyboard.isShiftLocked();
        mShiftKeyState.reset();
        mControlKeyState.reset();
        // changing dictionary
        setDictionariesForCurrentKeyboard();
        // Notifying if needed
        if ((mKeyboardChangeNotificationType
                .equals(KEYBOARD_NOTIFICATION_ALWAYS))
                || (mKeyboardChangeNotificationType
                .equals(KEYBOARD_NOTIFICATION_ON_PHYSICAL) && (type == NextKeyboardType.AlphabetSupportsPhysical))) {
            notifyKeyboardChangeIfNeeded();
        }
        postUpdateSuggestions();
    }

    public void onSwipeRight(boolean onSpaceBar, boolean twoFingersGesture) {
        final int keyCode = mConfig.getGestureSwipeRightKeyCode(onSpaceBar, twoFingersGesture);
        Log.d(TAG, "onSwipeRight " + ((onSpaceBar) ? " + space" : "") + ((twoFingersGesture) ? " + two-fingers" : "")
                + " => code " + keyCode);
        if (keyCode != 0)
            mSwitchAnimator
                    .doSwitchAnimation(AnimationType.SwipeRight, keyCode);
    }

    public void onSwipeLeft(boolean onSpaceBar, boolean twoFingersGesture) {
        final int keyCode = mConfig.getGestureSwipeLeftKeyCode(onSpaceBar, twoFingersGesture);
        Log.d(TAG, "onSwipeLeft " + ((onSpaceBar) ? " + space" : "") + ((twoFingersGesture) ? " + two-fingers" : "")
                + " => code " + keyCode);
        if (keyCode != 0)
            mSwitchAnimator.doSwitchAnimation(AnimationType.SwipeLeft, keyCode);
    }

    public void onSwipeDown(boolean onSpaceBar) {
        final int keyCode = mConfig.getGestureSwipeDownKeyCode();
        Log.d(TAG, "onSwipeDown " + ((onSpaceBar) ? " + space" : "")
                + " => code " + keyCode);
        if (keyCode != 0)
            onKey(keyCode, null, -1, new int[]{keyCode}, false);
    }

    public void onSwipeUp(boolean onSpaceBar) {
        final int keyCode = mConfig.getGestureSwipeUpKeyCode(onSpaceBar);
        Log.d(TAG, "onSwipeUp " + ((onSpaceBar) ? " + space" : "")
                + " => code " + keyCode);
        if (keyCode != 0) {
            onKey(keyCode, null, -1, new int[]{keyCode}, false);
        }
    }

    public void onPinch() {
        final int keyCode = mConfig.getGesturePinchKeyCode();
        Log.d(TAG, "onPinch => code " + keyCode);
        if (keyCode != 0)
            onKey(keyCode, null, -1, new int[]{keyCode}, false);
    }

    public void onSeparate() {
        final int keyCode = mConfig.getGestureSeparateKeyCode();
        Log.d(TAG, "onSeparate => code " + keyCode);
        if (keyCode != 0)
            onKey(keyCode, null, -1, new int[]{keyCode}, false);
    }

    private void sendKeyDown(InputConnection ic, int key) {
        if (ic != null)
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
    }

    private void sendKeyUp(InputConnection ic, int key) {
        if (ic != null)
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    public void onPress(int primaryCode) {
        InputConnection ic = getCurrentInputConnection();
        Log.d(TAG, "onPress:" + primaryCode);
        if (mVibrationDuration > 0 && primaryCode != 0) {
            mVibrator.vibrate(mVibrationDuration);
        }

        if (mDistinctMultiTouch && primaryCode == KeyCodes.SHIFT) {
            mShiftKeyState.onPress();
            handleShift(false);
        } else {
            mShiftKeyState.onOtherKeyPressed();
        }

        if (mDistinctMultiTouch && primaryCode == KeyCodes.CTRL) {
            mControlKeyState.onPress();
            handleControl(false);
            sendKeyDown(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT (API 11 and up)
        } else {
            mControlKeyState.onOtherKeyPressed();
        }

        if (mSoundOn && (!mSilentMode) && primaryCode != 0) {
            final int keyFX;
            switch (primaryCode) {
                case 13:
                case KeyCodes.ENTER:
                    keyFX = AudioManager.FX_KEYPRESS_RETURN;
                    break;
                case KeyCodes.DELETE:
                    keyFX = AudioManager.FX_KEYPRESS_DELETE;
                    break;
                case KeyCodes.SPACE:
                    keyFX = AudioManager.FX_KEYPRESS_SPACEBAR;
                    break;
                default:
                    keyFX = AudioManager.FX_KEY_CLICK;
            }
            final float fxVolume;
            // creating scoop to make sure volume and maxVolume
            // are not used
            {
                final int volume;
                final int maxVolume;
                if (mSoundVolume > 0) {
                    volume = mSoundVolume;
                    maxVolume = 100;
                    // pre-eclair
                    // volume is between 0..8 (float)
                    // eclair
                    // volume is between 0..1 (float)
                    if (Workarounds.getApiLevel() >= 5) {
                        fxVolume = ((float) volume) / ((float) maxVolume);
                    } else {
                        fxVolume = 8 * ((float) volume) / ((float) maxVolume);
                    }
                } else {
                    fxVolume = -1.0f;
                }

            }

            Log.d(TAG, "Sound on key-pressed. Sound ID:" + keyFX
                    + " with volume " + fxVolume);

            mAudioManager.playSoundEffect(keyFX, fxVolume);
        }
    }

    public void onRelease(int primaryCode) {
        InputConnection ic = getCurrentInputConnection();
        Log.d(TAG, "onRelease:" + primaryCode);
        if (mDistinctMultiTouch && primaryCode == KeyCodes.SHIFT) {
            if (mShiftKeyState.isMomentary())
                handleShift(true);
            mShiftKeyState.onRelease();
        }
        if (mDistinctMultiTouch && primaryCode == KeyCodes.CTRL) {
            if (mControlKeyState.isMomentary())
                handleControl(true);
            sendKeyUp(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT
            mControlKeyState.onRelease();
        }
        // the user lifted the finger, let's handle the shift
        if (primaryCode != KeyCodes.SHIFT)
            updateShiftKeyState(getCurrentInputEditorInfo());
        // and set the control state. Checking if the inputview is null
        // this is weird, I agree, how can onRelease be called, if the inputview
        // is null
        // well, there are some cases where the onRelease is called with a
        // delayed message, and in this case the view may already be disposed!
        // Issue #94.
        if (primaryCode != KeyCodes.CTRL && mInputView != null)
            mInputView.setControl(mControlKeyState.isMomentary());
    }

    // receive ringer mode changes to detect silent mode
    private final SoundPreferencesChangedReceiver mSoundPreferencesChangedReceiver = new SoundPreferencesChangedReceiver(
            this);
    private final PackagesChangedReceiver mPackagesChangedReceiver = new PackagesChangedReceiver(
            this);

    // update flags for silent mode
    public void updateRingerMode() {
        mSilentMode = (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);
    }

    private void loadSettings() {
        // Get the settings preferences
        SharedPreferences sp = PreferenceManager
                .getDefaultSharedPreferences(this);

        mVibrationDuration = Integer
                .parseInt(sp
                        .getString(
                                getString(R.string.settings_key_vibrate_on_key_press_duration),
                                getString(R.string.settings_default_vibrate_on_key_press_duration)));

        mSoundOn = sp.getBoolean(getString(R.string.settings_key_sound_on),
                getResources().getBoolean(R.bool.settings_default_sound_on));
        if (mSoundOn) {
            Log.i(TAG,
                    "Loading sounds effects from AUDIO_SERVICE due to configuration change.");
            mAudioManager.loadSoundEffects();
        }
        // checking the volume
        boolean customVolume = sp.getBoolean("use_custom_sound_volume", false);
        int newVolume;
        if (customVolume) {
            newVolume = sp.getInt("custom_sound_volume", 0) + 1;
            Log.i(TAG, "Custom volume checked: " + newVolume + " out of 100");
        } else {
            Log.i(TAG, "Custom volume un-checked.");
            newVolume = -1;
        }
        mSoundVolume = newVolume;

        // in order to support the old type of configuration
        mKeyboardChangeNotificationType = sp
                .getString(
                        getString(R.string.settings_key_physical_keyboard_change_notification_type),
                        getString(R.string.settings_default_physical_keyboard_change_notification_type));

        // now clearing the notification, and it will be re-shown if needed
        mInputMethodManager.hideStatusIcon(mImeToken);
        // mNotificationManager.cancel(KEYBOARD_NOTIFICATION_ID);
        // should it be always on?
        if (mKeyboardChangeNotificationType
                .equals(KEYBOARD_NOTIFICATION_ALWAYS))
            notifyKeyboardChangeIfNeeded();

        mAutoCap = sp.getBoolean("auto_caps", true);

        mShowSuggestions = sp.getBoolean("candidates_on", true);

        setDictionariesForCurrentKeyboard();

        mAutoComplete = sp.getBoolean("auto_complete", true)
                && mShowSuggestions;

        mQuickFixes = sp.getBoolean("quick_fix", true);

        mAllowSuggestionsRestart = sp.getBoolean(
                getString(R.string.settings_key_allow_suggestions_restart),
                getResources().getBoolean(
                        R.bool.settings_default_allow_suggestions_restart));

        mAutoCorrectOn = /* mSuggest != null && *//*
												 * Suggestion always exists,
												 * maybe not at the moment, but
												 * shortly
												 */
                (mAutoComplete/* || mQuickFixes */);

        // mCorrectionMode = mAutoComplete ? 2
        // : (/*mShowSuggestions*/ mQuickFixes ? 1 : 0);

        mSmileyOnShortPress = sp
                .getBoolean(
                        getString(R.string.settings_key_emoticon_long_press_opens_popup),
                        getResources()
                                .getBoolean(
                                        R.bool.settings_default_emoticon_long_press_opens_popup));
        // mSmileyPopupType =
        // sp.getString(getString(R.string.settings_key_smiley_popup_type),
        // getString(R.string.settings_default_smiley_popup_type));
        mOverrideQuickTextText = sp.getString(
                getString(R.string.settings_key_emoticon_default_text), null);

        mMinimumWordCorrectionLength = sp
                .getInt(getString(R.string.settings_key_min_length_for_word_correction__),
                        2);
        if (mSuggest != null)
            mSuggest.setMinimumWordLengthForCorrection(mMinimumWordCorrectionLength);

        setInitialCondensedState(getResources().getConfiguration());
    }

    private void setDictionariesForCurrentKeyboard() {
        if (mSuggest != null) {
            if (!mPredictionOn) {
                Log.d(TAG,
                        "No suggestion is required. I'll try to release memory from the dictionary.");
                // DictionaryFactory.getInstance().releaseAllDictionaries();
                mSuggest.setMainDictionary(getApplicationContext(), null);
                mSuggest.setUserDictionary(null);
                mSuggest.setAutoDictionary(null);
                mLastDictionaryRefresh = -1;
            } else {
                mLastDictionaryRefresh = SystemClock.elapsedRealtime();
                // It null at the creation of the application.
                if ((mKeyboardSwitcher != null)
                        && mKeyboardSwitcher.isAlphabetMode()) {
                    AnyKeyboard currentKeyobard = mKeyboardSwitcher
                            .getCurrentKeyboard();

                    // if there is a mapping in the settings, we'll use that,
                    // else we'll
                    // return the default
                    String mappingSettingsKey = getDictionaryOverrideKey(currentKeyobard);
                    String defaultDictionary = currentKeyobard
                            .getDefaultDictionaryLocale();
                    String dictionaryValue = mPrefs.getString(
                            mappingSettingsKey, null);
                    DictionaryAddOnAndBuilder dictionaryBuilder = null;

                    if (dictionaryValue == null) {
                        dictionaryBuilder = ExternalDictionaryFactory
                                .getDictionaryBuilderByLocale(currentKeyobard
                                        .getDefaultDictionaryLocale(),
                                        getApplicationContext());
                    } else {
                        Log.d(TAG,
                                "Default dictionary '"
                                        + (defaultDictionary == null ? "None"
                                        : defaultDictionary)
                                        + "' for keyboard '"
                                        + currentKeyobard
                                        .getKeyboardPrefId()
                                        + "' has been overriden to '"
                                        + dictionaryValue + "'");
                        dictionaryBuilder = ExternalDictionaryFactory
                                .getDictionaryBuilderById(dictionaryValue,
                                        getApplicationContext());
                    }

                    mSuggest.setMainDictionary(getApplicationContext(), dictionaryBuilder);
                    String localeForSupportingDictionaries = dictionaryBuilder != null ? dictionaryBuilder
                            .getLanguage() : defaultDictionary;
                    mUserDictionary = mSuggest.getDictionaryFactory()
                            .createUserDictionary(getApplicationContext(),
                                    localeForSupportingDictionaries);
                    mSuggest.setUserDictionary(mUserDictionary);

                    mAutoDictionary = mSuggest.getDictionaryFactory().createAutoDictionary(getApplicationContext(),localeForSupportingDictionaries);
                    mSuggest.setAutoDictionary(mAutoDictionary);
                    mSuggest.setContactsDictionary(getApplicationContext(), mConfig.useContactsDictionary());
                }
            }
        }
    }

    private String getDictionaryOverrideKey(AnyKeyboard currentKeyboard) {
        String mappingSettingsKey = currentKeyboard.getKeyboardPrefId()
                + "_override_dictionary";
        return mappingSettingsKey;
    }

    private void launchSettings() {
        handleClose();
        Intent intent = new Intent();
        intent.setClass(AnySoftKeyboard.this, MainSettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void launchDictionaryOverriding() {
        final String dictionaryOverridingKey = getDictionaryOverrideKey(getCurrentKeyboard());
        final String dictionaryOverrideValue = mPrefs.getString(
                dictionaryOverridingKey, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_launcher);
        builder.setTitle(getResources().getString(
                R.string.override_dictionary_title,
                getCurrentKeyboard().getKeyboardName()));
        builder.setNegativeButton(android.R.string.cancel, null);
        ArrayList<CharSequence> dictionaryIds = new ArrayList<CharSequence>();
        ArrayList<CharSequence> dictionaries = new ArrayList<CharSequence>();
        // null dictionary is handled as the default for the keyboard
        dictionaryIds.add(null);
        final String SELECTED = "\u2714 ";
        final String NOT_SELECTED = "- ";
        if (dictionaryOverrideValue == null)
            dictionaries.add(SELECTED
                    + getString(R.string.override_dictionary_default));
        else
            dictionaries.add(NOT_SELECTED
                    + getString(R.string.override_dictionary_default));
        // going over all installed dictionaries
        for (DictionaryAddOnAndBuilder dictionaryBuilder : ExternalDictionaryFactory
                .getAllAvailableExternalDictionaries(getApplicationContext())) {
            dictionaryIds.add(dictionaryBuilder.getId());
            String description;
            if (dictionaryOverrideValue != null
                    && dictionaryBuilder.getId()
                    .equals(dictionaryOverrideValue))
                description = SELECTED;
            else
                description = NOT_SELECTED;
            description += dictionaryBuilder.getName();
            if (!TextUtils.isEmpty(dictionaryBuilder.getDescription())) {
                description += " (" + dictionaryBuilder.getDescription() + ")";
            }
            dictionaries.add(description);
        }

        final CharSequence[] ids = new CharSequence[dictionaryIds.size()];
        final CharSequence[] items = new CharSequence[dictionaries.size()];
        dictionaries.toArray(items);
        dictionaryIds.toArray(ids);

        builder.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface di, int position) {
                di.dismiss();
                Editor editor = mPrefs.edit();
                switch (position) {
                    case 0:
                        Log.d(TAG,
                                "Dictionary overriden disabled. User selected default.");
                        editor.remove(dictionaryOverridingKey);
                        showToastMessage(R.string.override_disabled, true);
                        break;
                    default:
                        if ((position < 0) || (position >= items.length)) {
                            Log.d(TAG, "Dictionary override dialog canceled.");
                        } else {
                            CharSequence id = ids[position];
                            String selectedDictionaryId = (id == null) ? null : id
                                    .toString();
                            String selectedLanguageString = items[position]
                                    .toString();
                            Log.d(TAG,
                                    "Dictionary override. User selected "
                                            + selectedLanguageString
                                            + " which corresponds to id "
                                            + ((selectedDictionaryId == null) ? "(null)"
                                            : selectedDictionaryId));
                            editor.putString(dictionaryOverridingKey,
                                    selectedDictionaryId);
                            showToastMessage(
                                    getString(R.string.override_enabled,
                                            selectedLanguageString), true);
                        }
                        break;
                }
                editor.commit();
                setDictionariesForCurrentKeyboard();
            }
        });

        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mInputView.getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    private void showOptionsMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_launcher);
        builder.setNegativeButton(android.R.string.cancel, null);
        CharSequence itemSettings = getString(R.string.ime_settings);
        CharSequence itemOverrideDictionary = getString(R.string.override_dictionary);
        CharSequence itemInputMethod = getString(R.string.change_ime);
        builder.setItems(new CharSequence[]{itemSettings,
                itemOverrideDictionary, itemInputMethod},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int position) {
                        di.dismiss();
                        switch (position) {
                            case 0:
                                launchSettings();
                                break;
                            case 1:
                                launchDictionaryOverriding();
                                break;
                            case 2:
                                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                                        .showInputMethodPicker();
                                break;
                        }
                    }
                });
        builder.setTitle(getResources().getString(R.string.ime_name));
        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mInputView.getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {

        // If orientation changed while predicting, commit the change
        if (newConfig.orientation != mOrientation) {

            setInitialCondensedState(newConfig);

            commitTyped(getCurrentInputConnection());
            mOrientation = newConfig.orientation;

            mKeyboardSwitcher.makeKeyboards(true);
            // new WxH. need new object.
            mSentenceSeparators = getCurrentKeyboard().getSentenceSeparators();

            if (mKeyboardChangeNotificationType
                    .equals(KEYBOARD_NOTIFICATION_ALWAYS))// should
                // it
                // be
                // always
                // on?
                notifyKeyboardChangeIfNeeded();
        }

        super.onConfigurationChanged(newConfig);
    }

    private void setInitialCondensedState(Configuration newConfig) {
        final String defaultCondensed = mConfig.getInitialKeyboardCondenseState();
        mKeyboardInCondensedMode = CondenseType.None;
        if (defaultCondensed.equals("split_always")) {
            mKeyboardInCondensedMode = CondenseType.Split;
        } else if (defaultCondensed.equals("split_in_landscape")) {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
                mKeyboardInCondensedMode = CondenseType.Split;
            else
                mKeyboardInCondensedMode = CondenseType.None;
        } else if (defaultCondensed.equals("compact_right_always")) {
            mKeyboardInCondensedMode = CondenseType.CompactToRight;
        } else if (defaultCondensed.equals("compact_left_always")) {
            mKeyboardInCondensedMode = CondenseType.CompactToLeft;
        }

        Log.d(TAG, "setInitialCondensedState: defaultCondensed is "
                + defaultCondensed + " and mKeyboardInCondensedMode is "
                + mKeyboardInCondensedMode);
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        Log.d(TAG, "onSharedPreferenceChanged - key:" + key);
        AnyApplication.requestBackupToCloud();

        boolean isKeyboardKey = key
                .startsWith(KeyboardAddOnAndBuilder.KEYBOARD_PREF_PREFIX);
        boolean isDictionaryKey = key.startsWith("dictionary_");
        boolean isQuickTextKey = key
                .equals(getString(R.string.settings_key_active_quick_text_key));
        if (isKeyboardKey || isDictionaryKey || isQuickTextKey) {
            mKeyboardSwitcher.makeKeyboards(true);
        }

        loadSettings();

        if (isDictionaryKey
                || key.equals(getString(R.string.settings_key_use_contacts_dictionary))
                || key.equals(getString(R.string.settings_key_auto_dictionary_threshold))) {
            setDictionariesForCurrentKeyboard();
        } else if (
            // key.equals(getString(R.string.settings_key_top_keyboard_row_id)) ||
                key.equals(getString(R.string.settings_key_ext_kbd_bottom_row_key))
                        || key.equals(getString(R.string.settings_key_ext_kbd_top_row_key))
                        || key.equals(getString(R.string.settings_key_ext_kbd_ext_ketboard_key))
                        || key.equals(getString(R.string.settings_key_ext_kbd_hidden_bottom_row_key))
                        || key.equals(getString(R.string.settings_key_keyboard_theme_key))
                        || key.equals("zoom_factor_keys_in_portrait")
                        || key.equals("zoom_factor_keys_in_landscape")
                        || key.equals(getString(R.string.settings_key_smiley_icon_on_smileys_key))
                        || key.equals(getString(R.string.settings_key_long_press_timeout))
                        || key.equals(getString(R.string.settings_key_multitap_timeout))
                        || key.equals(getString(R.string.settings_key_default_split_state))) {
            // in some cases we do want to force keyboards recreations
            resetKeyboardView(key
                    .equals(getString(R.string.settings_key_keyboard_theme_key)));
        }
    }

    /*
	 * public void appendCharactersToInput(CharSequence textToCommit) { if
	 * (DEBUG) Log.d(TAG, "appendCharactersToInput: '"+ textToCommit+"'");
	 * for(int index=0; index<textToCommit.length(); index++) { final char c =
	 * textToCommit.charAt(index); mWord.add(c, new int[]{c}); }
	 * //mComposing.append(textToCommit); if (mPredictionOn)
	 * getCurrentInputConnection().setComposingText(mWord.getTypedWord(),
	 * textToCommit.length()); else commitTyped(getCurrentInputConnection());
	 * updateShiftKeyState(getCurrentInputEditorInfo()); }
	 */
    public void deleteLastCharactersFromInput(int countToDelete) {
        if (countToDelete == 0)
            return;

        final int currentLength = mWord.length();
        boolean shouldDeleteUsingCompletion;
        if (currentLength > 0) {
            shouldDeleteUsingCompletion = true;
            if (currentLength > countToDelete) {
                // mComposing.delete(currentLength - countToDelete,
                // currentLength);

                int deletesLeft = countToDelete;
                while (deletesLeft > 0) {
                    mWord.deleteLast();
                    deletesLeft--;
                }
            } else {
                // mComposing.setLength(0);
                mWord.reset();
            }
        } else {
            shouldDeleteUsingCompletion = false;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (mPredictionOn && shouldDeleteUsingCompletion) {
                ic.setComposingText(mWord.getTypedWord()/* mComposing */, 1);
                // updateCandidates();
            } else {
                ic.deleteSurroundingText(countToDelete, 0);
            }
        }
        postUpdateShiftKeyState();
    }

    public void showToastMessage(int resId, boolean forShortTime) {
        CharSequence text = getResources().getText(resId);
        showToastMessage(text, forShortTime);
    }

    private void showToastMessage(CharSequence text, boolean forShortTime) {
        int duration = forShortTime ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
        Log.v(TAG, "showToastMessage: '" + text + "'. For: "
                    + duration);
        Toast.makeText(this.getApplication(), text, duration).show();
    }

    @Override
    public void onLowMemory() {
        Log.w(TAG,
                "The OS has reported that it is low on memory!. I'll try to clear some cache.");
        mKeyboardSwitcher.onLowMemory();
        // DictionaryFactory.getInstance().onLowMemory(mSuggest.getMainDictionary());
        super.onLowMemory();
    }

    /*package*/ TextView mCandidateCloseText;

    private void showQuickTextKeyPopupKeyboard(QuickTextKey quickTextKey) {
        if (mInputView != null) {
            /*if (quickTextKey.getPackageContext() == getApplicationContext()) {
                mInputView.simulateLongPress(KeyCodes.QUICK_TEXT);
            } else {*/
                mInputView.showQuickTextPopupKeyboard(quickTextKey);
            /*}*/
        }
    }

    private void showQuickTextKeyPopupList(final QuickTextKey key) {
        if (mQuickTextKeyDialog == null) {
            String[] names = key.getPopupListNames();
            final String[] texts = key.getPopupListValues();
            int[] icons = key.getPopupListIconResIds();

            final int N = names.length;

            List<Map<String, ?>> entries = new ArrayList<Map<String, ?>>();
            for (int i = 0; i < N; i++) {
                HashMap<String, Object> entry = new HashMap<String, Object>();

                entry.put("name", names[i]);
                entry.put("text", texts[i]);
                if (icons != null)
                    entry.put("icons", icons[i]);

                entries.add(entry);
            }

            int layout;
            String[] from;
            int[] to;
            if (icons == null) {
                layout = R.layout.quick_text_key_menu_item_without_icon;
                from = new String[]{"name", "text"};
                to = new int[]{R.id.quick_text_name, R.id.quick_text_output};
            } else {
                layout = R.layout.quick_text_key_menu_item_with_icon;
                from = new String[]{"name", "text", "icons"};
                to = new int[]{R.id.quick_text_name, R.id.quick_text_output,
                        R.id.quick_text_icon};
            }
            final SimpleAdapter a = new SimpleAdapter(this, entries, layout,
                    from, to);
            SimpleAdapter.ViewBinder viewBinder = new SimpleAdapter.ViewBinder() {
                public boolean setViewValue(View view, Object data,
                                            String textRepresentation) {
                    if (view instanceof ImageView) {
                        Drawable img = key.getPackageContext().getResources()
                                .getDrawable((Integer) data);
                        ((ImageView) view).setImageDrawable(img);
                        return true;
                    }
                    return false;
                }
            };
            a.setViewBinder(viewBinder);

            AlertDialog.Builder b = new AlertDialog.Builder(this);

            b.setTitle(getString(R.string.menu_insert_smiley));

            b.setCancelable(true);
            b.setAdapter(a, new DialogInterface.OnClickListener() {
                @SuppressWarnings("unchecked")
                // I know, I know, it is not safe to cast, but I created the
                // list, and willing to pay the price.
                public final void onClick(DialogInterface dialog, int which) {
                    HashMap<String, Object> item = (HashMap<String, Object>) a
                            .getItem(which);
                    onText((String) item.get("text"));

                    dialog.dismiss();
                }
            });

            mQuickTextKeyDialog = b.create();
            Window window = mQuickTextKeyDialog.getWindow();
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.token = mInputView.getWindowToken();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }

        mQuickTextKeyDialog.show();
    }

    public boolean promoteToUserDictionary(String word, int frequency) {
        if (mUserDictionary.isValidWord(word))
            return false;
        else
            return mUserDictionary.addWord(word, frequency);
    }

    public WordComposer getCurrentWord() {
        return mWord;
    }

    /**
     * Override this to control when the soft input area should be shown to the
     * user. The default implementation only shows the input view when there is
     * no hard keyboard or the keyboard is hidden. If you change what this
     * returns, you will need to call {@link #updateInputViewShown()} yourself
     * whenever the returned value may have changed to have it re-evalauted and
     * applied. This needs to be re-coded for Issue 620
     */
    @Override
    public boolean onEvaluateInputViewShown() {
        Configuration config = getResources().getConfiguration();
        return config.keyboard == Configuration.KEYBOARD_NOKEYS
                || config.hardKeyboardHidden == Configuration.KEYBOARDHIDDEN_YES;
    }

    public void onCancel() {
        // don't know what to do here.
    }

    public void resetKeyboardView(boolean recreateView) {
        handleClose();
        if (mKeyboardSwitcher != null)
            mKeyboardSwitcher.makeKeyboards(true);
        if (recreateView) {
            // also recreate keyboard view
            setInputView(onCreateInputView());
            setCandidatesView(onCreateCandidatesView());
            setCandidatesViewShown(false);
        }
    }

    private ActivityManager.RunningTaskInfo getTopActivity() {
        ActivityManager activityManager = (ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> list = activityManager.getRunningTasks(1);
        return list.get(0);

    }

    Map<String, AnyKeyboard> mKeyActivityMap = new HashMap<>();
    String mCurrentApp = "";
    public void writeTask() {
        String topLevelApp = getTopActivity().baseActivity.getPackageName();
        if(!topLevelApp.equals(mCurrentApp)) {
            if(!mKeyActivityMap.containsKey(topLevelApp)) {
                mKeyActivityMap.put(topLevelApp, mKeyboardSwitcher.getCurrentKeyboard());
            }
        }

        mCurrentApp = topLevelApp;
        printMap();
    }

    private void printMap() {
        Log.i(TAG + "FOOBAR/CURRENT_STATE", Arrays.toString(mKeyActivityMap.entrySet().toArray()));
    }

    @Override
    public void onKeyboardChange(KeyboardSwitcher switcher) {
        Log.i(TAG + "FOOBAR", "AnySoftKeyboard/onKeyboardChange() Switching called");
        String topLevelApp = getTopActivity().baseActivity.getPackageName();
        mKeyActivityMap.put(topLevelApp, switcher.getCurrentKeyboard());
    }

    public void setTopActivityKeyboard(AnyKeyboard keyboard) {
        Log.i(TAG + "FOOBAR", "AnySoftKeyboard/setTopActivityKeyboard() called");
        printMap();
        String topLevelApp = getTopActivity().baseActivity.getPackageName();
        mKeyActivityMap.put(topLevelApp, keyboard);
        printMap();
    }

    public AnyKeyboard getStoredKeyboard() {
        return mKeyActivityMap.get(getTopActivity().baseActivity.getPackageName());
    }

}
