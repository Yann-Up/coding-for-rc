package com.kimirc.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class LoginStateViewer {

    private final Activity activity;
    private final LayoutInflater inflater;
    private final List<LoginStateEntry> entries = new ArrayList<>();
    private final LinearLayout loginStateList;
    private final LinearLayout detailSection;
    private final ScrollView loginStateScroll;
    private final TextView countView;
    private final TextView emptyView;
    private final TextView valuesButton;
    private final TextView refreshButton;
    private final ImageView detailArrow;

    private AlertDialog dialog;
    private boolean valuesVisible;
    private boolean expanded;

    private LoginStateViewer(Activity activity, String accountName) {
        this.activity = activity;
        inflater = activity.getLayoutInflater();
        View content = inflater.inflate(R.layout.dialog_login_state, null);
        loginStateList = content.findViewById(R.id.login_state_list);
        loginStateScroll = content.findViewById(R.id.login_state_scroll);
        detailSection = content.findViewById(R.id.login_state_detail_section);
        countView = content.findViewById(R.id.login_state_count);
        emptyView = content.findViewById(R.id.login_state_empty);
        valuesButton = content.findViewById(R.id.login_state_values_button);
        refreshButton = content.findViewById(R.id.login_state_refresh_button);
        detailArrow = content.findViewById(R.id.login_state_detail_arrow);

        TextView hostView = content.findViewById(R.id.login_state_host);
        String host = Uri.parse(WebActivity.REMOTE_URL_PREFIX).getHost();
        hostView.setText(host);

        content.findViewById(R.id.login_state_detail_row).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleDetail();
                    }
                });
        valuesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLoginState(!valuesVisible);
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLoginState(false);
            }
        });

        dialog = new AlertDialog.Builder(activity, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(activity.getString(R.string.login_state_title_with_account, accountName))
                .setView(content)
                .setNegativeButton(R.string.login_state_close, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                clearContent();
                dialog = null;
            }
        });
    }

    public static LoginStateViewer show(Activity activity, String accountName) {
        LoginStateViewer viewer = new LoginStateViewer(activity, accountName);
        viewer.showDialog();
        return viewer;
    }

    private void showDialog() {
        loadLoginState(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(activity.getColor(R.color.text_secondary));
    }

    private void toggleDetail() {
        expanded = !expanded;
        detailSection.setVisibility(expanded ? View.VISIBLE : View.GONE);
        detailArrow.setRotation(expanded ? 90f : 0f);
    }

    private void loadLoginState(boolean revealValues) {
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
                entries.add(new LoginStateEntry(name, revealValues ? value : null));
            }
        }

        countView.setText(String.valueOf(entries.size()));
        loginStateScroll.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        valuesButton.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        for (LoginStateEntry entry : entries) {
            addEntryView(entry);
        }
        updateValuesButton();
    }

    private void addEntryView(LoginStateEntry entry) {
        View itemView = inflater.inflate(R.layout.item_login_state, loginStateList, false);
        TextView nameView = itemView.findViewById(R.id.login_state_name);
        TextView valueView = itemView.findViewById(R.id.login_state_value);
        nameView.setText(entry.name);
        valueView.setText(valuesVisible ? entry.value : activity.getString(R.string.login_state_masked_value));
        loginStateList.addView(itemView);
    }

    private void updateValuesButton() {
        valuesButton.setText(valuesVisible
                ? R.string.login_state_hide_values : R.string.login_state_show_values);
    }

    private void clearContent() {
        valuesVisible = false;
        entries.clear();
        loginStateList.removeAllViews();
        loginStateScroll.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        countView.setText("0");
        updateValuesButton();
    }

    public void dismiss() {
        clearContent();
        AlertDialog current = dialog;
        if (current != null && current.isShowing()) {
            current.dismiss();
        } else {
            dialog = null;
        }
    }

    private static final class LoginStateEntry {
        final String name;
        final String value;

        LoginStateEntry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
