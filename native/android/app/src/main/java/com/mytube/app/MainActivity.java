package com.mytube.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaDownloaderPlugin.class); // super.onCreate 보다 먼저 등록해야 함
        super.onCreate(savedInstanceState);
    }
}
