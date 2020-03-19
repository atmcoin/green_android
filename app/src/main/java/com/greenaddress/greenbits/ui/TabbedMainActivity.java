package com.greenaddress.greenbits.ui;

import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.afollestad.materialdialogs.MaterialDialog;
import com.coinsquare.AtmDeposit;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.greenaddress.gdk.GDKTwoFactorCall;
import com.greenaddress.greenapi.data.BalanceData;
import com.greenaddress.greenapi.model.ActiveAccountObservable;
import com.greenaddress.greenapi.model.EventDataObservable;
import com.greenaddress.greenbits.ui.authentication.FirstScreenActivity;
import com.greenaddress.greenbits.ui.authentication.RequestLoginActivity;
import com.greenaddress.greenbits.ui.notifications.NotificationsFragment;
import com.greenaddress.greenbits.ui.preferences.GeneralPreferenceFragment;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;
import com.greenaddress.greenbits.ui.preferences.ResetActivePreferenceFragment;
import com.greenaddress.greenbits.ui.preferences.WatchOnlyPreferenceFragment;
import com.greenaddress.greenbits.ui.send.ScanActivity;
import com.greenaddress.greenbits.ui.send.SendAmountActivity;
import com.greenaddress.greenbits.ui.transactions.MainFragment;

import java.util.Arrays;
import java.util.Observable;
import java.util.Observer;

import static com.greenaddress.gdk.GDKSession.getSession;

// Problem with the above is that in the horizontal orientation the tabs don't go in the top bar
public class TabbedMainActivity extends LoggedActivity implements Observer,
    BottomNavigationView.OnNavigationItemSelectedListener  {

    private static final String TAG = TabbedMainActivity.class.getSimpleName();

    public static final int
        REQUEST_BITCOIN_URL_LOGIN = 1,
        REQUEST_TX_DETAILS = 2,
        REQUEST_BITCOIN_URL_SEND = 3,
        REQUEST_SELECT_ASSET = 4;
    private ViewPager mViewPager;
    private BottomNavigationView mNavigation;
    private MaterialDialog mSubaccountDialog;
    private boolean mIsBitcoinUri = false;

    static boolean isBitcoinScheme(final Intent intent) {
        final Uri uri = intent.getData();
        return uri != null && uri.getScheme() != null && uri.getScheme().equals("bitcoin");
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (modelIsNullOrDisconnected())
            return;
        UI.preventScreenshots(this);
        final Intent intent = getIntent();
        mIsBitcoinUri = isBitcoinScheme(intent) ||
                        intent.hasCategory(Intent.CATEGORY_BROWSABLE) ||
                        NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction());

        if (mIsBitcoinUri && !getConnectionManager().isLoggingInOrMore()) {
            // Not logged in, force the user to login
            final Intent login = new Intent(this, RequestLoginActivity.class);
            startActivityForResult(login, REQUEST_BITCOIN_URL_LOGIN);
            return;
        }
        launch();
        final boolean isResetActive = getModel().isTwoFAReset();
        if (mIsBitcoinUri && !isResetActive) {
            // If logged in, open send activity
            onBitcoinUri();
        }

        // check available preferred exchange rate
        try {
            final BalanceData balanceData = getSession().convertBalance(0);
            Double.parseDouble(balanceData.getFiat());
        } catch (final Exception e) {
            UI.popup(this, R.string.id_your_favourite_exchange_rate_is).show();
        }

        if (AtmDeposit.getInstance().getAddress() != null) {
            String amount = AtmDeposit.getInstance().getAmount();
            String usd = AtmDeposit.getInstance().getAmountUsd();
            final MaterialDialog dialog;
            MaterialDialog.Builder builder = UI.popup(this, "Getting cash at an ATM")
                    .positiveText("Proceed")
                    .content("Awaiting " + amount + " BTC to withdraw " + usd + " USD, do you want to proceed?")
                    .backgroundColor(getResources().getColor(R.color.buttonJungleGreen))
                    .onPositive((dlg, which) -> {
                        startActivity(new Intent(this, ScanActivity.class));
                    });

            dialog = builder.build();
            dialog.show();
        }
    }

    private void onBitcoinUri() {

        Uri uri = null;
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction()))
            uri = getIntent().getData();
        else {
            final Parcelable[] rawMessages;
            rawMessages = getIntent().getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            for (final Parcelable parcel : rawMessages) {
                final NdefMessage ndefMsg = (NdefMessage) parcel;
                for (final NdefRecord record : ndefMsg.getRecords())
                    if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN &&
                        Arrays.equals(record.getType(), NdefRecord.RTD_URI))
                        uri = record.toUri();
            }
        }
        if (uri == null)
            return;

        final Intent intent = new Intent(this, SendAmountActivity.class);
        final String text = uri.toString();
        try {
            final int subaccount = getModel().getCurrentSubaccount();
            final GDKTwoFactorCall call = getSession().createTransactionFromUri(null, text, subaccount);
            final ObjectNode transactionFromUri = call.resolve(null, getConnectionManager().getHWResolver());
            final String error = transactionFromUri.get("error").asText();
            if ("id_invalid_address".equals(error)) {
                UI.toast(this, R.string.id_invalid_address, Toast.LENGTH_SHORT);
                return;
            }
            removeUtxosIfTooBig(transactionFromUri);
            intent.putExtra(PrefKeys.INTENT_STRING_TX, transactionFromUri.toString());
        } catch (final Exception e) {
            e.printStackTrace();
            if (e.getMessage() != null)
                UI.toast(this, e.getMessage(), Toast.LENGTH_SHORT);
            return;
        }
        intent.putExtra("internal_qr", getIntent().getBooleanExtra("internal_qr", false));
        startActivityForResult(intent, REQUEST_BITCOIN_URL_SEND);
    }

    private void launch() {

        setContentView(R.layout.activity_tabbed_main);
        final Toolbar toolbar = UI.find(this, R.id.toolbar);
        setSupportActionBar(toolbar);
        setTitleWithNetwork(R.string.id_wallets);

        // Set up the action bar.
        final SectionsPagerAdapter sectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

        // Set up the ViewPager with the sections adapter.
        mViewPager = UI.find(this, R.id.container);

        // Keep all of our tabs in memory while paging. This helps any races
        // left where broadcasts/callbacks are called on the pager when its not
        // shown.
        mViewPager.setOffscreenPageLimit(3);

        // Set up the BottomNavigationView and connect to ViewPager
        mNavigation = findViewById(R.id.navigation);
        mNavigation.setOnNavigationItemSelectedListener(this);
        mNavigation.getMenu().findItem(R.id.navigation_home).setChecked(true);
        mNavigation.setItemIconTintList(null);  // allows colored icons
        mViewPager = findViewById(R.id.container);
        // set adapter and tabs only after all setTag in ViewPager container
        mViewPager.setAdapter(sectionsPagerAdapter);
        mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(final int index) {
                sectionsPagerAdapter.onViewPageSelected(index);
            }
        });
        mViewPager.setCurrentItem(1);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isFinishing())
            return;
        getModel().getActiveAccountObservable().addObserver(this);
        getModel().getEventDataObservable().addObserver(this);

        final SectionsPagerAdapter adapter = getPagerAdapter();

        if (adapter == null && !mIsBitcoinUri) {
            // FIXME: Should pass flag to activity so it shows it was forced logged out
            startActivity(new Intent(this, FirstScreenActivity.class));
            finish();
            return;
        }

        updateBottomNavigationView();
        invalidateOptionsMenu();
    }

    private void updateBottomNavigationView() {
        final MenuItem item = mNavigation.getMenu().findItem(R.id.navigation_notifications);
        runOnUiThread(() ->
                      item.setIcon(getModel() != null && getModel().getEventDataObservable().hasEvents() ?
                                   R.drawable.bottom_navigation_notifications_2 :
                                   R.drawable.bottom_navigation_notifications)
                      );
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isFinishing())
            return;
        mSubaccountDialog = UI.dismiss(this, mSubaccountDialog);
        getModel().getActiveAccountObservable().deleteObserver(this);
        getModel().getEventDataObservable().deleteObserver(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
        case REQUEST_BITCOIN_URL_SEND:
            mIsBitcoinUri = false;
            launch();
            onUpdateActiveAccount();
            break;
        case REQUEST_BITCOIN_URL_LOGIN:
            if (resultCode != RESULT_OK) {
                // The user failed to login after clicking on a bitcoin Uri
                finish();
                return;
            }
            mIsBitcoinUri = true;
            launch();
            break;
        case REQUEST_TX_DETAILS:
            break;
        }
    }


    @Override
    public void onBackPressed() {
        this.moveTaskToBack(true);
    }

    @Override
    public void update(final Observable observable, final Object data) {
        super.update(observable,data);
        if (observable instanceof ActiveAccountObservable)
            onUpdateActiveAccount();
        else if (observable instanceof EventDataObservable) {
            updateBottomNavigationView();
        } else {
            invalidateOptionsMenu();
        }
    }

    public void onUpdateActiveAccount() {
        getPagerAdapter().notifyDataSetChanged();
    }

    public SectionsPagerAdapter getPagerAdapter() {
        if (mViewPager == null)
            return null;
        return (SectionsPagerAdapter) mViewPager.getAdapter();
    }

    /**
     * A {@link FragmentPagerAdapter} that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    public class SectionsPagerAdapter extends FragmentStatePagerAdapter {
        private final Fragment[] mFragments = new Fragment[3];
        int mSelectedPage = -1;
        private int mInitialSelectedPage = -1;
        private boolean mInitialPage = true;

        SectionsPagerAdapter(final FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(final int index) {
            Log.d(TAG, "SectionsPagerAdapter -> getItem " + index);
            final Fragment preferenceFragment;
            if (getModel().isTwoFAReset())
                preferenceFragment = new ResetActivePreferenceFragment();
            else if (getConnectionManager().isWatchOnly())
                preferenceFragment = new WatchOnlyPreferenceFragment();
            else
                preferenceFragment = new GeneralPreferenceFragment();

            final Fragment centerFragment = new MainFragment();

            switch (index) {
            case 0: return preferenceFragment;
            case 1: return centerFragment;
            case 2: return new NotificationsFragment();
            }
            return null;
        }

        @Override
        public Object instantiateItem(final ViewGroup container, final int index) {
            Log.d(TAG, "SectionsPagerAdapter -> instantiateItem " + index);

            mFragments[index] = (Fragment) super.instantiateItem(container, index);

            if (mInitialPage && index == mInitialSelectedPage) {
                // Call setPageSelected() on the first page, now that it is created
                Log.d(TAG, "SectionsPagerAdapter -> selecting first page " + index);
                mInitialSelectedPage = -1;
                mInitialPage = false;
            }
            return mFragments[index];
        }

        @Override
        public void destroyItem(final ViewGroup container, final int index, final Object object) {
            Log.d(TAG, "SectionsPagerAdapter -> destroyItem " + index);
            if (index >= 0 && index <= 2 && mFragments[index] != null) {
                // Make sure the fragment is not kept alive and does not
                // try to process any callbacks it registered for.
                mFragments[index].onPause();
                mFragments[index] = null;
            }
            super.destroyItem(container, index, object);
        }

        @Override
        public int getCount() {
            return mFragments.length;
        }

        @Override
        public CharSequence getPageTitle(final int index) {
            if (getModel().isTwoFAReset())
                return " " + getString(R.string.id_wallets);
            final String networkName = getNetwork().getName();
            switch (index) {
            case 0: return " " + getString(R.string.id_settings);
            case 1: return " " + networkName + " " + getString(R.string.id_wallets);
            case 2: return " " + getString(R.string.id_notifications);
            }
            return null;
        }

        void onViewPageSelected(final int index) {
            Log.d(TAG, "SectionsPagerAdapter -> onViewPageSelected " + index +
                  " current is " + mSelectedPage + " initial " + mInitialPage);

            if (mInitialPage)
                mInitialSelectedPage = index; // Store so we can notify it when constructed

            if (index == mSelectedPage)
                return; // No change to the selected page

            mNavigation.getMenu().getItem(index).setChecked(true);
            mNavigation.setSelectedItemId(index);
            if (index != 1)
                getSupportActionBar().show();
            else
                getSupportActionBar().hide();
            getSupportActionBar().setTitle(getPageTitle(index));
            invalidateOptionsMenu();
        }

        public void onOptionsItemSelected(final MenuItem item) {}
    }

    @Override
    public boolean onNavigationItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case R.id.navigation_settings:
            mViewPager.setCurrentItem(0);
            return true;
        case R.id.navigation_home:
            mViewPager.setCurrentItem(1);
            return true;
        case R.id.atm:
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://secure.just.cash/wac/map"));
            startActivity(browserIntent);
            return true;
        case R.id.navigation_notifications:
            mViewPager.setCurrentItem(2);
            return true;
        }
        return false;
    }
}
