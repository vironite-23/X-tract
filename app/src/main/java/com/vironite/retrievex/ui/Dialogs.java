package com.vironite.retrievex.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.vironite.retrievex.R;

/** Reusable dialogs. */
public final class Dialogs {
    public interface TextCallback {
        void onText(String text);
    }

    private Dialogs() {
    }

    public static void textInput(Context c, int titleRes, String initial, String hint, String help, final TextCallback cb) {
        View v = LayoutInflater.from(c).inflate(R.layout.dialog_text_input, null);
        final TextInputEditText edit = v.findViewById(R.id.input_edit);
        TextInputLayout layout = v.findViewById(R.id.input_layout);
        TextView helpView = v.findViewById(R.id.input_help);
        if (hint != null) layout.setHint(hint);
        if (initial != null) {
            edit.setText(initial);
            edit.setSelection(initial.length());
        }
        if (help == null || help.isEmpty()) helpView.setVisibility(View.GONE);
        else helpView.setText(help);
        new MaterialAlertDialogBuilder(c)
                .setTitle(titleRes)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> cb.onText(edit.getText() == null ? "" : edit.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void confirm(Context c, int titleRes, String message, int positiveRes, final Runnable onYes) {
        new MaterialAlertDialogBuilder(c)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(positiveRes, (d, w) -> onYes.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void message(Context c, int titleRes, String message) {
        new MaterialAlertDialogBuilder(c)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
