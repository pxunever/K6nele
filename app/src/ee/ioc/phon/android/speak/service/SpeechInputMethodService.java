package ee.ioc.phon.android.speak.service;

import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.List;

import ee.ioc.phon.android.speak.Log;
import ee.ioc.phon.android.speak.R;
import ee.ioc.phon.android.speak.activity.PermissionsRequesterActivity;
import ee.ioc.phon.android.speak.model.CallerInfo;
import ee.ioc.phon.android.speak.utils.Utils;
import ee.ioc.phon.android.speak.view.AbstractSpeechInputViewListener;
import ee.ioc.phon.android.speak.view.SpeechInputView;
import ee.ioc.phon.android.speechutils.Extras;
import ee.ioc.phon.android.speechutils.editor.CommandEditor;
import ee.ioc.phon.android.speechutils.editor.CommandEditorResult;
import ee.ioc.phon.android.speechutils.editor.InputConnectionCommandEditor;
import ee.ioc.phon.android.speechutils.utils.PreferenceUtils;

public class SpeechInputMethodService extends InputMethodService {

    private InputMethodManager mInputMethodManager;
    private SpeechInputView mInputView;
    private CommandEditor mCommandEditor;
    private boolean mShowPartialResults;
    private SharedPreferences mPrefs;
    private Resources mRes;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("onCreate");
        mInputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        mCommandEditor = new InputConnectionCommandEditor(getApplicationContext());
    }

    /**
     * This is called at configuration change. We just kill the running session.
     * TODO: better handle configuration changes
     */
    @Override
    public void onInitializeInterface() {
        Log.i("onInitializeInterface");
        closeSession();
    }

    @Override
    public View onCreateInputView() {
        Log.i("onCreateInputView");
        mInputView = (SpeechInputView) getLayoutInflater().inflate(R.layout.voice_ime_view, null, false);
        return mInputView;
    }

    /**
     * We check the type of editor control and if we probably cannot handle it (e.g. dates)
     * or do not want to (e.g. passwords) then we hand the editing over to another keyboard.
     * TODO: handle inputType = 0
     */
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        String type = "UNKNOWN";

        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
                type = "NUMBER";
                break;
            case InputType.TYPE_CLASS_DATETIME:
                type = "DATETIME";
                switchIme(false);
                break;
            case InputType.TYPE_CLASS_PHONE:
                type = "PHONE";
                break;
            case InputType.TYPE_CLASS_TEXT:
                int variation = attribute.inputType & InputType.TYPE_MASK_VARIATION;
                type = "TEXT/";
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                    // We refuse to recognize passwords for privacy reasons.
                    type += "PASSWORD || VISIBLE_PASSWORD";
                    switchIme(false);
                } else if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                    type += "EMAIL_ADDRESS";
                } else if (variation == InputType.TYPE_TEXT_VARIATION_URI) {
                    // URI bar of Chrome and Firefox, can also handle search queries, thus supported
                    type += "URI";
                } else if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
                    // List filtering? Used in the Dialer search bar, thus supported
                    type += "FILTER";
                } else {
                    type += variation;
                }

                // This is used in the standard search bar (e.g. in Google Play).
                if ((attribute.inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                    type += "FLAG_AUTO_COMPLETE";
                }
                break;

            default:
        }
        Log.i("onStartInput: " + type + ", " + attribute.inputType + ", " + attribute.imeOptions + ", " + restarting);
    }

    /**
     * Note that when editing a HTML page, then switching between form fields might fail to call
     * this method with restarting=false, we thus always update the editor info (incl. inputType).
     */
    @Override
    public void onStartInputView(EditorInfo editorInfo, boolean restarting) {
        super.onStartInputView(editorInfo, restarting);
        Log.i("onStartInputView: " + editorInfo.inputType + "/" + editorInfo.imeOptions + "/" + restarting);

        ((InputConnectionCommandEditor) mCommandEditor).setInputConnection(getCurrentInputConnection());
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        mRes = getResources();
        mInputView.init(R.array.keysIme, new CallerInfo(makeExtras(mPrefs, mRes), editorInfo, getPackageName()));

        // TODO: update this less often (in onStart)
        closeSession();

        if (restarting) {
            return;
        }

        mInputView.setListener(getSpeechInputViewListener(editorInfo.packageName));
        mShowPartialResults = PreferenceUtils.getPrefBoolean(mPrefs, mRes, R.string.keyImeShowPartialResults, R.bool.defaultImeShowPartialResults);

        // Launch recognition immediately (if set so)
        if (PreferenceUtils.getPrefBoolean(mPrefs, mRes, R.string.keyImeAutoStart, R.bool.defaultImeAutoStart)) {
            Log.i("Auto-starting");
            mInputView.start();
        }
    }

    /**
     * Called when the input view is being hidden from the user.
     * This will be called either prior to hiding the window,
     * or prior to switching to another target for editing.
     *
     * @param finishingInput If true, onFinishInput() will be called immediately after.
     */
    @Override
    public void onFinishInputView(boolean finishingInput) {
        // TODO: maybe do not call super
        super.onFinishInputView(finishingInput);
        Log.i("onFinishInputView: " + finishingInput);
        if (!finishingInput) {
            closeSession();
        }
    }


    /**
     * Called to inform the input method that text input has finished in the last editor.
     * At this point there may be a call to onStartInput(EditorInfo, boolean) to perform input in a new editor,
     * or the input method may be left idle.
     * This method is not called when input restarts in the same editor.
     */
    @Override
    public void onFinishInput() {
        // TODO: maybe do not call super
        super.onFinishInput();
        Log.i("onFinishInput");
        closeSession();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
        Log.i("onCurrentInputMethodSubtypeChanged: " + subtype);
        closeSession();
    }

    private void closeSession() {
        if (mInputView != null) {
            mInputView.cancel();
        }
    }

    private IBinder getToken() {
        final Dialog dialog = getWindow();
        if (dialog == null) {
            return null;
        }
        final Window window = dialog.getWindow();
        if (window == null) {
            return null;
        }
        return window.getAttributes().token;
    }

    /**
     * Switching to another IME (ideally the previous one). Either when the user tries to edit
     * a password, or explicitly presses the "switch IME" button.
     * TODO: not sure it is the best way to do it
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void switchIme(boolean isAskUser) {
        closeSession();
        if (isAskUser) {
            mInputMethodManager.showInputMethodPicker();
        } else {
            final IBinder token = getToken();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    mInputMethodManager.switchToNextInputMethod(getToken(), false /* not onlyCurrentIme */);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    mInputMethodManager.switchToLastInputMethod(token);
                } else {
                    mInputMethodManager.showInputMethodPicker();
                }
            } catch (NoSuchMethodError e) {
                Log.e("IME switch failed", e);
            }
        }
    }

    private static String getText(List<String> results) {
        if (results.size() > 0) {
            return results.get(0);
        }
        return "";
    }

    private static Bundle makeExtras(SharedPreferences prefs, Resources res) {
        Bundle extras = new Bundle();
        boolean isUnlimitedDuration = !PreferenceUtils.getPrefBoolean(prefs, res,
                R.string.keyImeAutoStopAfterPause, R.bool.defaultImeAutoStopAfterPause);
        extras.putBoolean(Extras.EXTRA_UNLIMITED_DURATION, isUnlimitedDuration);
        extras.putBoolean(Extras.EXTRA_DICTATION_MODE, isUnlimitedDuration);
        return extras;
    }

    private SpeechInputView.SpeechInputViewListener getSpeechInputViewListener(final String packageName) {
        return new AbstractSpeechInputViewListener() {

            @Override
            public void onComboChange(String language, ComponentName service) {
                // TODO: quick hack to add app to the matcher, not sure if we can access the
                // class name of the app
                ComponentName app = new ComponentName(packageName, packageName);
                // TODO: name of the rewrites table configurable
                mCommandEditor.setUtteranceRewriter(Utils.getUtteranceRewriter(mPrefs, mRes, null, language, service, app));
            }

            @Override
            public void onPartialResult(List<String> results) {
                if (mShowPartialResults) {
                    mCommandEditor.commitPartialResult(getText(results));
                }
            }

            @Override
            public void onFinalResult(List<String> results, Bundle bundle) {
                CommandEditorResult editorResult = mCommandEditor.commitFinalResult(getText(results));
                if (editorResult != null && mInputView != null && editorResult.isCommand()) {
                    mInputView.showMessage(editorResult.ppCommand(), editorResult.isSuccess());
                }
            }

            @Override
            public void onSwitchIme(boolean isAskUser) {
                switchIme(isAskUser);
            }

            @Override
            public void onSearch() {
                closeSession();
                mCommandEditor.runOp(mCommandEditor.imeActionSearch());
                requestHideSelf(0);
            }

            @Override
            public void onDeleteLastWord() {
                mCommandEditor.runOp(mCommandEditor.deleteLeftWord());
            }

            @Override
            public void onAddNewline() {
                mCommandEditor.runOp(mCommandEditor.replaceSel("\n"));
            }

            @Override
            public void goUp() {
                mCommandEditor.runOp(mCommandEditor.keyUp());
            }

            @Override
            public void goDown() {
                mCommandEditor.runOp(mCommandEditor.keyDown());
            }

            @Override
            public void onAddSpace() {
                mCommandEditor.runOp(mCommandEditor.replaceSel(" "));
            }

            @Override
            public void onSelectAll() {
                // TODO: show ContextMenu
                mCommandEditor.runOp(mCommandEditor.selectAll());
            }

            @Override
            public void onReset() {
                // TODO: hide ContextMenu (if visible)
                mCommandEditor.runOp(mCommandEditor.moveRel(0));
            }

            @Override
            public void onStartListening() {
                Log.i("IME: onStartListening");
                mCommandEditor.reset();
            }

            @Override
            public void onStopListening() {
                Log.i("IME: onStopListening");
            }

            // TODO: add onCancel()

            @Override
            public void onError(int errorCode) {
                Log.i("IME: onError: " + errorCode);
                if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Intent intent = new Intent(SpeechInputMethodService.this, PermissionsRequesterActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    SpeechInputMethodService.this.startActivity(intent);
                }
            }
        };
    }
}