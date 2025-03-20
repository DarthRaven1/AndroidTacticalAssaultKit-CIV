package com.foxykeep.datadroid.network;

import android.app.Application;
import com.google.crypto.tink.config.TinkConfig;
import java.security.GeneralSecurityException;

public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Initialize Tink
            TinkConfig.register();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to initialize Tink", e);
        }
    }
}
