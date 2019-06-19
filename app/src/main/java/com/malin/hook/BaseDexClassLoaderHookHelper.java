package com.malin.hook;

import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

/**
 * 由于应用程序使用的ClassLoader为PathClassLoader
 * 最终继承自 BaseDexClassLoader
 * 查看源码得知,这个BaseDexClassLoader加载代码根据一个叫做
 * dexElements的数组进行, 因此我们把包含代码的dex文件插入这个数组
 * 系统的classLoader就能帮助我们找到这个类
 * <p>
 * 这个类用来进行对于BaseDexClassLoader的Hook
 * com from wei shu
 */
public final class BaseDexClassLoaderHookHelper {

    /**
     * 默认情况下performLaunchActivity会使用替身StubActivity的ApplicationInfo也就是宿主程序的CLassLoader加载所有的类；
     * 我们的思路是告诉宿主ClassLoader我们在哪，让其帮助完成类加载的过程。
     * <p>
     * 宿主程序的ClassLoader最终继承自BaseDexClassLoader，BaseDexClassLoader通过DexPathList进行类的查找过程；
     * 而这个查找通过遍历一个dexElements的数组完成；
     * <p>
     * 我们通过把插件dex添加进这个数组就让宿主ClassLoader获取了加载插件类的能力。
     */

    /**
     * 使用宿主ClassLoader帮助加载插件类
     *
     * @param classLoader 表示宿主的LoadedApk在Application类中有一个成员变量mLoadedApk，而这个变量是从ContextImpl中获取的；
     *                    ContextImpl重写了getClassLoader方法，
     *                    因此我们在Context环境中直接getClassLoader()获取到的就是宿主程序唯一的ClassLoader。
     * @param apkFile     apkFile
     * @param optDexFile  optDexFile
     */
    public static void patchClassLoader(ClassLoader classLoader, File apkFile, File optDexFile) {

        try {
            Class<?> superClass = DexClassLoader.class.getSuperclass();
            //1. 获取 BaseDexClassLoader : pathList
            //private final DexPathList pathList;
            //http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java
            Field pathListField = superClass.getDeclaredField("pathList");
            pathListField.setAccessible(true);

            //2.获取DexPathList pathList;
            Object dexPathList = pathListField.get(classLoader);


            //3. 获取 DexPathList的属性: Element[] dexElements
            //private final Element[] dexElements;
            //http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java
            Field dexElementArray = dexPathList.getClass().getDeclaredField("dexElements");
            dexElementArray.setAccessible(true);

            //4.获取 DexPathList的属性 Element[] dexElements;值
            //Element是DexPathList的内部类
            Object[] dexElements = (Object[]) dexElementArray.get(dexPathList);

            //5. Element 类型
            // 数组的 class 对象的getComponentType()方法可以取得一个数组的Class对象
            Class<?> elementClass = dexElements.getClass().getComponentType();

            //6. 创建一个数组, 用来替换原始的数组
            //通过Array.newInstance()可以反射生成数组对象,生成数组，指定元素类型和数组长度
            Object[] newElements = (Object[]) Array.newInstance(elementClass, dexElements.length + 1);


            Object elementObj;
            if (Build.VERSION.SDK_INT >= 26) {
                //7.构造插件Element
                // 构造函数 public Element(DexFile dexFile, File dexZipPath){}
                //这个构造函数不能用了 @Deprecated public Element(File dir, boolean isDirectory, File zip, DexFile dexFile){}
                Constructor<?> constructor = elementClass.getConstructor(DexFile.class, File.class);
                constructor.setAccessible(true);

                //8. 生成Element的实例对象
                //http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/DexFile.java
                elementObj = constructor.newInstance(DexFile.loadDex(apkFile.getCanonicalPath(), optDexFile.getAbsolutePath(), 0), apkFile);
                ///data/data/com.malin.hook/files
                // /data/data/com.malin.hook/files/oat/arm64/pluginapk-debug.odex
                // /data/data/com.malin.hook/files/oat/arm64/pluginapk-debug.vdex
            } else if (Build.VERSION.SDK_INT >= 18) {
                //7. 构造插件Element(File file, boolean isDirectory, File zip, DexFile dexFile){} 这个构造函数
                //DexPathList的静态内部类static class Element {}
                //构造函数:public Element(File dir, boolean isDirectory, File zip, DexFile dexFile)
                Constructor<?> constructor = elementClass.getConstructor(File.class, boolean.class, File.class, DexFile.class);
                constructor.setAccessible(true);

                //8. 生成Element的实例对象
                elementObj = constructor.newInstance(apkFile, false, apkFile, DexFile.loadDex(apkFile.getCanonicalPath(), optDexFile.getAbsolutePath(), 0));
            } else {
                //<=17
                //TODO:未解决
                //java.lang.IllegalAccessError: Class ref in pre-verified class resolved to unexpected implementation

                //7. 构造插件public Element(File file, File zip, DexFile dexFile){} 这个构造函数
                //DexPathList的静态内部类static class Element {}
                //构造函数:public Element(File dir, boolean isDirectory, File zip, DexFile dexFile)
                Constructor<?> constructor = elementClass.getConstructor(File.class, File.class, DexFile.class);
                constructor.setAccessible(true);

                //8. 生成Element的实例对象
                elementObj = constructor.newInstance(apkFile, apkFile, DexFile.loadDex(apkFile.getCanonicalPath(), optDexFile.getAbsolutePath(), 0));
            }

            Object[] toAddElementArray = new Object[]{elementObj};

            // 把原始的elements复制进去
            System.arraycopy(dexElements, 0, newElements, 0, dexElements.length);

            // 插件的那个element复制进去
            System.arraycopy(toAddElementArray, 0, newElements, dexElements.length, toAddElementArray.length);

            // 替换
            dexElementArray.set(dexPathList, newElements);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
    }
}
