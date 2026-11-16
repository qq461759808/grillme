package com.grillme.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：只要开关是开的，就把服务拉起来，不用你每天手动点一次。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? null : intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                return;
            }
            Cfg cfg = Cfg.get(context);
            if (cfg.enabled()) {
                Lang.init(context);
                Lang.setLang(context, cfg.lang());
                GrillService.start(context);
            }
        } catch (Exception ignored) {
        }
    }
}
