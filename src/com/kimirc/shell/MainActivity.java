package com.kimirc.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_CONFIRM_CREDENTIAL = 41;
    private static final int REQ_SCAN = 43;

    private static String pendingScanUrl;

    private boolean unlocked;
    private DeviceAdapter adapter;
    private EditText urlInputField;
    private TextView deviceCountView;
    private CookieViewer cookieViewer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (savedInstanceState != null) {
            unlocked = savedInstanceState.getBoolean("unlocked", false);
        }
        if (unlocked) {
            initMainUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!unlocked) {
            authenticate();
        } else if (adapter != null) {
            refreshDevices();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        unlocked = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (cookieViewer != null) {
            cookieViewer.dismiss();
            cookieViewer = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("unlocked", unlocked);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONFIRM_CREDENTIAL) {
            if (resultCode == RESULT_OK) {
                onUnlocked();
            } else {
                finish();
            }
        } else if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
            if (text != null) {
                pendingScanUrl = text.trim();
            }
            consumePendingScanUrl();
        }
    }

    private void authenticate() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 28) {
            if (keyguard != null && keyguard.isDeviceSecure()) {
                showBiometricPrompt();
                return;
            }
        } else {
            Intent intent = keyguard != null
                    ? keyguard.createConfirmDeviceCredentialIntent(
                            getString(R.string.lock_title), getString(R.string.lock_desc))
                    : null;
            if (intent != null) {
                startActivityForResult(intent, REQ_CONFIRM_CREDENTIAL);
                return;
            }
        }
        onUnlocked();
        showNoLockscreenHint();
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
                        onUnlocked();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        finish();
                    }
                });
    }

    private void onUnlocked() {
        unlocked = true;
        if (adapter == null) {
            initMainUi();
        }
        consumePendingScanUrl();
    }

    private void consumePendingScanUrl() {
        if (pendingScanUrl == null) {
            return;
        }
        String url = pendingScanUrl;
        pendingScanUrl = null;
        if (urlInputField != null) {
            urlInputField.setText(url);
            urlInputField.setSelection(url.length());
        } else {
            showAddDialog(url);
        }
    }

    private void showNoLockscreenHint() {
        new AlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
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
        Button addButton = findViewById(R.id.add_button);

        adapter = new DeviceAdapter(this);
        listView.setEmptyView(emptyView);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                DeviceStore.Device device = adapter.getItem(position);
                Intent intent = new Intent(MainActivity.this, WebActivity.class);
                intent.putExtra(WebActivity.EXTRA_NAME, device.name);
                intent.putExtra(WebActivity.EXTRA_URL, device.url);
                startActivity(intent);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(adapter.getItem(position));
                return true;
            }
        });

        findViewById(R.id.cookie_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cookieViewer != null) {
                    cookieViewer.dismiss();
                }
                cookieViewer = CookieViewer.show(MainActivity.this);
            }
        });
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
        refreshDevices();
    }

    private void refreshDevices() {
        List<DeviceStore.Device> devices = DeviceStore.load(this);
        adapter.refresh(devices);
        deviceCountView.setText(getString(R.string.device_count, devices.size()));
    }

    private void confirmDelete(final DeviceStore.Device device) {
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_confirm, device.name))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        DeviceStore.remove(MainActivity.this, device);
                        refreshDevices();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_secondary));
    }

    private void showAddDialog() {
        showAddDialog(null);
    }

    private void showAddDialog(String presetUrl) {
        View form = getLayoutInflater().inflate(R.layout.dialog_add_device, null);
        final EditText nameInput = form.findViewById(R.id.name_input);
        final EditText urlInput = form.findViewById(R.id.url_input);
        if (presetUrl != null) {
            urlInput.setText(presetUrl);
        } else {
            String clip = readClipboardLink();
            if (clip != null) {
                urlInput.setText(clip);
            }
        }
        urlInputField = urlInput;

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_KimiRC_Dialog_Alert)
                .setTitle(R.string.add_device)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNeutralButton(R.string.scan_add, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (urlInputField == urlInput) {
                    urlInputField = null;
                }
            }
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_secondary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String name = nameInput.getText().toString().trim();
                        String url = urlInput.getText().toString().trim();
                        nameInput.setError(null);
                        urlInput.setError(null);
                        if (name.isEmpty()) {
                            nameInput.setError(getString(R.string.name_required));
                            nameInput.requestFocus();
                            return;
                        }
                        if (!url.startsWith(WebActivity.REMOTE_URL_PREFIX)) {
                            urlInput.setError(getString(R.string.url_invalid));
                            urlInput.requestFocus();
                            return;
                        }
                        DeviceStore.add(MainActivity.this, new DeviceStore.Device(name, url));
                        refreshDevices();
                        dialog.dismiss();
                    }
                });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivityForResult(new Intent(MainActivity.this, ScanActivity.class),
                                REQ_SCAN);
                    }
                });
    }

    private String readClipboardLink() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return null;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null) {
            return null;
        }
        String value = text.toString().trim();
        return value.startsWith(WebActivity.REMOTE_URL_PREFIX) ? value : null;
    }

    private static class DeviceAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private List<DeviceStore.Device> devices = new ArrayList<>();

        DeviceAdapter(Context context) {
            inflater = LayoutInflater.from(context);
        }

        void refresh(List<DeviceStore.Device> data) {
            devices = new ArrayList<>(data);
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
            holder.urlView.setText(device.url);
            return convertView;
        }

        private static class ViewHolder {
            final TextView nameView;
            final TextView urlView;

            ViewHolder(View view) {
                nameView = view.findViewById(R.id.device_name);
                urlView = view.findViewById(R.id.device_url);
            }
        }
    }
}
