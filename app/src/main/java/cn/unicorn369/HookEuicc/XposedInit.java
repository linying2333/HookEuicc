package cn.unicorn369.HookEuicc;

import android.app.Activity;
import android.app.AndroidAppHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ResolveInfo;

import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.telephony.TelephonyManager;

import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String Title = "eSIM Code";

    private static String initActivationCode = "";
    private static Activity activity;

    private static final String PREF_NAME = "conf";
    private static final String KEY_ENABLE_HOOK = "enable_hook";
    private static final String KEY_ENABLE_FAKE_EID = "enable_fake_eid";
    // private static final String KEY_ENABLE_NO_EUICC = "enable_no_euicc";
    private static final String KEY_ENABLE_BYPASS_OMAPI = "enable_bypass_omapi";
    private static final String KEY_WHITELIST_PACKAGES = "whitelist_packages";

    private static final String KEY_VALUE_FAKE_EID = "value_fake_eid";

    private XSharedPreferences prefs;

    private Set<String> parseWhitelist(String raw) {
        Set<String> set = new HashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String pkg : raw.split(",")) {
                String trimmed = pkg.trim();
                if (!trimmed.isEmpty()) {
                    set.add(trimmed);
                }
            }
        }
        return set;
    }

    private boolean isWhitelistMatch(String packageName) {
        prefs.reload();
        String raw = prefs.getString(KEY_WHITELIST_PACKAGES, "");
        Set<String> whitelist = parseWhitelist(raw);
        return !whitelist.isEmpty() && packageName != null && whitelist.contains(packageName);
    }

    private boolean isWhitelistEmpty() {
        prefs.reload();
        String raw = prefs.getString(KEY_WHITELIST_PACKAGES, "");
        return parseWhitelist(raw).isEmpty();
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, "conf");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // 机型受限，暂不启用
        /*
        if (lpparam.packageName.equals("com.android.phone")) {
            XposedHelpers.findAndHookMethod(
                "com.android.internal.telephony.uicc.UiccSlot",
                lpparam.classLoader, "isEuicc",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_NO_EUICC, false)) return;
                        param.setResult(false);
                    }
                }
            );
        }
        */

        // === OMAPI Bypass (com.android.se) ===
        if (lpparam.packageName.equals("com.android.se")) {
            // 始终安装 readSecurityProfile hook，回调内动态检查开关和白名单
            XposedHelpers.findAndHookMethod("com.android.se.security.AccessControlEnforcer",
                lpparam.classLoader, "readSecurityProfile",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_BYPASS_OMAPI, false)) {
                            return null;
                        }
                        if (!isWhitelistEmpty()) {
                            return null;
                        }
                        XposedHelpers.setBooleanField(param.thisObject, "mUseArf", false);
                        XposedHelpers.setBooleanField(param.thisObject, "mUseAra", false);
                        XposedHelpers.setBooleanField(param.thisObject, "mFullAccess", true);
                        return null;
                    }
                });

            XposedHelpers.findAndHookMethod("com.android.se.Terminal",
                lpparam.classLoader, "isPrivilegedApplication", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_BYPASS_OMAPI, false)) return;
                        String packageName = (String) param.args[0];
                        if (isWhitelistMatch(packageName)) {
                            param.setResult(true);
                        }
                    }
                });
        }

        // === eSIM Hook (其他进程) ===
        if (!lpparam.packageName.equals("com.android.se")) {
            Class<?> packageManagerClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader);

            // Activity 捕获（始终安装）
            XposedHelpers.findAndHookMethod(
                Activity.class, "onCreate", "android.os.Bundle",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        activity = (Activity) param.thisObject;
                    }
                }
            );

            // 伪装支持eSIM
            XposedHelpers.findAndHookMethod(
                packageManagerClass, "hasSystemFeature", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_HOOK, true)) return;
                        String feature = (String) param.args[0];
                        if (feature.equals(PackageManager.FEATURE_TELEPHONY_EUICC)) {
                            param.setResult(true);
                        }
                    }
                }
            );
            XposedHelpers.findAndHookMethod(
                packageManagerClass, "hasSystemFeature", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_HOOK, true)) return;
                        String feature = (String) param.args[0];
                        if (feature.equals(PackageManager.FEATURE_TELEPHONY_EUICC)) {
                            param.setResult(true);
                        }
                    }
                }
            );
            XposedHelpers.findAndHookMethod(
                EuiccManager.class, "isEnabled",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_HOOK, true)) return;
                        param.setResult(true);
                    }
                }
            );

            // 获取eSIM激活码
            XposedHelpers.findAndHookMethod(
                DownloadableSubscription.class, "forActivationCode", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_HOOK, true)) return;
                        String activationCode = (String) param.args[0];
                        if (activationCode != null) {
                            XposedBridge.log("HookEuicc-eSIM Code: " + activationCode);
                            shareCode(activationCode);
                        }
                    }
                }
            );

            // Hook LPA
            XposedHelpers.findAndHookMethod(
                DownloadableSubscription.class, "getEncodedActivationCode",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_HOOK, true)) return;
                        String activationCode = (String) param.getResult();
                        if (activationCode != null) {
                            XposedBridge.log("HookEuicc-eSIM Code: " + activationCode);
                            shareCode(activationCode);
                        }
                    }
                }
            );

            // 其他检测
            XposedHelpers.findAndHookMethod(
                packageManagerClass, "queryIntentServices", Intent.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_HOOK, true)) return;
                        Intent intent = (Intent) param.args[0];
                        if (intent != null && "android.service.euicc.EuiccService".equals(intent.getAction())) {
                            List<ResolveInfo> originalList = (List<ResolveInfo>) param.getResult();
                            if (originalList == null || originalList.isEmpty()) {
                                List<ResolveInfo> fakeList = new ArrayList<>();
                                fakeList.add(createFakeResolveInfo());
                                param.setResult(fakeList);
                            }
                        }
                    }
                }
            );
            XposedHelpers.findAndHookMethod(
                TelephonyManager.class, "getCardIdForDefaultEuicc",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_HOOK, true)) return;
                        param.setResult(0);
                    }
                }
            );

            // 伪装EID（动态读取值）
            XposedHelpers.findAndHookMethod(
                EuiccManager.class, "getEid",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        prefs.reload();
                        if (!prefs.getBoolean(KEY_ENABLE_FAKE_EID, false)) return;
                        String value = prefs.getString(KEY_VALUE_FAKE_EID, "89044123456789876543210123456789");
                        param.setResult(value);
                    }
                }
            );
        }
    }

    private void shareCode(String activationCode) {
        Context context = AndroidAppHelper.currentApplication().getApplicationContext();
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            ClipData clipdata = ClipData.newPlainText(Title, activationCode);
            clipboardManager.setPrimaryClip(clipdata);
        }
        if (initActivationCode != activationCode) {
            initActivationCode = activationCode;
            Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, activationCode);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            shareIntent = Intent.createChooser(shareIntent, Title);
            try {
                context.startActivity(shareIntent);
            } catch (Exception e) {
                activity.startActivity(shareIntent);
            }
            try {
                Toast.makeText(context, "已复制到剪切板\neSIM激活码：" + activationCode, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(activity, "已复制到剪切板\neSIM激活码：" + activationCode, Toast.LENGTH_LONG).show();
            }
        }
    }

    private ResolveInfo createFakeResolveInfo() {
        ResolveInfo fakeInfo = new ResolveInfo();
        fakeInfo.serviceInfo = new ServiceInfo();
        fakeInfo.serviceInfo.packageName = BuildConfig.APPLICATION_ID;
        fakeInfo.serviceInfo.name = "HookEuiccService";
        fakeInfo.serviceInfo.permission = "android.permission.BIND_EUICC_SERVICE";
        return fakeInfo;
    }
}
