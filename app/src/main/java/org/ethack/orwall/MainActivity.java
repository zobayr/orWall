package org.ethack.orwall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.ethack.orwall.adapter.RowAdapter;
import org.ethack.orwall.iptables.InitializeIptables;
import org.ethack.orwall.lib.InstallScripts;
import org.ethack.orwall.lib.PackageComparator;
import org.ethack.orwall.lib.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    public final static String PREFERENCE = "org.ethack.orwall_preferences";
    public final static String PREF_KEY_SIP_APP = "sip_app";
    public final static String PREF_KEY_SIP_ENABLED = "sip_enabled";
    public final static String PREF_KEY_SPEC_BROWSER = "browser_app";
    public final static String PREF_KEY_BROWSER_ENABLED = "browser_enabled";
    public final static String PREF_KEY_TETHER_ENABLED = "enable_tethering";
    public final static String PREF_KEY_IS_TETHER_ENAVLED = "is_tether_enabled";
    private InitializeIptables initializeIptables;
    private PackageManager packageManager;
    private List<PackageInfo> finalList;
    private CountDownTimer timer;

    private ListView listview;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        initializeIptables = new InitializeIptables(this);

        Shell shell = new Shell();

        if (!shell.checkSu()) {
            Log.e(MainActivity.class.getName(), "Unable to get root shell, exiting.");
            AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setMessage("Seems you do not have root access on this device");
            alert.setNeutralButton("Dismiss", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
            alert.show();
        } else {

            ApplicationInfo orbot_id = null;
            packageManager = getPackageManager();

            try {
                orbot_id = packageManager.getApplicationInfo("org.torproject.android", PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(BootBroadcast.class.getName(), "Unable to get Orbot APK info - is it installed?");
                AlertDialog.Builder alert = new AlertDialog.Builder(this);
                alert.setMessage("You must have Orbot installed!");
                alert.setNeutralButton("Dismiss", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                });
                alert.show();
            }

            if (orbot_id != null) {

                InstallScripts installScripts = new InstallScripts(this);
                installScripts.run();
                // install the initscript — there is a check in the function in order to avoid useless writes.;
                boolean enforceInit = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getBoolean("enforce_init_script", true);
                boolean disableInit = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getBoolean("deactivate_init_script", false);

                if (enforceInit) {
                    Log.d("Main", "Enforcing or installing init-script");
                    initializeIptables.installInitScript(this);
                }
                if (disableInit && !enforceInit) {
                    Log.d("Main", "Disabling init-script");
                    initializeIptables.removeIniScript();
                }

                List<PackageInfo> packageList = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS);
                finalList = new ArrayList<PackageInfo>();

                for (PackageInfo applicationInfo : packageList) {
                    String[] permissions = applicationInfo.requestedPermissions;
                    if (permissions != null) {
                        for (String perm : permissions) {
                            if (perm.equals("android.permission.INTERNET")) {
                                finalList.add(applicationInfo);
                                break;
                            }
                        }
                    }
                }

                Collections.sort(finalList, new PackageComparator(packageManager));


                listview = (ListView) findViewById(R.id.applist);
                listview.setAdapter(new RowAdapter(this, finalList, packageManager));
            }
        }


    }

    @Override
    public boolean onMenuItemSelected(int featureID, MenuItem item) {

        switch (item.getItemId()) {
            case R.id.enable_tethering:
            case R.id.disable_tethering:
                boolean enabled = (item.getItemId() == R.id.enable_tethering);
                initializeIptables.enableTethering(enabled);
                getSharedPreferences(PREFERENCE, MODE_PRIVATE).edit().putBoolean(PREF_KEY_IS_TETHER_ENAVLED, enabled).commit();

                return true;
            case R.id.authorize_browser:
            case R.id.disable_browser:
                final Long browser_uid = Long.valueOf(getSharedPreferences(PREFERENCE, MODE_PRIVATE).getString(PREF_KEY_SPEC_BROWSER, null));
                final Context context = this;

                initializeIptables.manageCaptiveBrowser((item.getItemId() == R.id.authorize_browser), browser_uid);
                getSharedPreferences(PREFERENCE, MODE_PRIVATE).edit().putBoolean(PREF_KEY_BROWSER_ENABLED, (item.getItemId() == R.id.authorize_browser)).commit();

                if (item.getItemId() == R.id.authorize_browser) {
                    this.timer = new CountDownTimer(TimeUnit.MINUTES.toMillis(5), TimeUnit.SECONDS.toMillis(30)) {
                        public void onTick(long untilFinished) {

                            long minutes = TimeUnit.MILLISECONDS.toMinutes(untilFinished);
                            long seconds = TimeUnit.MILLISECONDS.toSeconds(untilFinished) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(untilFinished));

                            CharSequence text = String.format("%d minutes, %d seconds until end of permission.", minutes, seconds);
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                        }

                        public void onFinish() {
                            initializeIptables.manageCaptiveBrowser(false, browser_uid);
                            getSharedPreferences(PREFERENCE, MODE_PRIVATE).edit().putBoolean(PREF_KEY_BROWSER_ENABLED, false).commit();
                            CharSequence text = "End of Browser bypass.";
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                        }
                    }.start();
                } else {
                    this.timer.cancel();
                }
                return true;

            case R.id.enable_sip:
            case R.id.disable_sip:
                Long sip_uid = Long.valueOf(getSharedPreferences(PREFERENCE, MODE_PRIVATE).getString(PREF_KEY_SIP_APP, null));
                initializeIptables.manageSip((item.getItemId() == R.id.enable_sip), sip_uid);
                getSharedPreferences(PREFERENCE, MODE_PRIVATE).edit().putBoolean(PREF_KEY_SIP_ENABLED, (item.getItemId() == R.id.enable_sip)).commit();
                return true;

            case R.id.action_settings:
                showPreferences();
                return true;

            case R.id.action_about:
                LayoutInflater li = LayoutInflater.from(this);
                View view = li.inflate(R.layout.about, null);
                String versionName = "";
                try {
                    versionName = packageManager.getPackageInfo(this.getPackageName(), 0).versionName;
                } catch (PackageManager.NameNotFoundException e) {

                }
                TextView version = (TextView) view.findViewById(R.id.about_version);
                version.setText(versionName);
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.menu_action_about))
                        .setView(view)
                        .show();
                return true;

            case R.id.action_search:
                TextWatcher filterTextWatcher = new TextWatcher() {

                    public void afterTextChanged(Editable s) {
                        showApplications(s.toString(), false);
                    }

                    public void beforeTextChanged(CharSequence s, int start, int count,
                                                  int after) {
                    }

                    public void onTextChanged(CharSequence s, int start, int before,
                                              int count) {
                        showApplications(s.toString(), false);
                    }

                };

                item.setActionView(R.layout.searchbar);

                final EditText filterText = (EditText) item.getActionView().findViewById(R.id.searchApps);

                filterText.addTextChangedListener(filterTextWatcher);
                filterText.setEllipsize(TextUtils.TruncateAt.END);
                filterText.setSingleLine();

                item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item) {
                        // Do something when collapsed
                        return true; // Return true to collapse action view
                    }

                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item) {
                        filterText.post(new Runnable() {
                            @Override
                            public void run() {
                                filterText.requestFocus();
                                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                                imm.showSoftInput(filterText, InputMethodManager.SHOW_IMPLICIT);
                            }
                        });
                        return true; // Return true to expand action view
                    }
                });
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        String sip_app = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getString(PREF_KEY_SIP_APP, null);
        boolean sip_enabled = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getBoolean(PREF_KEY_SIP_ENABLED, false);

        String browser_app = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getString(PREF_KEY_SPEC_BROWSER, null);
        boolean browser_enabled = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getBoolean(PREF_KEY_BROWSER_ENABLED, false);

        boolean tethering_enabled = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getBoolean(PREF_KEY_TETHER_ENABLED, false);
        boolean is_tether_enabled = getSharedPreferences(PREFERENCE, MODE_PRIVATE).getBoolean(PREF_KEY_IS_TETHER_ENAVLED, false);

        if (sip_app != null && !sip_app.equals("0")) {
            MenuItem item = menu.getItem(3);
            item.setEnabled(true);
            if (sip_enabled) {
                item.setVisible(false);
                menu.getItem(4).setVisible(true);
            } else {
                item.setVisible(true);
                menu.getItem(4).setVisible(false);
            }
        }
        if (browser_app != null && !browser_app.equals("0")) {
            MenuItem item = menu.getItem(1);
            item.setEnabled(true);
            if (browser_enabled) {
                item.setVisible(false);
                menu.getItem(2).setVisible(true);
            } else {
                item.setVisible(true);
                menu.getItem(2).setVisible(false);
            }
        }
        MenuItem tether_enable = menu.getItem(5);
        MenuItem tether_disable = menu.getItem(6);
        if (tethering_enabled) {
            tether_enable.setEnabled(true);

            if (is_tether_enabled) {
                tether_disable.setVisible(true);
                tether_enable.setVisible(false);
            } else {
                tether_enable.setVisible(true);
                tether_disable.setVisible(false);
            }
        } else {
            tether_enable.setEnabled(false);
            tether_disable.setVisible(false);
        }
        return true;
    }

    private void showPreferences() {
        Intent intent = new Intent(this, PreferencesActivity.class);
        startActivityForResult(intent, 1);
    }

    private void showApplications(final String searchStr, boolean showAll) {
        boolean isMatchFound = false;
        List<PackageInfo> searchApp = new ArrayList<PackageInfo>();

        if (searchStr != null && searchStr.length() > 0) {
            for (PackageInfo pkg : finalList) {
                String[] names = {
                        pkg.packageName,
                        packageManager.getApplicationLabel(pkg.applicationInfo).toString()
                };
                for (String name : names) {
                    if ((name.contains(searchStr.toLowerCase()) ||
                            name.toLowerCase().contains(searchStr.toLowerCase())) &&
                            !searchApp.contains(pkg)
                            ) {
                        searchApp.add(pkg);
                        isMatchFound = true;
                    }
                }
            }
        }

        List<PackageInfo> apps2;
        if (showAll || (searchStr != null && searchStr.equals(""))) {
            apps2 = finalList;
        } else if (isMatchFound || searchApp.size() > 0) {
            apps2 = searchApp;
        } else {
            apps2 = new ArrayList<PackageInfo>();
        }

        Collections.sort(apps2, new PackageComparator(packageManager));

        this.listview.setAdapter(new RowAdapter(this, apps2, packageManager));
    }
}