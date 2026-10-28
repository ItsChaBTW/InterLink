package com.interlink.app;

import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.interlink.app.model.PeerDevice;
import com.interlink.app.model.SessionState;
import com.interlink.app.service.IntercomService;
import com.interlink.app.ui.SessionViewModel;

import java.util.List;
import java.util.Locale;

/**
 * Screen 3: Active Intercom Screen designed for riders with motorcycle mounts and gloves.
 */
public class SessionActivity extends AppCompatActivity {
    private static final String TAG = "SessionActivity";

    private SessionViewModel viewModel;

    private TextView tvSessionStatus;
    private View viewStatusDot;
    private TextView tvPartnerName;
    private TextView tvSessionTimer;

    // Telemetry
    private TextView tvMetricLatency;
    private TextView tvMetricJitter;
    private TextView tvMetricLoss;

    // Audio Route
    private ImageView imgRouteIcon;
    private TextView tvActiveAudioRoute;
    private Button btnChangeRoute;

    // Giant Mute Button
    private FrameLayout btnMuteToggle;
    private ImageView iconMicState;
    private TextView tvMicStateLabel;
    private TextView tvMicSublabel;
    private TextView tvPartnerMuteIndicator;

    private Button btnEndIntercom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session);

        viewModel = new ViewModelProvider(this).get(SessionViewModel.class);

        initViews();
        observeViewModel();
    }

    private void initViews() {
        tvSessionStatus = findViewById(R.id.tv_session_status);
        viewStatusDot = findViewById(R.id.view_status_dot);
        tvPartnerName = findViewById(R.id.tv_partner_name);
        tvSessionTimer = findViewById(R.id.tv_session_timer);

        tvMetricLatency = findViewById(R.id.tv_metric_latency);
        tvMetricJitter = findViewById(R.id.tv_metric_jitter);
        tvMetricLoss = findViewById(R.id.tv_metric_loss);

        imgRouteIcon = findViewById(R.id.img_route_icon);
        tvActiveAudioRoute = findViewById(R.id.tv_active_audio_route);
        btnChangeRoute = findViewById(R.id.btn_change_route_session);

        btnMuteToggle = findViewById(R.id.btn_mute_toggle);
        iconMicState = findViewById(R.id.icon_mic_state);
        tvMicStateLabel = findViewById(R.id.tv_mic_state_label);
        tvMicSublabel = findViewById(R.id.tv_mic_sublabel);
        tvPartnerMuteIndicator = findViewById(R.id.tv_partner_mute_indicator);

        btnEndIntercom = findViewById(R.id.btn_end_intercom);

        btnMuteToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewModel.toggleMute();
            }
        });

        btnChangeRoute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAudioRouteDialog();
            }
        });

        btnEndIntercom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endIntercomAndExit();
            }
        });
    }

    private void observeViewModel() {
        viewModel.getSessionState().observe(this, new Observer<SessionState>() {
            @Override
            public void onChanged(SessionState state) {
                if (state == SessionState.CONNECTED) {
                    tvSessionStatus.setText(R.string.session_active_title);
                    tvSessionStatus.setTextColor(ContextCompat.getColor(SessionActivity.this, R.color.primary_teal));
                    viewStatusDot.setBackgroundTintList(ContextCompat.getColorStateList(SessionActivity.this, R.color.status_connected));
                } else if (state == SessionState.RECONNECTING) {
                    tvSessionStatus.setText(R.string.status_reconnecting);
                    tvSessionStatus.setTextColor(ContextCompat.getColor(SessionActivity.this, R.color.status_connecting));
                    viewStatusDot.setBackgroundTintList(ContextCompat.getColorStateList(SessionActivity.this, R.color.status_connecting));
                } else if (state == SessionState.DISCONNECTED || state == SessionState.IDLE || state == SessionState.ERROR) {
                    Toast.makeText(SessionActivity.this, "Intercom session ended", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });

        viewModel.getPeerDevice().observe(this, new Observer<PeerDevice>() {
            @Override
            public void onChanged(PeerDevice peer) {
                if (peer != null) {
                    tvPartnerName.setText(peer.getDeviceName());
                    tvMetricLatency.setText(String.format(Locale.US, "%d ms", peer.getRoundTripTimeMs()));
                    tvMetricJitter.setText(String.format(Locale.US, "%d ms", peer.getJitterMs()));
                    tvMetricLoss.setText(String.format(Locale.US, "%.1f%%", peer.getPacketLossPercent()));

                    if (peer.isMuted()) {
                        tvPartnerMuteIndicator.setText("Partner mic: MUTED");
                        tvPartnerMuteIndicator.setTextColor(ContextCompat.getColor(SessionActivity.this, R.color.status_connecting));
                    } else {
                        tvPartnerMuteIndicator.setText("Partner mic: Live");
                        tvPartnerMuteIndicator.setTextColor(ContextCompat.getColor(SessionActivity.this, R.color.text_secondary));
                    }
                }
            }
        });

        viewModel.getSessionDurationFormatted().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String duration) {
                tvSessionTimer.setText(duration);
            }
        });

        viewModel.getAudioRouteName().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String routeName) {
                tvActiveAudioRoute.setText(routeName);
            }
        });

        viewModel.getIsBluetoothActive().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isBt) {
                imgRouteIcon.setImageResource(isBt ? R.drawable.ic_bluetooth : R.drawable.ic_headset);
            }
        });

        viewModel.getIsMuted().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isMuted) {
                updateMuteUi(isMuted);
            }
        });
    }

    private void updateMuteUi(boolean isMuted) {
        if (isMuted) {
            btnMuteToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.mic_muted_bg));
            iconMicState.setImageResource(R.drawable.ic_mic_off);
            tvMicStateLabel.setText("MIC MUTED");
            tvMicSublabel.setText("TAP TO TALK");
        } else {
            btnMuteToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.mic_active_bg));
            iconMicState.setImageResource(R.drawable.ic_mic);
            tvMicStateLabel.setText("MIC LIVE");
            tvMicSublabel.setText("TAP TO MUTE");
        }
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
                .setTitle("Select Audio Route")
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

    private void endIntercomAndExit() {
        viewModel.endSession();
        Intent stopServiceIntent = new Intent(this, IntercomService.class);
        stopServiceIntent.setAction(IntercomService.ACTION_STOP_FOREGROUND);
        startService(stopServiceIntent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Motorcycle safety: confirm exit so rider doesn't accidentally disconnect
        new AlertDialog.Builder(this)
                .setTitle("End Intercom Session?")
                .setMessage("Are you sure you want to disconnect from your riding partner?")
                .setPositiveButton("End Session", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        endIntercomAndExit();
                    }
                })
                .setNegativeButton("Keep Connected", null)
                .show();
    }
}

