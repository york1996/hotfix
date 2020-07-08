package com.york1996.hotfix;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.HashSet;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

public class HotFixManager {
    private static final String TAG = "FixManager";
    private static HashSet<File> mLoadedDex = new HashSet<>();

    static {
        mLoadedDex.clear();
    }

    public static void loadDex(Context context) {
        if (context == null) {
            return;
        }
        File filesDir = context.getCodeCacheDir();
        File[] listFiles = filesDir.listFiles();

        // 过滤非dex文件
        for (File file : listFiles) {
            if (file.getName().startsWith("classes") || file.getName().endsWith(".dex")) {
                Log.d(TAG, "dexName:" + file.getName());
                mLoadedDex.add(file);
            }
        }

        // 遍历文件加入
        for (File dex : mLoadedDex) {
            try {
                // 获取PathClassLoader加载的系统类等
                PathClassLoader pathClassLoader = (PathClassLoader) context.getClassLoader();
                Class baseDexClassLoader = Class.forName("dalvik.system.BaseDexClassLoader");
                Field pathListFiled = baseDexClassLoader.getDeclaredField("pathList");
                pathListFiled.setAccessible(true);
                Object pathListObject = pathListFiled.get(pathClassLoader);

                Class systemDexPathListClass = pathListObject.getClass();
                Field systemElementsField = systemDexPathListClass.getDeclaredField("dexElements");
                systemElementsField.setAccessible(true);
                Object systemElements = systemElementsField.get(pathListObject);

                // 自定义DexClassLoader定义要载入的补丁dex，此处其实可以将多个dex用「:」隔开，则无需遍历
                DexClassLoader dexClassLoader = new DexClassLoader(dex.getAbsolutePath(), null, null, context.getClassLoader());
                Class customDexClassLoader = Class.forName("dalvik.system.BaseDexClassLoader");
                Field customPathListFiled = customDexClassLoader.getDeclaredField("pathList");
                customPathListFiled.setAccessible(true);
                Object customDexPathListObject = customPathListFiled.get(dexClassLoader);

                Class customPathClass = customDexPathListObject.getClass();
                Field customElementsField = customPathClass.getDeclaredField("dexElements");
                customElementsField.setAccessible(true);
                Object customElements = customElementsField.get(customDexPathListObject);

                // 合并数组
                Class<?> elementClass = systemElements.getClass().getComponentType();
                int systemLength = Array.getLength(systemElements);
                int customLength = Array.getLength(customElements);
                int newSystemLength = systemLength + customLength;

                // 生成一个新的数组，类型为Element类型
                Object newElementsArray = Array.newInstance(elementClass, newSystemLength);
                for (int i = 0; i < newSystemLength; i++) {
                    if (i < customLength) {
                        Array.set(newElementsArray, i, Array.get(customElements, i));
                    } else {
                        Array.set(newElementsArray, i, Array.get(systemElements, i - customLength));
                    }
                }

                // 覆盖新数组
                Field elementsField = pathListObject.getClass().getDeclaredField("dexElements");
                elementsField.setAccessible(true);
                elementsField.set(pathListObject, newElementsArray);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
