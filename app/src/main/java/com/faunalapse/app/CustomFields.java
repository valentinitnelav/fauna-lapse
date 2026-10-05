package com.faunalapse.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Card 2: the fields the user adds (FieldInfo.Custom), one row each with its name, its value and a
 * remove button. Text, notes and numbers are typed; dates, times, yes or no and list choices are
 * picked in a dialog, so they are always written the same way (2026-10-05, 14:30, yes).
 */
final class CustomFields {

    private final Activity activity;
    private final LinearLayout rows;

    CustomFields(Activity activity, LinearLayout rows, View addButton) {
        this.activity = activity;
        this.rows = rows;
        addButton.setOnClickListener(v -> askForField());
    }

    void show(List<FieldInfo.Custom> fields) {
        rows.removeAllViews();
        for (FieldInfo.Custom c : fields) addRow(c);
    }

    /** The fields on the screen, in their order; FieldInfo.problem checks the values. */
    List<FieldInfo.Custom> read() {
        List<FieldInfo.Custom> list = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            EditText value = (EditText) rows.getChildAt(i).getTag();
            FieldInfo.Custom c = (FieldInfo.Custom) value.getTag();
            list.add(new FieldInfo.Custom(c.name, c.type, value.getText().toString().trim(), c.choices));
        }
        return list;
    }

    private void addRow(final FieldInfo.Custom c) {
        boolean notes = FieldInfo.Custom.NOTES.equals(c.type);
        final View row = activity.getLayoutInflater().inflate(
                notes ? R.layout.custom_field_notes : R.layout.custom_field, rows, false);
        TextView name = row.findViewById(R.id.custom_name);
        name.setText(c.name);
        final EditText value = row.findViewById(R.id.custom_value);
        value.setId(View.generateViewId()); // one ID per row, so the label points to its own field
        name.setLabelFor(value.getId());
        value.setTag(c);
        row.setTag(value);
        value.setText(c.value);
        if (FieldInfo.Custom.NUMBER.equals(c.type)) {
            value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            value.setHint(R.string.custom_hint_number);
            onChange(value, text -> value.setError(text.isEmpty() || FieldInfo.Custom.number(text) != null ? null
                    : activity.getString(R.string.custom_number_needed))); // a wrong number shows at once
        } else if (notes) {
            limit(value, FieldInfo.MAX_NOTES);
            value.setHint(activity.getString(R.string.custom_hint_notes, FieldInfo.MAX_NOTES));
            final TextView count = row.findViewById(R.id.custom_count);
            onChange(value, text -> count.setText(activity.getString(R.string.custom_count,
                    value.length(), FieldInfo.MAX_NOTES)));
        } else if (FieldInfo.Custom.TEXT.equals(c.type)) {
            value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            limit(value, FieldInfo.MAX_CUSTOM_TEXT);
            value.setHint(activity.getString(R.string.custom_hint_text, FieldInfo.MAX_CUSTOM_TEXT));
            // The field takes no more letters at the limit: say why.
            onChange(value, text -> value.setError(value.length() < FieldInfo.MAX_CUSTOM_TEXT ? null
                    : activity.getString(R.string.custom_text_full, FieldInfo.MAX_CUSTOM_TEXT)));
        } else { // picked in a dialog, not typed
            value.setFocusable(false);
            value.setHint(FieldInfo.Custom.DATE.equals(c.type) ? R.string.custom_hint_date
                    : FieldInfo.Custom.TIME.equals(c.type) ? R.string.custom_hint_time : R.string.custom_hint_choose);
            value.setOnClickListener(v -> pick(value, c));
        }
        View remove = row.findViewById(R.id.custom_remove);
        remove.setContentDescription(activity.getString(R.string.custom_remove, c.name));
        remove.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.custom_remove, c.name))
                .setMessage(R.string.custom_remove_text)
                .setPositiveButton(R.string.custom_remove_yes, (d, w) -> rows.removeView(row))
                .setNegativeButton(R.string.custom_remove_no, null)
                .show());
        rows.addView(row);
    }

    private interface OnText {
        void changed(String text);
    }

    /** Calls {@code then} with the trimmed text now and after each change. */
    private static void onChange(EditText field, final OnText then) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                then.changed(s.toString().trim());
            }
        });
        then.changed(field.getText().toString().trim());
    }

    private static void limit(EditText field, int max) {
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(max)});
    }

    /** The dialog of a field that is picked, starting at its value (or now); "Clear" empties it. */
    private void pick(final EditText value, FieldInfo.Custom c) {
        String v = value.getText().toString();
        AlertDialog dialog;
        if (FieldInfo.Custom.DATE.equals(c.type)) {
            Calendar now = Calendar.getInstance();
            int y = now.get(Calendar.YEAR);
            int m = now.get(Calendar.MONTH);
            int d = now.get(Calendar.DAY_OF_MONTH);
            if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                y = Integer.parseInt(v.substring(0, 4));
                m = Integer.parseInt(v.substring(5, 7)) - 1;
                d = Integer.parseInt(v.substring(8, 10));
            }
            dialog = new DatePickerDialog(activity, (picker, year, month, day) ->
                    value.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)), y, m, d);
        } else if (FieldInfo.Custom.TIME.equals(c.type)) {
            Calendar now = Calendar.getInstance();
            int h = now.get(Calendar.HOUR_OF_DAY);
            int min = now.get(Calendar.MINUTE);
            if (v.matches("\\d{2}:\\d{2}")) {
                h = Integer.parseInt(v.substring(0, 2));
                min = Integer.parseInt(v.substring(3, 5));
            }
            // 24-hour clock, as in the record and the schedule
            dialog = new TimePickerDialog(activity, (picker, hour, minute) ->
                    value.setText(String.format(Locale.US, "%02d:%02d", hour, minute)), h, min, true);
        } else { // yes or no, or a choice: the values as saved, the words as shown
            final String[] values = FieldInfo.Custom.YES_NO.equals(c.type)
                    ? new String[] {FieldInfo.Custom.YES, FieldInfo.Custom.NO} : c.choices.toArray(new String[0]);
            String[] words = FieldInfo.Custom.YES_NO.equals(c.type)
                    ? new String[] {activity.getString(R.string.custom_yes), activity.getString(R.string.custom_no)} : values;
            int checked = -1;
            for (int i = 0; i < values.length; i++) if (values[i].equals(v)) checked = i;
            dialog = new AlertDialog.Builder(activity)
                    .setTitle(c.name)
                    .setSingleChoiceItems(words, checked, (d, which) -> {
                        value.setText(values[which]);
                        d.dismiss();
                    })
                    .setNegativeButton(R.string.custom_add_no, null)
                    .create();
        }
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, activity.getString(R.string.custom_clear),
                (d, w) -> value.setText(""));
        dialog.show();
    }

    /** "Add a field": a name, a type and, for a list, the choices; the dialog stays open while
     *  they are not usable. */
    private void askForField() {
        View form = activity.getLayoutInflater().inflate(R.layout.custom_field_new, rows, false);
        final EditText name = form.findViewById(R.id.custom_new_name);
        limit(name, FieldInfo.MAX_NAME);
        final RadioGroup type = form.findViewById(R.id.custom_new_type);
        final View choicesLabel = form.findViewById(R.id.custom_choices_label);
        final EditText choices = form.findViewById(R.id.custom_choices);
        type.setOnCheckedChangeListener((g, id) -> {
            int show = id == R.id.custom_type_choice ? View.VISIBLE : View.GONE;
            choicesLabel.setVisibility(show);
            choices.setVisibility(show);
        });
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.custom_add)
                .setView(form)
                .setPositiveButton(R.string.custom_add_yes, null)
                .setNegativeButton(R.string.custom_add_no, null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String p = FieldInfo.nameProblem(n, read());
            if (p != null) {
                name.setError(p);
                name.requestFocus();
                return;
            }
            RadioButton checked = form.findViewById(type.getCheckedRadioButtonId());
            String t = (String) checked.getTag();
            List<String> list = new ArrayList<>();
            if (FieldInfo.Custom.CHOICE.equals(t)) {
                list = FieldInfo.choiceLines(choices.getText().toString());
                p = FieldInfo.choicesProblem(list);
                if (p != null) {
                    choices.setError(p);
                    choices.requestFocus();
                    return;
                }
            }
            addRow(new FieldInfo.Custom(n, t, "", list));
            dialog.dismiss();
        });
    }
}
