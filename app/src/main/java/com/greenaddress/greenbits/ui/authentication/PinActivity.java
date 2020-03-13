package com.greenaddress.greenbits.ui.authentication;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.blockstream.libgreenaddress.GDK;
import com.coinsquare.AtmDeposit;
import com.greenaddress.greenapi.ConnectionManager;
import com.greenaddress.greenapi.data.NetworkData;
import com.greenaddress.greenapi.data.PinData;
import com.greenaddress.greenbits.AuthenticationHandler;
import com.greenaddress.greenbits.KeyStoreAES;
import com.greenaddress.greenbits.ui.LoginActivity;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.UI;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

public class PinActivity extends LoginActivity implements PinFragment.OnPinListener {

    private static final int ACTIVITY_REQUEST_CODE = 1;
    private PinFragment mPinFragment;
    private SharedPreferences mPin;

    private void login(final String pin) {

        if (isLoading())
            return;

        if (pin.length() < 4) {
            UI.toast(this, R.string.id_pin_has_to_be_between_4_and_15, Toast.LENGTH_SHORT);
            if (mPinFragment != null)
                mPinFragment.clear();
            return;
        }

        startLoading(getString(R.string.id_logging_in));
        if (mPinFragment != null)
            mPinFragment.setEnabled(false);

        final PinData pinData = PinData.fromPreferenceValues(mPin);
        final ConnectionManager cm = getConnectionManager();
        getGAApp().getExecutor().execute(() -> {
            try {
                cm.disconnect();
                cm.connect(this);
                cm.loginWithPin(pin, pinData);
                onPostLogin();
                runOnUiThread(() -> {
                    stopLoading();
                    mPin.edit().putInt("counter", 0).apply();
                    goToTabbedMainActivity();
                });
            } catch (final Exception e) {
                cm.disconnect();
                runOnUiThread(() -> { onLoginFailure(e); });
            }
        });
    }

    void onLoginFailure(final Exception e) {
        stopLoading();
        final String message;
        final int counter = mPin.getInt("counter", 0) + 1;
        final SharedPreferences.Editor editor = mPin.edit();
        final int code = getCode(e);
        if (getCode(e) == GDK.GA_NOT_AUTHORIZED) {
            if (counter < 3) {
                editor.putInt("counter", counter);
                message = (counter == 2) ? getString(R.string.id_last_attempt_if_failed_you_will) :
                          getString(R.string.id_invalid_pin_you_have_1d, 3 - counter);
            } else {
                message = getString(R.string.id_invalid_pin_you_dont_have_any);
                editor.clear();
            }
            editor.apply();
        } else if (code == GDK.GA_RECONNECT || code == GDK.GA_ERROR) {
            message = getString(R.string.id_connection_failed);
        } else {
            // Should not happen
            message = getString(R.string.id_error);
        }

        runOnUiThread(() -> {
            UI.toast(this, message, Toast.LENGTH_LONG);

            if (counter >= 3) {
                startActivity(new Intent(PinActivity.this, FirstScreenActivity.class));
                finish();
                return;
            }
            stopLoading();
            if (mPinFragment != null) {
                mPinFragment.clear();
                mPinFragment.setEnabled(true);
            }
        });
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AuthenticationHandler.hasPin(this)) {
            startActivity(new Intent(this, FirstScreenActivity.class));
            finish();
            return;
        }

        String action = getIntent().getAction();
        Uri data = getIntent().getData();

        if (action != null && data != null) {
            Log.d("pin", "Deep link detected " + action + " " + data);
            AtmDeposit.getInstance().setIntent(getIntent());
        }

        setContentView(R.layout.activity_pin);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setTitleBackTransparent();

        if (AuthenticationHandler.getNativeAuth(this) != null)
            onNativeAuth();
        else if (AuthenticationHandler.getPinAuth(this) != null)
            onPinAuth();
    }

    private void onNativeAuth() {
        mPin = AuthenticationHandler.getNativeAuth(this);
        tryDecrypt();
    }

    private void onPinAuth() {
        mPin = AuthenticationHandler.getPinAuth(this);
        if (mPin == null) {
            onBackPressed();
            return;
        }
        mPinFragment = new PinFragment();
        final Bundle bundle = new Bundle();
        bundle.putBoolean("is_six_digit", isSixDigit());
        mPinFragment.setArguments(bundle);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == ACTIVITY_REQUEST_CODE) {
            // Challenge completed, proceed with using cipher
            if (resultCode == RESULT_OK) {
                onNativeAuth();
            } else {
                // The user canceled or didn’t complete the lock screen
                // operation. Fallback to manual pin.
                onPinAuth();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setAppNameTitle();

        if (mPinFragment != null)
            getSupportFragmentManager().beginTransaction()
            .replace(R.id.fragment_container, mPinFragment).commit();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            onBackPressed();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onPinInserted(final String pin) {
        login(pin);
    }

    @Override
    public void onPinBackPressed() {
        onBackPressed();
    }

    @Override
    public void onBackPressed() {
        final Intent intent = new Intent(this, FirstScreenActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public boolean isSixDigit() {
        return AuthenticationHandler.getPinAuth(this).getBoolean("is_six_digit", false);
    }


    @TargetApi(Build.VERSION_CODES.M)
    private void tryDecrypt() {
        final NetworkData network = getNetwork();
        final String nativePIN = mPin.getString("native", null);
        final String nativeIV = mPin.getString("nativeiv", null);

        try {
            final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            SecretKey secretKey = (SecretKey) keyStore.getKey(KeyStoreAES.getKeyName(network.getNetwork()), null);
            if (secretKey == null) {
                // support old native authentication
                secretKey = (SecretKey) keyStore.getKey(KeyStoreAES.KEYSTORE_KEY, null);
            }
            final Cipher cipher = getAESCipher();
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(Base64.decode(nativeIV, Base64.NO_WRAP)));
            final byte[] decrypted = cipher.doFinal(Base64.decode(nativePIN, Base64.NO_WRAP));
            final String pin = Base64.encodeToString(decrypted, Base64.NO_WRAP).substring(0, 15);
            login(pin);
        } catch (final KeyStoreException | UserNotAuthenticatedException e) {
            try {
                KeyStoreAES.showAuthenticationScreen(this, network.getName());
            } catch (final Exception exception) {
                UI.popup(this, R.string.id_warning, R.string.id_continue)
                .content(R.string.id_set_up_a_screen_lock_for_your)
                .onAny((dlg, which) -> {
                    AuthenticationHandler.clean(this, AuthenticationHandler.getNativeAuth(this));
                    onPinAuth();
                    onResume();
                }).show();
            }
        } catch (final InvalidAlgorithmParameterException | BadPaddingException | IllegalBlockSizeException |
                 CertificateException | UnrecoverableKeyException | IOException |
                 NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
            UI.popup(this, R.string.id_warning, R.string.id_continue)
            .content(e.getLocalizedMessage())
            .onAny((dlg, which) -> { onPinAuth(); onResume(); })
            .show();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private Cipher getAESCipher() throws NoSuchAlgorithmException, NoSuchPaddingException {
        final String name = KeyProperties.KEY_ALGORITHM_AES + '/' +
                            KeyProperties.BLOCK_MODE_CBC + '/' +
                            KeyProperties.ENCRYPTION_PADDING_PKCS7;
        return Cipher.getInstance(name);
    }
}
