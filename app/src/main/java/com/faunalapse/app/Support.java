package com.faunalapse.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

/**
 * "Support FaunaLapse" on the main screen: share the app, how to cite it, report a problem. Idea
 * from FaunaPulse's support box (maintainer); written here for plain Android views. FaunaLapse has
 * no network access: these open other apps (Android's share window, the browser), and the user
 * sees what is sent before sending it.
 */
final class Support {

    static final String REPO_URL = "https://github.com/valentinitnelav/fauna-lapse";
    /** A web form for reports without a GitHub account (Tally or Google Forms, maintainer's choice,
     *  2026-10-11); empty until it exists, and then its button stays hidden. */
    static final String FORM_URL = "";

    private Support() {
    }

    /** Android's share window with a short text and the link: works with every app installed. */
    static void share(Activity a) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, a.getString(R.string.app_name))
                .putExtra(Intent.EXTRA_TEXT, a.getString(R.string.support_share_text, REPO_URL));
        open(a, Intent.createChooser(send, a.getString(R.string.support_share)), REPO_URL);
    }

    /** The citation in the app (it works without internet), with Copy. */
    static void cite(final Activity a) {
        final String citation = a.getString(R.string.support_citation, version(a), REPO_URL);
        new AlertDialog.Builder(a)
                .setTitle(R.string.support_cite)
                .setMessage(a.getString(R.string.two_paragraphs, citation, a.getString(R.string.support_cite_note)))
                .setPositiveButton(R.string.support_copy, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(a.getString(R.string.support_cite), citation));
                    // Android 13+ confirms a copy itself.
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(a, R.string.support_copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.site_photo_close, null)
                .show();
    }

    /** Where a report goes (the web form, once there is one, and GitHub), with this phone's details
     *  filled in; the browser opens it, and nothing is sent before the user sends it there. */
    static void report(final Activity a) {
        final String details = a.getString(R.string.support_details, version(a), Build.MANUFACTURER, Build.MODEL,
                Build.VERSION.RELEASE);
        final String issues = REPO_URL + "/issues";
        boolean form = !FORM_URL.isEmpty();
        AlertDialog.Builder d = new AlertDialog.Builder(a)
                .setTitle(R.string.support_report)
                .setMessage(form ? a.getString(R.string.support_report_text, details, FORM_URL)
                        : a.getString(R.string.support_report_text_github, details, issues))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.support_report_github, (dialog, which) -> {
                    Uri issue = Uri.parse(issues + "/new").buildUpon()
                            .appendQueryParameter("body", a.getString(R.string.support_issue_body, details)).build();
                    open(a, new Intent(Intent.ACTION_VIEW, issue), issues);
                });
        if (form) {
            // Hidden fields of the form take these names from the link.
            d.setPositiveButton(R.string.support_report_form, (dialog, which) -> {
                Uri u = Uri.parse(FORM_URL).buildUpon()
                        .appendQueryParameter("app_version", version(a))
                        .appendQueryParameter("phone", Build.MANUFACTURER + " " + Build.MODEL)
                        .appendQueryParameter("android", Build.VERSION.RELEASE).build();
                open(a, new Intent(Intent.ACTION_VIEW, u), FORM_URL);
            });
        }
        d.show();
    }

    /** FaunaLapse's version name, for example 0.1.0-alpha.1. */
    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Opens another app; without one (a phone without a browser), shows the address instead. */
    private static void open(Activity a, Intent intent, String address) {
        try {
            a.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, a.getString(R.string.support_no_app, address), Toast.LENGTH_LONG).show();
        }
    }
}
