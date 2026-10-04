package com.faunalapse.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Explanations behind a small (i) with an arrow: a label with the tag "help" (style Label.Help)
 * opens and closes the next view with the tag "help_text" (style Hint.Help), in screen order. Values
 * that change while the user works (pixels, warnings) stay visible; only reasons and how-to texts
 * fold away, so a screen opens short. Idea from FaunaPulse's setting help; written here for plain
 * Android views.
 */
final class HelpToggles {

    private HelpToggles() {
    }

    /** Wires every "help" label under {@code root} to its "help_text"; all start closed. */
    static void wire(View root) {
        wire(root, new TextView[1]);
    }

    private static void wire(View v, TextView[] label) {
        Object tag = v.getTag();
        if ("help".equals(tag)) {
            label[0] = (TextView) v;
        } else if ("help_text".equals(tag) && label[0] != null) {
            attach(label[0], v);
            label[0] = null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) wire(g.getChildAt(i), label);
        }
    }

    private static void attach(final TextView label, final View text) {
        final CharSequence words = label.getText();
        text.setVisibility(View.GONE);
        show(label, words, false);
        label.setOnClickListener(v -> {
            boolean open = text.getVisibility() != View.VISIBLE;
            text.setVisibility(open ? View.VISIBLE : View.GONE);
            show(label, words, open);
        });
    }

    /** The label's words with the icon after them (it wraps with the text on narrow screens). */
    private static void show(TextView label, CharSequence words, boolean open) {
        Context c = label.getContext();
        Drawable d = c.getDrawable(open ? R.drawable.ic_help_less : R.drawable.ic_help_more);
        int h = Math.round(label.getTextSize() * 1.1f);
        d.setBounds(0, 0, h * d.getIntrinsicWidth() / d.getIntrinsicHeight(), h);
        SpannableStringBuilder s = new SpannableStringBuilder(words).append("  ");
        s.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), s.length() - 1, s.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setText(s);
        label.setContentDescription(words + ". " + c.getString(open ? R.string.help_hide : R.string.help_show));
    }
}
