package com.kimirc.shell;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class CookieViewer {

    private final MainActivity activity;
    private final LayoutInflater inflater;
    private final List<CookieEntry> entries = new ArrayList<>();
    private final LinearLayout cookieList;
    private final ScrollView cookieScroll;
    private final TextView countView;
    private final TextView emptyView;

    private AlertDialog dialog;
    private Button valuesButton;
    private boolean valuesVisible;

    private CookieViewer(MainActivity activity) {
        this.activity = activity;
        inflater = activity.getLayoutInflater();
        View content = inflater.inflate(R.layout.dialog_cookie_list, null);
        cookieList = content.findViewById(R.id.cookie_list);
        cookieScroll = content.findViewById(R.id.cookie_scroll);
        countView = content.findViewById(R.id.cookie_count);
        emptyView = content.findViewById(R.id.cookie_empty);

        TextView hostView = content.findViewById(R.id.cookie_host);
        String host = Uri.parse(WebActivity.REMOTE_URL_PREFIX).getHost();
        hostView.setText(activity.getString(R.string.cookie_query_host, host));

        dialog = new AlertDialog.Builder(activity, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.cookie_title)
                .setView(content)
                .setPositiveButton(R.string.cookie_show_values, null)
                .setNeutralButton(R.string.cookie_refresh, null)
                .setNegativeButton(R.string.cookie_close, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                clearContent();
                dialog = null;
                valuesButton = null;
            }
        });
    }

    public static CookieViewer show(MainActivity activity) {
        CookieViewer viewer = new CookieViewer(activity);
        viewer.showDialog();
        return viewer;
    }

    private void showDialog() {
        loadCookies(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show();

        valuesButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        valuesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadCookies(!valuesVisible);
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadCookies(false);
                    }
                });
        Button closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        closeButton.setTextColor(activity.getColor(R.color.text_secondary));
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private void loadCookies(boolean revealValues) {
        clearContent();
        valuesVisible = revealValues;

        String cookieHeader = CookieManager.getInstance().getCookie(
                WebActivity.REMOTE_URL_PREFIX);
        if (cookieHeader != null) {
            String[] parts = cookieHeader.split(";");
            for (String part : parts) {
                String item = part.trim();
                if (item.isEmpty()) {
                    continue;
                }
                int separator = item.indexOf('=');
                String name = separator >= 0
                        ? item.substring(0, separator).trim() : item;
                String value = separator >= 0 ? item.substring(separator + 1) : "";
                entries.add(new CookieEntry(name, revealValues ? value : null));
            }
        }

        countView.setText(activity.getString(R.string.cookie_entry_count, entries.size()));
        cookieScroll.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (CookieEntry entry : entries) {
            addCookieView(entry);
        }
        updateValuesButton();
    }

    private void addCookieView(CookieEntry entry) {
        View itemView = inflater.inflate(R.layout.item_cookie, cookieList, false);
        TextView nameView = itemView.findViewById(R.id.cookie_name);
        TextView valueView = itemView.findViewById(R.id.cookie_value);
        nameView.setText(entry.name);
        valueView.setText(valuesVisible ? entry.value : activity.getString(R.string.cookie_masked_value));
        cookieList.addView(itemView);
    }

    private void updateValuesButton() {
        if (valuesButton != null) {
            valuesButton.setText(valuesVisible
                    ? R.string.cookie_hide_values : R.string.cookie_show_values);
        }
    }

    private void clearContent() {
        valuesVisible = false;
        entries.clear();
        cookieList.removeAllViews();
        cookieScroll.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        countView.setText(activity.getString(R.string.cookie_entry_count, 0));
        updateValuesButton();
    }

    public void dismiss() {
        clearContent();
        AlertDialog current = dialog;
        if (current != null && current.isShowing()) {
            current.dismiss();
        } else {
            dialog = null;
            valuesButton = null;
        }
    }

    private static final class CookieEntry {
        final String name;
        final String value;

        CookieEntry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
