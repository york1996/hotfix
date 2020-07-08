package com.york1996.hotfix;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private File mCodeCacheFileDir;
    private File mAppFileDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mCodeCacheFileDir = getApplicationContext().getCodeCacheDir();
        mAppFileDir = getApplicationContext().getExternalFilesDir("");

        Log.d(TAG, "mCodeCacheFileDir = " + mCodeCacheFileDir + " mAppFileDir = " + mAppFileDir);
    }

    public void clickToShowToast(View view) {
        HotFixTestingDemo.showToast(getApplicationContext());
    }

    public void clickToHotFix(View view) {
        //将out.dex写入CodeCacheFileDir
        String name = "out.dex";
        String filePath = new File(mCodeCacheFileDir, name).getAbsolutePath();
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }
        InputStream is = null;
        FileOutputStream os = null;
        try {
            Log.d(TAG, "fixBug: " + mAppFileDir.getAbsolutePath());
            is = new FileInputStream(new File(mAppFileDir, name));
            os = new FileOutputStream(filePath);
            int len;
            byte[] buffer = new byte[1024];
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            File f = new File(filePath);
            if (f.exists()) {
                Toast.makeText(this, "dex overwrite", Toast.LENGTH_SHORT).show();
            }
            HotFixManager.loadDex(this);
        } catch (IOException e) {
            Log.d(TAG, "fix error: " + e.getMessage());
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                Log.d(TAG, "fix error: " + e.getMessage());
            }
        }
    }
}
