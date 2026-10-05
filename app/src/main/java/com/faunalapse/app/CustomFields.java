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
 * remove button. Text and numbers are typed; dates and times open Android's own pickers, so they
 * are always written the same way (2026-10-05, 14:30).
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
            list.add(new FieldInfo.Custom(c.name, c.type, value.getText().toString().trim()));
        }
        return list;
    }

    private void addRow(FieldInfo.Custom c) {
        final View row = activity.getLayoutInflater().inflate(R.layout.custom_field, rows, false);
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
            value.addTextChangedListener(new TextWatcher() { // a wrong number shows at once
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String text = s.toString().trim();
                    value.setError(text.isEmpty() || FieldInfo.Custom.number(text) != null ? null
                            : activity.getString(R.string.custom_number_needed));
                }
            });
        } else if (FieldInfo.Custom.TEXT.equals(c.type)) {
            value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            value.setFilters(new InputFilter[] {new InputFilter.LengthFilter(FieldInfo.MAX_CUSTOM_TEXT)});
            value.setHint(activity.getString(R.string.custom_hint_text, FieldInfo.MAX_CUSTOM_TEXT));
        } else { // date or time: chosen in a picker, not typed
            value.setFocusable(false);
            value.setHint(FieldInfo.Custom.DATE.equals(c.type) ? R.string.custom_hint_date : R.string.custom_hint_time);
            value.setOnClickListener(v -> pick(value, c.type));
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

    /** A date or time picker, starting at the value (or now); "Clear" empties the field. */
    private void pick(final EditText value, String type) {
        Calendar now = Calendar.getInstance();
        String v = value.getText().toString();
        AlertDialog dialog;
        if (FieldInfo.Custom.DATE.equals(type)) {
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
        } else {
            int h = now.get(Calendar.HOUR_OF_DAY);
            int min = now.get(Calendar.MINUTE);
            if (v.matches("\\d{2}:\\d{2}")) {
                h = Integer.parseInt(v.substring(0, 2));
                min = Integer.parseInt(v.substring(3, 5));
            }
            // 24-hour clock, as in the record and the schedule
            dialog = new TimePickerDialog(activity, (picker, hour, minute) ->
                    value.setText(String.format(Locale.US, "%02d:%02d", hour, minute)), h, min, true);
        }
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, activity.getString(R.string.custom_clear),
                (d, w) -> value.setText(""));
        dialog.show();
    }

    /** "Add a field": a name and a type; the dialog stays open while the name is not usable. */
    private void askForField() {
        View form = activity.getLayoutInflater().inflate(R.layout.custom_field_new, rows, false);
        final EditText name = form.findViewById(R.id.custom_new_name);
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(FieldInfo.MAX_NAME)});
        final RadioGroup type = form.findViewById(R.id.custom_new_type);
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
                return;
            }
            int id = type.getCheckedRadioButtonId();
            RadioButton checked = form.findViewById(id);
            addRow(new FieldInfo.Custom(n, (String) checked.getTag(), ""));
            dialog.dismiss();
        });
    }
}
