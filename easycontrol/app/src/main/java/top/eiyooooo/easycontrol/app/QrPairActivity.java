package top.eiyooooo.easycontrol.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.concurrent.atomic.AtomicBoolean;

import top.eiyooooo.easycontrol.app.adb.AdbPairManager;
import top.eiyooooo.easycontrol.app.databinding.ActivityQrPairBinding;
import top.eiyooooo.easycontrol.app.helper.PublicTools;

/**
 * 无线调试二维码配对：
 * 扫描被控端"使用二维码配对设备"生成的二维码（WIFI:T:ADB;S:名称;P:密码;;），
 * 通过 mDNS 发现配对服务并完成 SPAKE2 配对，随后自动打开 5555 端口。
 */
public class QrPairActivity extends Activity {
  private static final String PAIRING_TYPE = "_adb-tls-pairing._tcp";
  private static final String CONNECT_TYPE = "_adb-tls-connect._tcp";
  private static final long TIMEOUT_MILLIS = 15000;
  public static final int RESULT_CODE = 1001;

  private ActivityQrPairBinding qrBinding;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final AtomicBoolean resolved = new AtomicBoolean(false);
  private NsdManager nsdManager;
  private NsdManager.DiscoveryListener discoveryListener;
  private Runnable timeoutRunnable;
  private volatile boolean cancelled = false;
  private String serviceName;
  private String servicePassword;
  private String serial;
  private String lastHost;

  @SuppressLint("MissingPermission")
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    PublicTools.setStatusAndNavBar(this);
    PublicTools.setLocale(this);
    qrBinding = ActivityQrPairBinding.inflate(this.getLayoutInflater());
    setContentView(qrBinding.getRoot());
    qrBinding.backButton.setOnClickListener(v -> {
      cancelled = true;
      finish();
    });
    qrBinding.retry.setOnClickListener(v -> startScan());
    // zxing 要求 Android 7.0+；被控端二维码配对功能需要 Android 11+
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      failed(getString(R.string.qr_pair_version_tip));
      return;
    }
    startScan();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    cancelled = true;
    cancelTimeout();
    stopDiscovery();
  }

  private void startScan() {
    cancelled = false;
    resolved.set(false);
    qrBinding.retry.setVisibility(View.GONE);
    setStatus(getString(R.string.qr_pair_status_scan));
    IntentIntegrator integrator = new IntentIntegrator(this);
    integrator.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
    integrator.setPrompt(getString(R.string.qr_pair_scan_hint));
    integrator.setBeepEnabled(false);
    integrator.setOrientationLocked(true);
    integrator.initiateScan();
  }

  @SuppressLint("MissingPermission")
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (cancelled) return;
    IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
    String content = result == null ? null : result.getContents();
    if (content == null) {
      // 用户取消扫码，返回配对页
      finish();
      return;
    }
    if (!parseQr(content)) {
      failed(getString(R.string.qr_pair_content_invalid));
      return;
    }
    findService(PAIRING_TYPE, this::doPair, () -> failed(getString(R.string.qr_pair_timeout)));
  }

  // 解析 WIFI:T:ADB;S:<名称>;P:<密码>;;
  private boolean parseQr(String content) {
    String name = substringBetween(content, "S:", ";");
    String password = substringBetween(content, "P:", ";");
    if (name == null || name.isEmpty() || password == null || password.isEmpty() || !content.contains("T:ADB")) return false;
    serviceName = name;
    servicePassword = password;
    // 服务名格式 adb-<serial>-<random>
    String[] parts = name.split("-");
    serial = parts.length > 1 ? parts[1] : name;
    return true;
  }

  private String substringBetween(String s, String prefix, String suffix) {
    int i = s.indexOf(prefix);
    if (i < 0) return null;
    int j = s.indexOf(suffix, i + prefix.length());
    if (j < 0) return null;
    return s.substring(i + prefix.length(), j);
  }

  // mDNS 发现指定类型服务，实例名匹配后回调（host:port）
  private interface OnResolved {
    void onResolved(String host, int port);
  }

  @SuppressLint("MissingPermission")
  private void findService(String type, OnResolved onResolved, Runnable onTimeout) {
    resolved.set(false);
    nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
    cancelTimeout();
    timeoutRunnable = onTimeout;
    handler.postDelayed(onTimeout, TIMEOUT_MILLIS);
    discoveryListener = new NsdManager.DiscoveryListener() {
      @Override
      public void onStartDiscoveryFailed(String type, int errorCode) {
        onTimeout.run();
      }

      @Override
      public void onStopDiscoveryFailed(String type, int errorCode) {
      }

      @Override
      public void onDiscoveryStarted(String type) {
      }

      @Override
      public void onDiscoveryStopped(String type) {
      }

      @Override
      public void onServiceLost(NsdServiceInfo serviceInfo) {
      }

      @Override
      public void onServiceFound(NsdServiceInfo serviceInfo) {
        if (cancelled || resolved.get()) return;
        String name = serviceInfo.getServiceName();
        boolean matched = type.equals(CONNECT_TYPE) ? name.contains(serial) : name.equals(serviceName);
        if (!matched) return;
        tryResolve(serviceInfo, onResolved, 0);
      }
    };
    try {
      nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    } catch (Exception e) {
      onTimeout.run();
    }
  }

  @SuppressLint("MissingPermission")
  private void tryResolve(NsdServiceInfo serviceInfo, OnResolved onResolved, int attempt) {
    if (cancelled || resolved.get() || attempt >= 3) return;
    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
      @Override
      public void onResolveFailed(NsdServiceInfo info, int errorCode) {
        // resolve 偶发失败，稍后重试
        handler.postDelayed(() -> tryResolve(serviceInfo, onResolved, attempt + 1), 1000);
      }

      @Override
      public void onServiceResolved(NsdServiceInfo info) {
        if (cancelled || resolved.get()) return;
        resolved.set(true);
        cancelTimeout();
        stopDiscovery();
        String host = info.getHost() == null ? null : info.getHost().getHostAddress();
        if (host == null) {
          resolved.set(false);
          return;
        }
        onResolved.onResolved(host, info.getPort());
      }
    });
  }

  private void doPair(String host, int pairingPort) {
    lastHost = host;
    setStatus(getString(R.string.qr_pair_status_pairing));
    new Thread(() -> {
      try {
        if (AdbPairManager.INSTANCE == null) AdbPairManager.init();
        AdbPairManager.INSTANCE.pair(host, pairingPort, servicePassword);
      } catch (Exception e) {
        if (!cancelled) failed(getString(R.string.qr_pair_failed, e.getMessage() == null ? "" : e.getMessage()));
        return;
      }
      if (cancelled) return;
      runOnUiThread(() -> findService(CONNECT_TYPE, this::doOpenPort, this::finishWithPairingOnly));
    }).start();
  }

  private void doOpenPort(String host, int debugPort) {
    lastHost = host;
    setStatus(getString(R.string.qr_pair_status_connecting));
    new Thread(() -> {
      boolean success = false;
      try {
        AdbPairManager.INSTANCE.connect(host, debugPort);
        AdbPairManager.INSTANCE.openStream("tcpip:5555");
        AdbPairManager.INSTANCE.disconnect();
        success = true;
      } catch (Exception ignored) {
      }
      boolean finalSuccess = success;
      runOnUiThread(() -> {
        if (finalSuccess) finishWithSuccess(host);
        else finishWithPairingOnly();
      });
    }).start();
  }

  // 配对成功但打开端口失败：回填 IP，提示用户手动打开端口
  private void finishWithPairingOnly() {
    if (lastHost != null) {
      Intent intent = new Intent();
      intent.putExtra("ip", lastHost);
      setResult(RESULT_OK, intent);
    }
    failed(getString(R.string.qr_pair_open_port_error));
  }

  private void finishWithSuccess(String host) {
    Intent intent = new Intent();
    intent.putExtra("ip", host);
    intent.putExtra("debug_port", 5555);
    setResult(RESULT_OK, intent);
    setStatus(getString(R.string.qr_pair_success));
    handler.postDelayed(this::finish, 1500);
  }

  private void failed(String message) {
    cancelTimeout();
    stopDiscovery();
    qrBinding.qrStatus.setText(message);
    qrBinding.retry.setVisibility(View.VISIBLE);
  }

  private void setStatus(String text) {
    qrBinding.qrStatus.setText(text);
    qrBinding.retry.setVisibility(View.GONE);
  }

  private void cancelTimeout() {
    if (timeoutRunnable != null) {
      handler.removeCallbacks(timeoutRunnable);
      timeoutRunnable = null;
    }
  }

  @SuppressLint("MissingPermission")
  private void stopDiscovery() {
    if (nsdManager != null && discoveryListener != null) {
      try {
        nsdManager.stopServiceDiscovery(discoveryListener);
      } catch (Exception ignored) {
      }
      discoveryListener = null;
    }
  }
}
