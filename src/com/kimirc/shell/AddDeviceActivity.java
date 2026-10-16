package com.kimirc.shell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

public class AddDeviceActivity extends ThemedActivity {

    private static final int REQ_SCAN = 43;

    private EditText nameInput;
    private EditText urlInput;

    public static Intent createIntent(Context context) {
        return new Intent(context, AddDeviceActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_device);

        nameInput = findViewById(R.id.name_input);
        urlInput = findViewById(R.id.url_input);

        String clip = readClipboardLink();
        if (clip != null) {
            urlInput.setText(clip);
            urlInput.setSelection(clip.length());
        }

        findViewById(R.id.back_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.scan_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(AddDeviceActivity.this, ScanActivity.class),
                        REQ_SCAN);
            }
        });
        findViewById(R.id.save_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
            if (text != null) {
                String url = text.trim();
                urlInput.setText(url);
                urlInput.setSelection(url.length());
            }
        }
    }

    private void save() {
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
        String accountId = AccountStore.active(this).id;
        DeviceStore.add(this, new DeviceStore.Device(accountId, name, url));
        finish();
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
}
