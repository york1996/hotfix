# Android 热修复技术探索

## 简介

热修复，指的就是不用通过重新安装 APK 进行代码的修改，通过某些手段替换一些文件，达到对应用程序行为作出修改的作用。在国内大多都是通过反射对 ClassLoader 等手段实现。

谷歌官方已经提供了一个全新的方案：[Android App Bundle](https://developer.android.com/guide/app-bundle)，但由于国内互联网环境，并未得到有效的推广，取而代之的则是市面上的各种热更新插件，比如腾讯微信的 [Tinker](https://github.com/Tencent/tinker)，以及完整的 PaaS 方案：[Bugly 应用升级](https://bugly.qq.com/v2/products/upgrade)。需要注意的是，Google Play 是严禁带有热更新功能的应用上架的。

一般来说，国内的热修复方案，无非就是通过 IO 操作把补丁文件下载下来，通过某些方式替换，下次启动即完成了修复。

知识储备：[反射](https://www.cnblogs.com/chanshuyi/p/head_first_of_reflection.html)、[双亲委托模式](https://www.jianshu.com/p/f053066f5f51)、[类加载机制](https://www.cnblogs.com/chanshuyi/p/the_java_class_load_mechamism.html)、[Java IO 操作](https://www.runoob.com/java/java-files-io.html)

扩展：[Android增量更新](https://juejin.im/post/5da845cdf265da5b7244c63e)

## MultiDex

官方文档：[为方法数超过 64K 的应用启用 MultiDex](https://developer.android.com/studio/build/multidex?hl=zh-cn)

简单来说，MultiDex 就是将编译好的 class 文件拆开打包成多个 dex，本意是为超大型应用绕过 dex 方法的限制，运行时加载其他 dex 文件。这样的话一个 APK 里有多个 dex，一般启动时仅加载第一个 dex，后面几个 dex 会在 Application 的 onCreate 中通过 ClassLoader 进行加载。那么热修复的关键技术就在于 ClassLoader 中，通过 ClassLoader 去加载替换的 dex 文件即可。

## 类替换原理

类替换，核心就是要通过 ClassLoader 去加载替换的类， ClassLoader 将编译好的类加载到虚拟机中。

ClassLoader 分为 Java 和 Android 两种不同的 ClassLoader，因为在 Android 虚拟机中（ART 和 DVM）都是加载 dex 文件的，而不是加载 jar 和 class 文件。本文简单介绍 Android 中的 ClassLoader，在 Android 中 ClassLoader 包括 BootClassLoader、PathClassLoader 以及 DexClassLoader。 

>  Java 中的 ClassLoader 可参考以下资料：
>
>  [Android解析ClassLoader（一）Java中的ClassLoader](https://juejin.im/post/59cb2270518825276e78029a)
>
>  [Android解析ClassLoader（二）Android中的ClassLoader](https://juejin.im/post/59e73b3cf265da432e5b1b29)

### BootClassLoader

Android 系统启动时会使用 BootClassLoader 来预加载常用类，BootClassLoader 是 ClassLoader 用 default 修饰的内部类，应用也无法直接调用。

### PathClassLoader

Android 系统使用 PathClassLoader 来加载在本地文件系统里的 dex 相关文件，一般用来加载系统类及应用程序的类。仅支持加载 apk/jar 内的 dex 文件。

```java
/**
 * Provides a simple {@link ClassLoader} implementation that operates on a list
 * of files and directories in the local file system, but does not attempt to
 * load classes from the network. Android uses this class for its system class
 * loader and for its application class loader(s).
 */
public class PathClassLoader extends BaseDexClassLoader {
    public PathClassLoader(String dexPath, ClassLoader parent) {
        super(dexPath, null, null, parent);
    }

    public PathClassLoader(String dexPath, String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
    }

    /**
     * @hide
     */
    @libcore.api.CorePlatformApi
    public PathClassLoader(
            String dexPath, String librarySearchPath, ClassLoader parent,
            ClassLoader[] sharedLibraryLoaders) {
        super(dexPath, librarySearchPath, parent, sharedLibraryLoaders);
    }
}
```

PathClassLoader 继承于 BaseDexClassLoader，由代码可见方法都在父类中，遵循双亲委托模式。

PathClassLoader 最多由三个参数：

- dexPath：包含 dex 的 apk 文件或 jar 文件的路径集合，多个路径用文件分隔符分隔，默认文件分隔符为‘：’。
- librarySearchPath：包含 C/C++ Native 库的路径集合，多个路径用文件分隔符分隔分割，可以为 null。
- parent：ClassLoader 的 parent。

### DexClassLoader

DexClassLoader 用来加载来自 apk/jar 文件内的 dex 文件，同时亦能从一个 jar 包或者未安装的 apk 中加载 dex，可由用户自定义，**故 DexClassLoader 为热修复的关键类**。API 26 后相关 dex 文件需要放置到应用私有的文件夹内，通过 `context.getCodeCacheDir()` 获取到文件夹路径，将需要热修复的 dex 文件放置在此处才可用 DexClassLoader 进行加载。

```java
/**
 * A class loader that loads classes from {@code .jar} and {@code .apk} files
 * containing a {@code classes.dex} entry. This can be used to execute code not
 * installed as part of an application.
 *
 * <p>Prior to API level 26, this class loader requires an
 * application-private, writable directory to cache optimized classes.
 * Use {@code Context.getCodeCacheDir()} to create such a directory:
 * <pre>   {@code
 *   File dexOutputDir = context.getCodeCacheDir();
 * }</pre>
 *
 * <p><strong>Do not cache optimized classes on external storage.</strong>
 * External storage does not provide access controls necessary to protect your
 * application from code injection attacks.
 */
public class DexClassLoader extends BaseDexClassLoader {
    public DexClassLoader(String dexPath, String optimizedDirectory,
            String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
    }
}
```

构造函数参数多了一个 optimizedDirectory，但在 API 26 中已被废弃，故无需过于关注。其它参数与 PathClassLoader 中是一致的。

### 关键方法

在 DexClassLoader 中主要还是靠父类 BaseDexClassLoader 去进行类加载，从 [BaseDexClassLoader](https://cs.android.com/android/platform/superproject/+/master:libcore/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java) 的代码出发：

```java
/**
 * Base class for common functionality between various dex-based
 * {@link ClassLoader} implementations.
 */
public class BaseDexClassLoader extends ClassLoader {

    //忽略部分代码
    @UnsupportedAppUsage
    private final DexPathList pathList;
    
    //......
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // First, check whether the class is present in our shared libraries.
        if (sharedLibraryLoaders != null) {
            for (ClassLoader loader : sharedLibraryLoaders) {
                try {
                    return loader.loadClass(name);
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        // Check whether the class in question is present in the dexPath that
        // this classloader operates on.
        List<Throwable> suppressedExceptions = new ArrayList<Throwable>();
        Class c = pathList.findClass(name, suppressedExceptions);
        if (c == null) {
            ClassNotFoundException cnfe = new ClassNotFoundException(
                    "Didn't find class \"" + name + "\" on path: " + pathList);
            for (Throwable t : suppressedExceptions) {
                cnfe.addSuppressed(t);
            }
            throw cnfe;
        }
        return c;
    }
    
    //.......
}
    
```

可见 DexClassLoader 中维护着一个 DexPathList，在 DexPathList 中通过 `findClass` 方法进行查找对应的类，那么接下来看下 DexPathList 的代码：


```java
/**
 * A pair of lists of entries, associated with a {@code ClassLoader}.
 * One of the lists is a dex/resource path &mdash; typically referred
 * to as a "class path" &mdash; list, and the other names directories
 * containing native code libraries. Class path entries may be any of:
 * a {@code .jar} or {@code .zip} file containing an optional
 * top-level {@code classes.dex} file as well as arbitrary resources,
 * or a plain {@code .dex} file (with no possibility of associated
 * resources).
 *
 * <p>This class also contains methods to use these lists to look up
 * classes and resources.</p>
 *
 * @hide
 */
public final class DexPathList {
    private static final String DEX_SUFFIX = ".dex";
    private static final String zipSeparator = "!/";

    /** class definition context */
    @UnsupportedAppUsage
    private final ClassLoader definingContext;

    /**
     * List of dex/resource (class path) elements.
     * Should be called pathElements, but the Facebook app uses reflection
     * to modify 'dexElements' (http://b/7726934).
     */
    @UnsupportedAppUsage
    private Element[] dexElements;

    /** List of native library path elements. */
    // Some applications rely on this field being an array or we'd use a final list here
    @UnsupportedAppUsage
    /* package visible for testing */ NativeLibraryElement[] nativeLibraryPathElements;

    /** List of application native library directories. */
    @UnsupportedAppUsage
    private final List<File> nativeLibraryDirectories;

    /** List of system native library directories. */
    @UnsupportedAppUsage
    private final List<File> systemNativeLibraryDirectories;
    
    //.......
    
    /**
     * Finds the named class in one of the dex files pointed at by
     * this instance. This will find the one in the earliest listed
     * path element. If the class is found but has not yet been
     * defined, then this method will define it in the defining
     * context that this instance was constructed with.
     *
     * @param name of class to find
     * @param suppressed exceptions encountered whilst finding the class
     * @return the named class or {@code null} if the class is not
     * found in any of the dex files
     */
    public Class<?> findClass(String name, List<Throwable> suppressed) {
        for (Element element : dexElements) {
            Class<?> clazz = element.findClass(name, definingContext, suppressed);
            if (clazz != null) {
                return clazz;
            }
        }

        if (dexElementsSuppressedExceptions != null) {
            suppressed.addAll(Arrays.asList(dexElementsSuppressedExceptions));
        }
        return null;
    }
    
    //.......
}
```

可见在 DexPathList 中维护着一个 dexElements 成员变量，在 `findClass` 方法中按顺序遍历。那么就是说，热修复的关键就在于此，开发者仅需要对这个 dexElements 进行修改即可进行热修复操作。

## 代码实践

### 生成 dex

Android SDK 中提供了生成 dex 文件的工具 dx，位置位于 SDK 目录下 build-tools 目录里，点开任意版本的文件夹均有此工具。生成 dex 文件的命令为：

`dx --dex --no-strict --output out.dex test.class`

其中上边的 out.dex 即是由 test.class 生成的 dex 文件，可以自己命名，同时 test.class 这个参数可以为目录，即将目录下所有 class 生成 dex 文件。

### 打入补丁

注意文件读写权限问题：[Android 10分区存储介绍及百度APP适配实践](https://mp.weixin.qq.com/s/djTZykAvPc3uWcdvAjHZMw)

从上面的类替换原理中可以知道需要取得 DexPathList 的 dexElements 进行数组的修改即可，同时也要保留原有的数组，故我们需要获取到 PathClassLoader 中的数组，然后自定义 DexClassLoader，对 DexClassLoader 数组进行定义，将两个数组合并，由于是按顺序读取获得对应类，自定义补丁的数组要在前面。整体思路即是如此，代码如下，细节可看注释：

```java
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
```

> 需要注意要把补丁 dex 文件放到 CodeCacheDir 中，这样 DexClassLoader 方可访问（API 26）



**参考资料**

[安卓App热补丁动态修复技术介绍 - QQ空间技术团队](https://mp.weixin.qq.com/s?__biz=MzI1MTA1MzM2Nw==&mid=400118620&idx=1&sn=b4fdd5055731290eef12ad0d17f39d4a&scene=1&srcid=1106Imu9ZgwybID13e7y2nEi)

[热修复入门：Android 中的 ClassLoader](https://jaeger.itscoder.com/android/2016/08/27/android-classloader.html)

[浅谈 Android Dex 文件](https://tech.youzan.com/qian-tan-android-dexwen-jian/)

