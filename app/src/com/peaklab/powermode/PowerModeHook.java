package com.peaklab.powermode;

import android.content.Context;
import android.view.View;
import android.widget.BaseAdapter;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XC_MethodHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * PicoLabPowerMode
 * 给 Pico 4 设置 / 实验室 / 电源管理方案 的下拉框加上 "高性能"(High Performance, powerlevel=2) 档位。
 *
 * 策略(稳):
 *  1. hook PicolabFragment.T0(): 置一个标志, 表示正在弹"电源模式"菜单
 *  2. hook PopupMenuHelper.c(Activity, View, BaseAdapter, SimpleOnItemClickListener, int):
 *     若标志置位, 反射取 OSUIMenuAdapter 的私有 List<MenuItemData> f, 若尚无"高性能"项则追加一项
 *     (MenuItemData(TYPE_TITLE_CHECK).k(picolab_powerFunc3)), 并 notifyDataSetChanged()
 *  3. hook PicolabFragment.U0(int): i==2(高性能) 时直接运行时切换(DeviceSwitchUtilsKt.e(ctx,2)),
 *     避免走 P()[2] 越界; 并刷新按钮文字
 *
 * 系统底层已支持 powerlevel=2 (eyebuffer 2048 / 关FFR / 关stencil / 由 DeviceSwitchUtilsKt.e 写 props).
 */
public class PowerModeHook implements IXposedHookLoadPackage {

    public static final String TAG = "PicoLabPower";

    // 静态标志: 当前是否在弹"电源模式"菜单 (PicolabFragment)
    private static volatile boolean sPowerMenuOpen = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        if (lp.packageName == null || !lp.packageName.equals("com.picovr.settings")) {
            return;
        }
        XposedBridge.log(TAG + ": load in settings");
        final ClassLoader cl = lp.classLoader;

        final Class<?> frag;
        try {
            frag = XposedHelpers.findClass("com.picovr.fragments.PicolabFragment", cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": no PicolabFragment " + t);
            return;
        }
        XposedBridge.log(TAG + ": PicolabFragment found");

        // ---------- 反射取 R.string 资源 ----------
        // 运行时 R.string 字段名可能被 proguard 混淆, 直接用 public.xml 里稳定的资源ID常量.
        //  (picolab_powerFunc1=0x7f1002e8, Func2=0x7f1002e9, Func3=0x7f1002ea)
        final int resPowerFunc3 = 0x7f1002ea;
        final int resPowerFunc2 = 0x7f1002e9;
        final int resPowerTip3 = 0x7f1002ea;

        // ---------- 1) hook T0(View): 置标志 (T0 是带 View 参数的私有方法) ----------
        try {
            XposedHelpers.findAndHookMethod(frag, "T0", View.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { sPowerMenuOpen = true; }
                @Override protected void afterHookedMethod(MethodHookParam p)  { sPowerMenuOpen = false; }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": T0 hook err " + t); }

        // ---------- 2) hook PopupMenuHelper.c: 给电源菜单加"高性能"项 ----------
        try {
            Class<?> helper = XposedHelpers.findClass("com.picovr.customviews.PopupMenuHelper", cl);
            Class<?> listener = XposedHelpers.findClass("com.picovr.listener.SimpleOnItemClickListener", cl);
            XposedHelpers.findAndHookMethod(helper, "c",
                android.app.Activity.class, View.class, BaseAdapter.class,
                listener, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!sPowerMenuOpen) return;
                        try {
                            BaseAdapter adapter = (BaseAdapter) p.args[2];
                            if (adapter == null) return;
                            // 反射取 List<MenuItemData> f
                            Field f = null;
                            for (Field fd : adapter.getClass().getDeclaredFields()) {
                                if (fd.getType() == java.util.List.class) { f = fd; break; }
                            }
                            if (f == null) return;
                            f.setAccessible(true);
                            List<Object> data = (List<Object>) f.get(adapter);
                            // 已是3项则跳过
                            if (data.size() >= 3) { sPowerMenuOpen = false; return; }
                            // 构造 MenuItemData(TYPE_TITLE_CHECK).k(powerFunc3)
                            Class<?> typeEnum = XposedHelpers.findClass(
                                "com.bytedance.osui.popupmenu.MenuItemType", cl);
                            Object titleCheck = Enum.valueOf((Class<? extends Enum>) typeEnum, "TYPE_TITLE_CHECK");
                            Class<?> md = XposedHelpers.findClass(
                                "com.bytedance.osui.popupmenu.MenuItemData", cl);
                            Object item = md.getConstructor(typeEnum).newInstance(titleCheck);
                            // item.k(R.string.picolab_powerFunc3) -> 显示"效果优先", 改用 l(CharSequence) 直接设文案
                            // k.invoke(item, resPowerFunc3);
                            Method l = md.getMethod("l", CharSequence.class);
                            l.invoke(item, "性能模式");
                            data.add(item);
                            // 刷新
                            Method n = adapter.getClass().getMethod("notifyDataSetChanged");
                            n.invoke(adapter);
                            XposedBridge.log(TAG + ": added High Performance item to power menu");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": inject menu err " + t);
                        } finally {
                            sPowerMenuOpen = false;
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PopupMenuHelper hook err " + t);
        }

        // ---------- 3) hook U0(int i): 高性能(i==2) 运行时切换 ----------
        try {
            XposedHelpers.findAndHookMethod(frag, "U0", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int i = (int) p.args[0];
                    if (i == 0 || i == 1 || i == 2) { // 接管三个档位: 双向强制 eyebuffer
                        try {
                            // 运行时切换走系统: DeviceSwitchUtilsKt.e(context, i)
                            Object activity = XposedHelpers.callMethod(p.thisObject, "getActivity");
                            Class<?> dsu = XposedHelpers.findClass(
                                "com.picovr.settings.custom.DeviceSwitchUtilsKt", cl);
                            Method e = dsu.getMethod("e", Context.class, int.class);
                            e.invoke(null, activity, i);
                            // 强制 eyebuffer: 性能档(2)->2448, 标准/续航(0/1)->1504x1504
                            // 运行时真正读 persist.pvr.config.eyebuffer_width/height, 不是 PXRuleValueFile
                            String w = (i == 2) ? "2448" : "1504";
                            String h = (i == 2) ? "2448" : "1504";
                            try {
                                Class<?> sp = Class.forName("android.os.SystemProperties");
                                Method spSet = sp.getMethod("set", String.class, String.class);
                                spSet.invoke(null, "persist.pvr.config.eyebuffer_width", w);
                                spSet.invoke(null, "persist.pvr.config.eyebuffer_height", h);
                                XposedBridge.log(TAG + ": eyebuffer -> " + w + "x" + h + " (powerlevel=" + i + ")");
                            } catch (Throwable t) { XposedBridge.log(TAG + " set eyebuffer err " + t); }
                            // 更新字段 this.m = i (反射)
                            try {
                                Field mf = frag.getDeclaredField("m");
                                mf.setAccessible(true);
                                mf.setInt(p.thisObject, i);
                            } catch (Throwable t) { XposedBridge.log(TAG + " set m err " + t); }
                            // 刷新按钮文字: V(i)
                            Method v = frag.getDeclaredMethod("V", int.class);
                            v.setAccessible(true);
                            v.invoke(p.thisObject, i);
                            XposedBridge.log(TAG + ": powerlevel=" + i + " applied (eyebuffer forced)");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": U0(" + i + ") err " + t);
                        }
                        p.setResult(null); // 接管原逻辑, 避免越界
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": U0 hook err " + t);
        }

        // ---------- 4) hook Q(int): 当前方案/按钮文字 (i==2 时不用系统资源"效果优先", 改显"性能模式") ----------
        try {
            XposedHelpers.findAndHookMethod(frag, "Q", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int i = (int) p.args[0];
                    if (i == 2) {
                        p.setResult("性能模式");
                    }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": Q hook err " + t); }

        XposedBridge.log(TAG + ": installed");
    }
}
