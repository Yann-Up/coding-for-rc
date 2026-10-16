package com.kimirc.shell;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends ThemedActivity {

    private static final long SPLASH_MIN_MS = 700;
    private static final long SPLASH_FADE_IN_MS = 240;
    private static final long SPLASH_FADE_OUT_MS = 180;

    private boolean unlocked;
    private boolean resumed;
    private boolean splashPending;
    private boolean internalNavigation;
    private boolean switchAuthPending;
    private Runnable pendingAction;
    private DeviceAdapter adapter;
    private TextView deviceCountView;
    private AlertDialog accountDialog;
    private AccountStore.Account activeAccount;
    private View splashOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            unlocked = savedInstanceState.getBoolean("unlocked", false);
        }
        initMainUi();
        if (unlocked) {
            hideSplash();
        } else {
            showSplash();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        internalNavigation = false;
        // 开屏动画期间不弹验证：等动画结束、首页已经就绪再唤醒指纹。
        if (splashPending) {
            return;
        }
        if (!unlocked) {
            authenticate();
        } else if (adapter != null) {
            refresh();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (internalNavigation) {
            internalNavigation = false;
            return;
        }
        unlocked = false;
    }

    private void showSplash() {
        splashOverlay = findViewById(R.id.splash_overlay);
        View content = findViewById(R.id.splash_content);
        content.setAlpha(0f);
        content.animate()
                .alpha(1f)
                .setDuration(SPLASH_FADE_IN_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        splashPending = true;
        splashOverlay.postDelayed(splashDone, SPLASH_MIN_MS);
    }

    private final Runnable splashDone = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            splashPending = false;
            splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(SPLASH_FADE_OUT_MS)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            hideSplash();
                            if (resumed && !unlocked) {
                                authenticate();
                            }
                        }
                    })
                    .start();
        }
    };

    private void hideSplash() {
        if (splashOverlay != null) {
            splashOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        if (splashOverlay != null) {
            splashOverlay.removeCallbacks(splashDone);
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (accountDialog != null) {
            accountDialog.dismiss();
            accountDialog = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("unlocked", unlocked);
    }

    private void authenticate() {
        pendingAction = null;
        switchAuthPending = false;
        startAuthentication();
    }

    private void authenticateThen(Runnable action) {
        pendingAction = action;
        switchAuthPending = true;
        startAuthentication();
    }

    private void startAuthentication() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isDeviceSecure()) {
            showBiometricPrompt();
            return;
        }
        if (switchAuthPending) {
            switchAuthPending = false;
            runPendingAction();
        } else {
            onUnlocked();
            showNoLockscreenHint();
        }
    }

    @SuppressLint("MissingPermission")
    private void showBiometricPrompt() {
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.lock_title))
                .setDescription(getString(R.string.lock_desc));
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            builder.setDeviceCredentialAllowed(true);
        }
        builder.build().authenticate(new CancellationSignal(), getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        if (switchAuthPending) {
                            switchAuthPending = false;
                            runPendingAction();
                        } else {
                            onUnlocked();
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        switchAuthPending = false;
                        pendingAction = null;
                        if (!unlocked) {
                            finish();
                        }
                    }
                });
    }

    private void runPendingAction() {
        Runnable action = pendingAction;
        pendingAction = null;
        if (action != null) {
            action.run();
        }
    }

    private void onUnlocked() {
        unlocked = true;
        if (adapter == null) {
            initMainUi();
        } else {
            refresh();
        }
    }

    private void showNoLockscreenHint() {
        new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.lock_title)
                .setMessage(R.string.lock_hint)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void initMainUi() {
        setContentView(R.layout.activity_main);
        ListView listView = findViewById(R.id.device_list);
        View emptyView = findViewById(R.id.empty_view);
        deviceCountView = findViewById(R.id.device_count);
        View addButton = findViewById(R.id.add_button);

        activeAccount = AccountStore.active(this);
        adapter = new DeviceAdapter(this);
        listView.setEmptyView(emptyView);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                DeviceStore.Device device = adapter.getItem(position);
                internalNavigation = true;
                WebActivity.start(MainActivity.this, activeAccount.slot, activeAccount.id,
                        activeAccount.name, device.name, device.url);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(adapter.getItem(position));
                return true;
            }
        });

        findViewById(R.id.account_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAccountDialog();
            }
        });
        findViewById(R.id.settings_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                internalNavigation = true;
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                internalNavigation = true;
                startActivity(AddDeviceActivity.createIntent(MainActivity.this));
            }
        });
        refresh();
    }

    private void refresh() {
        if (activeAccount == null) {
            activeAccount = AccountStore.active(this);
        }
        List<DeviceStore.Device> devices = DeviceStore.load(this, activeAccount.id);
        Map<String, String> infoMap = new HashMap<>();
        for (DeviceStore.Device device : devices) {
            if (!infoMap.containsKey(device.accountId)) {
                infoMap.put(device.accountId, deviceInfo(device.accountId));
            }
        }
        adapter.refresh(devices, infoMap);
        deviceCountView.setText(getString(R.string.device_count, devices.size()));
    }

    private String deviceInfo(String accountId) {
        String nickname = AccountStore.nickname(this, accountId);
        if (!nickname.isEmpty()) {
            return nickname;
        }
        AccountStore.Account account = AccountStore.byId(this, accountId);
        return account != null ? account.name : accountId;
    }

    private void switchAccount(final AccountStore.Account account) {
        if (account.id.equals(activeAccount.id)) {
            return;
        }
        authenticateThen(new Runnable() {
            @Override
            public void run() {
                AccountStore.setActive(MainActivity.this, account.id);
                activeAccount = account;
                refresh();
                showAccountDialog();
            }
        });
    }

    private void showAccountDialog() {
        if (accountDialog != null) {
            accountDialog.dismiss();
            accountDialog = null;
        }
        View content = getLayoutInflater().inflate(R.layout.dialog_account_list, null);
        LinearLayout list = content.findViewById(R.id.account_list);
        for (final AccountStore.Account account : AccountStore.load(this)) {
            View row = getLayoutInflater().inflate(R.layout.item_account, list, false);
            TextView nameView = row.findViewById(R.id.account_name);
            TextView countView = row.findViewById(R.id.account_device_count);
            TextView currentView = row.findViewById(R.id.account_current);
            nameView.setText(account.name);
            countView.setText(getString(R.string.device_count, DeviceStore.count(this, account.id)));
            currentView.setVisibility(
                    account.id.equals(activeAccount.id) ? View.VISIBLE : View.GONE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchAccount(account);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showAccountActions(account);
                    return true;
                }
            });
            list.addView(row);
        }

        accountDialog = new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.account_switch_title)
                .setView(content)
                .setPositiveButton(R.string.account_add, null)
                .setNegativeButton(R.string.account_close, null)
                .create();
        accountDialog.show();
        accountDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(getColor(R.color.text_secondary));
        accountDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCreateAccountDialog();
                    }
                });
    }

    private void showAccountActions(final AccountStore.Account account) {
        new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(account.name)
                .setItems(new CharSequence[]{
                        getString(R.string.account_rename),
                        getString(R.string.account_delete)},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    showRenameAccountDialog(account);
                                } else {
                                    confirmDeleteAccount(account);
                                }
                            }
                        })
                .show();
    }

    private void showCreateAccountDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_account_name, null);
        final EditText nameInput = form.findViewById(R.id.account_name_input);
        final AlertDialog dialog = new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.account_add)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_secondary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String name = nameInput.getText().toString().trim();
                        if (name.isEmpty()) {
                            nameInput.setError(getString(R.string.account_name_required));
                            nameInput.requestFocus();
                            return;
                        }
                        AccountStore.Account created = AccountStore.create(MainActivity.this, name);
                        if (created == null) {
                            nameInput.setError(
                                    getString(R.string.account_limit, WebActivity.SLOT_COUNT));
                            nameInput.requestFocus();
                            return;
                        }
                        AccountStore.setActive(MainActivity.this, created.id);
                        activeAccount = created;
                        refresh();
                        dialog.dismiss();
                        showAccountDialog();
                    }
                });
    }

    private void showRenameAccountDialog(final AccountStore.Account account) {
        View form = getLayoutInflater().inflate(R.layout.dialog_account_name, null);
        final EditText nameInput = form.findViewById(R.id.account_name_input);
        nameInput.setText(account.name);
        nameInput.setSelection(account.name.length());
        final AlertDialog dialog = new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.account_rename_title)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_secondary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String name = nameInput.getText().toString().trim();
                        if (name.isEmpty()) {
                            nameInput.setError(getString(R.string.account_name_required));
                            nameInput.requestFocus();
                            return;
                        }
                        AccountStore.rename(MainActivity.this, account.id, name);
                        if (account.id.equals(activeAccount.id)) {
                            activeAccount = AccountStore.byId(MainActivity.this, account.id);
                            refresh();
                        }
                        dialog.dismiss();
                        showAccountDialog();
                    }
                });
    }

    private void confirmDeleteAccount(final AccountStore.Account account) {
        final AlertDialog dialog = new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.account_delete)
                .setMessage(getString(R.string.account_delete_confirm, account.name))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        AccountStore.remove(MainActivity.this, account.id);
                        DeviceStore.removeAll(MainActivity.this, account.id);
                        if (account.id.equals(activeAccount.id)) {
                            activeAccount = AccountStore.active(MainActivity.this);
                            refresh();
                        }
                        showAccountDialog();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_secondary));
    }

    private void confirmDelete(final DeviceStore.Device device) {
        final AlertDialog dialog = new SecureAlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_confirm, device.name))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        DeviceStore.remove(MainActivity.this, device);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_secondary));
    }

    private static class DeviceAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private List<DeviceStore.Device> devices = new ArrayList<>();
        private Map<String, String> infoMap = new HashMap<>();

        DeviceAdapter(Context context) {
            inflater = LayoutInflater.from(context);
        }

        void refresh(List<DeviceStore.Device> data, Map<String, String> info) {
            devices = new ArrayList<>(data);
            infoMap = new HashMap<>(info);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return devices.size();
        }

        @Override
        public DeviceStore.Device getItem(int position) {
            return devices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_device, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            DeviceStore.Device device = getItem(position);
            holder.nameView.setText(device.name);
            String info = infoMap.get(device.accountId);
            holder.infoView.setText(info != null ? info : device.accountId);
            return convertView;
        }

        private static class ViewHolder {
            final TextView nameView;
            final TextView infoView;

            ViewHolder(View view) {
                nameView = view.findViewById(R.id.device_name);
                infoView = view.findViewById(R.id.device_info);
            }
        }
    }
}
