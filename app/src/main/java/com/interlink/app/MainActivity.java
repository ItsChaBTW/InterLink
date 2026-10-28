package com.interlink.app;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.interlink.app.model.SessionState;
import com.interlink.app.network.HostDiscovery;
import com.interlink.app.network.PacketProtocol;
import com.interlink.app.security.SessionSecurity;
import com.interlink.app.service.IntercomService;
import com.interlink.app.ui.SessionViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen 1 (Home) & Screen 2 (Pairing & Connection setup for Host and Guest).
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private SessionViewModel viewModel;

    // UI Panels
    private View panelHomeActions;
    private View panelHost;
    private View panelGuest;

    // Header & Readiness Views
    private TextView tvNetworkStatus;
    private TextView tvDetectedIp;
    private TextView tvHeadsetStatus;
    private ImageView iconWifiStatus;
    private ImageView iconHeadsetStatus;
    private Button btnAudioRouteSelect;

    // Host Views
    private TextView tvHostIpDisplay;
    private TextView tvPairingCodeDisplay;
    private TextView tvHostWaitingStatus;
    private Button btnCancelHost;

    // Guest Views
    private EditText etHostIp;
    private EditText etPairingCode;
    private TextView tvGuestStatusError;
    private Button btnConnectToHost;
    private Button btnCancelGuest;
    private LinearLayout layoutDiscoveredHosts;
    private LinearLayout containerDiscoveredHosts;

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(SessionViewModel.class);

        initViews();
        setupPermissionLauncher();
        observeViewModel();
        refreshDeviceReadiness();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDeviceReadiness();
        // If already connected, transition directly to active intercom
        if (viewModel.getSessionState().getValue() == SessionState.CONNECTED) {
            launchSessionActivity();
        }
    }

    private void initViews() {
        panelHomeActions = findViewById(R.id.panel_home_actions);
        panelHost = findViewById(R.id.panel_host);
        panelGuest = findViewById(R.id.panel_guest);

        tvNetworkStatus = findViewById(R.id.tv_network_status);
        tvDetectedIp = findViewById(R.id.tv_detected_ip);
        tvHeadsetStatus = findViewById(R.id.tv_headset_status);
        iconWifiStatus = findViewById(R.id.icon_wifi_status);
        iconHeadsetStatus = findViewById(R.id.icon_headset_status);
        btnAudioRouteSelect = findViewById(R.id.btn_audio_route_select);

        // Host
        tvHostIpDisplay = findViewById(R.id.tv_host_ip_display);
        tvPairingCodeDisplay = findViewById(R.id.tv_pairing_code_display);
        tvHostWaitingStatus = findViewById(R.id.tv_host_waiting_status);
        btnCancelHost = findViewById(R.id.btn_cancel_host);

        // Guest
        etHostIp = findViewById(R.id.et_host_ip);
        etPairingCode = findViewById(R.id.et_pairing_code);
        tvGuestStatusError = findViewById(R.id.tv_guest_status_error);
        btnConnectToHost = findViewById(R.id.btn_connect_to_host);
        btnCancelGuest = findViewById(R.id.btn_cancel_guest);
        layoutDiscoveredHosts = findViewById(R.id.layout_discovered_hosts);
        containerDiscoveredHosts = findViewById(R.id.container_discovered_hosts);

        // Buttons
        findViewById(R.id.btn_host_session).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPermissionsAndRun(new Runnable() {
                    @Override
                    public void run() {
                        startHostMode();
                    }
                });
            }
        });

        findViewById(R.id.btn_guest_session).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPermissionsAndRun(new Runnable() {
                    @Override
                    public void run() {
                        startGuestMode();
                    }
                });
            }
        });

        btnCancelHost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewModel.endSession();
                showHomePanel();
            }
        });

        btnCancelGuest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewModel.endSession();
                viewModel.stopHostDiscovery();
                showHomePanel();
            }
        });

        btnConnectToHost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performGuestConnect();
            }
        });

        btnAudioRouteSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAudioRouteDialog();
            }
        });
    }

    private void observeViewModel() {
        viewModel.getSessionState().observe(this, new Observer<SessionState>() {
            @Override
            public void onChanged(SessionState state) {
                if (state == SessionState.CONNECTED) {
                    launchSessionActivity();
                } else if (state == SessionState.HOSTING) {
                    tvHostWaitingStatus.setText(R.string.status_waiting_for_guest);
                } else if (state == SessionState.ERROR) {
                    tvGuestStatusError.setVisibility(View.VISIBLE);
                    tvGuestStatusError.setText(viewModel.getStatusMessage().getValue());
                }
            }
        });

        viewModel.getAudioRouteName().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String name) {
                tvHeadsetStatus.setText(name);
            }
        });

        viewModel.getIsBluetoothActive().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isBt) {
                iconHeadsetStatus.setImageResource(isBt ? R.drawable.ic_bluetooth : R.drawable.ic_headset);
            }
        });

        viewModel.getDiscoveredHosts().observe(this, new Observer<List<SessionViewModel.DiscoveredHost>>() {
            @Override
            public void onChanged(List<SessionViewModel.DiscoveredHost> hosts) {
                updateDiscoveredHostsUi(hosts);
            }
        });
    }

    private void refreshDeviceReadiness() {
        String localIp = HostDiscovery.getLocalIpAddress();
        tvDetectedIp.setText(localIp);

        boolean isLocalNet = !localIp.equals("127.0.0.1");
        if (isLocalNet) {
            tvNetworkStatus.setText(R.string.status_ready_hotspot);
            iconWifiStatus.setColorFilter(ContextCompat.getColor(this, R.color.primary_teal));
        } else {
            tvNetworkStatus.setText(R.string.status_no_network);
            iconWifiStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_error));
        }
    }

    private void startHostMode() {
        panelHomeActions.setVisibility(View.GONE);
        panelGuest.setVisibility(View.GONE);
        panelHost.setVisibility(View.VISIBLE);

        String localIp = HostDiscovery.getLocalIpAddress();
        tvHostIpDisplay.setText(localIp);

        String pin = SessionSecurity.generatePairingPin();
        // Format with spaces for high visibility
        tvPairingCodeDisplay.setText(pin.charAt(0) + " " + pin.charAt(1) + " " + pin.charAt(2) + " " +
                pin.charAt(3) + " " + pin.charAt(4) + " " + pin.charAt(5));

        startIntercomForegroundService();
        viewModel.startHosting(pin);
    }

    private void startGuestMode() {
        panelHomeActions.setVisibility(View.GONE);
        panelHost.setVisibility(View.GONE);
        panelGuest.setVisibility(View.VISIBLE);
        tvGuestStatusError.setVisibility(View.GONE);

        HostDiscovery discovery = new HostDiscovery(this);
        String gateway = discovery.getGatewayIpAddress();
        if (gateway != null && !gateway.isEmpty()) {
            etHostIp.setText(gateway);
        }

        viewModel.startHostDiscovery();
    }

    private void performGuestConnect() {
        String hostIp = etHostIp.getText().toString().trim();
        String pin = etPairingCode.getText().toString().trim();

        if (hostIp.isEmpty()) {
            tvGuestStatusError.setText("Please enter the Host IP address");
            tvGuestStatusError.setVisibility(View.VISIBLE);
            return;
        }

        if (pin.length() != 6) {
            tvGuestStatusError.setText("Please enter a valid 6-digit PIN");
            tvGuestStatusError.setVisibility(View.VISIBLE);
            return;
        }

        tvGuestStatusError.setVisibility(View.GONE);
        startIntercomForegroundService();
        viewModel.joinSession(hostIp, PacketProtocol.DEFAULT_CONTROL_PORT, pin);
    }

    private void updateDiscoveredHostsUi(final List<SessionViewModel.DiscoveredHost> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            layoutDiscoveredHosts.setVisibility(View.GONE);
            containerDiscoveredHosts.removeAllViews();
            return;
        }

        layoutDiscoveredHosts.setVisibility(View.VISIBLE);
        containerDiscoveredHosts.removeAllViews();

        for (final SessionViewModel.DiscoveredHost host : hosts) {
            View itemView = LayoutInflater.from(this).inflate(R.layout.item_audio_device, containerDiscoveredHosts, false);
            ImageView icon = itemView.findViewById(R.id.img_device_type);
            TextView name = itemView.findViewById(R.id.tv_device_name);
            TextView desc = itemView.findViewById(R.id.tv_device_type_desc);
            RadioButton rb = itemView.findViewById(R.id.rb_device_selected);

            icon.setImageResource(R.drawable.ic_wifi);
            name.setText(host.name);
            desc.setText("Host IP: " + host.ip);
            rb.setVisibility(View.GONE);

            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etHostIp.setText(host.ip);
                    etPairingCode.requestFocus();
                    Toast.makeText(MainActivity.this, "Selected " + host.name, Toast.LENGTH_SHORT).show();
                }
            });

            containerDiscoveredHosts.addView(itemView);
        }
    }

    private void showHomePanel() {
        panelHost.setVisibility(View.GONE);
        panelGuest.setVisibility(View.GONE);
        panelHomeActions.setVisibility(View.VISIBLE);
    }

    private void launchSessionActivity() {
        Intent intent = new Intent(this, SessionActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void startIntercomForegroundService() {
        Intent serviceIntent = new Intent(this, IntercomService.class);
        serviceIntent.setAction(IntercomService.ACTION_START_FOREGROUND);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void showAudioRouteDialog() {
        final List<AudioDeviceInfo> devices = viewModel.getAvailableCommunicationDevices();
        if (devices.isEmpty()) {
            Toast.makeText(this, "No external audio devices found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] deviceNames = new String[devices.size() + 1];
        deviceNames[0] = "Default System Speaker / Earpiece";
        for (int i = 0; i < devices.size(); i++) {
            deviceNames[i + 1] = com.interlink.app.audio.BluetoothAudioManager.getDeviceDisplayName(devices.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Helmet / Audio Device")
                .setItems(deviceNames, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            viewModel.selectCommunicationDevice(null);
                        } else {
                            viewModel.selectCommunicationDevice(devices.get(which - 1));
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setupPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                new androidx.activity.result.ActivityResultCallback<java.util.Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(java.util.Map<String, Boolean> result) {
                        Boolean micGranted = result.get(Manifest.permission.RECORD_AUDIO);
                        if (micGranted == null || !micGranted) {
                            Toast.makeText(MainActivity.this, R.string.err_perm_record_audio, Toast.LENGTH_LONG).show();
                        }
                    }
                }
        );
    }

    private void checkPermissionsAndRun(Runnable onGranted) {
        List<String> neededPermissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!neededPermissions.isEmpty()) {
            permissionLauncher.launch(neededPermissions.toArray(new String[0]));
        } else {
            onGranted.run();
        }
    }
}
