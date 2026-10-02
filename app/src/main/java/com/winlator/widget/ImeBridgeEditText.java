package com.winlator.widget;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

public class ImeBridgeEditText extends AppCompatEditText {
    public interface Listener {
        void onInputConnectionCreated();
        void onComposingText(CharSequence text);
        void onFinishComposingText();
        void onCommitText(CharSequence text);
        void onDeleteSurroundingText(int beforeLength, int afterLength);
        void onSendKeyEvent(KeyEvent event);
        void onEditorAction(int actionCode);
    }

    private Listener listener;
    private boolean composingText;

    public ImeBridgeEditText(@NonNull Context context) {
        super(context);
        init();
    }

    public ImeBridgeEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ImeBridgeEditText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public boolean isComposingText() {
        return composingText;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection base = super.onCreateInputConnection(outAttrs);
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        composingText = false;
        if (listener != null) listener.onInputConnectionCreated();

        return new InputConnectionWrapper(base, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                composingText = false;
                if (listener != null && text != null && text.length() > 0) {
                    listener.onCommitText(text);
                }
                boolean result = super.commitText(text, newCursorPosition);
                Editable editable = getEditableText();
                if (editable != null && editable.length() > 64) {
                    editable.delete(0, editable.length() - 32);
                }
                return result;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                composingText = text != null && text.length() > 0;
                if (listener != null) listener.onComposingText(text);
                return super.setComposingText(text, newCursorPosition);
            }

            @Override
            public boolean finishComposingText() {
                composingText = false;
                if (listener != null) listener.onFinishComposingText();
                return super.finishComposingText();
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (listener != null) {
                    listener.onDeleteSurroundingText(beforeLength, afterLength);
                }
                return composingText ? super.deleteSurroundingText(beforeLength, afterLength) : true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (listener != null && event != null) {
                    listener.onSendKeyEvent(event);
                }
                return super.sendKeyEvent(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                if (listener != null) {
                    listener.onEditorAction(actionCode);
                }
                return super.performEditorAction(actionCode);
            }
        };
    }
}
