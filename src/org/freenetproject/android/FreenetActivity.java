package org.freenetproject.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class FreenetActivity extends Activity {

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        Log.i("Freenet", "=== onCreate ===");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        startService(new Intent(FreenetService.ACTION_START_NODE));
    }
}
