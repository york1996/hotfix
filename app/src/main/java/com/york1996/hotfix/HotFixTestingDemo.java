package com.york1996.hotfix;

import android.content.Context;
import android.widget.Toast;

//待修复的文件
public class HotFixTestingDemo {
    public static void showToast(Context context) {
        Toast.makeText(context, "修复前", Toast.LENGTH_SHORT).show();
//        Toast.makeText(context, "修复后", Toast.LENGTH_SHORT).show();
    }
}
