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
import java.util.Locale;

/**
 * PicoLabPowerMode
 * 给 Pico 4 设置 / 实验室 / 电源管理方案 的下拉框加上 "高性能"(High Performance, powerlevel=2) 档位。
 *
 * 策略(稳):
 *  1. hook PicolabFragment.T0(): 置一个标志, 表示正在弹"电源模式"菜单
 *  2. hook PopupMenuHelper.c(Activity, View, BaseAdapter, SimpleOnItemClickListener, int):
 *     若标志置位, 反射取 OSUIMenuAdapter 的私有 List<MenuItemData> f, 若尚无"性能"/"画质"项则追加之
 *     (MenuItemData(TYPE_TITLE_CHECK).k(picolab_powerFunc3)), 并 notifyDataSetChanged()
 *  3. hook PicolabFragment.U0(int): i==2(性能) 或 i==3(画质) 时接管, 避免走 P()[i] 越界; 并刷新按钮文字
 *     eyebuffer 双向强制: 画质(3)->2448, 其余->1504; FFR 双向强制: 性能(2)->关, 其余->开
 *
 * 系统底层已支持 powerlevel=2 (eyebuffer 2048 / 关FFR / 关stencil / 由 DeviceSwitchUtilsKt.e 写 props).
 */
public class PowerModeHook implements IXposedHookLoadPackage {

    public static final String TAG = "PicoLabPower";

    // 静态标志: 当前是否在弹"电源模式"菜单 (PicolabFragment)
    private static volatile boolean sPowerMenuOpen = false;
    private static final String COORD_PREFIX = "pico_power_coord_";
    private static final String COORD_OWNER = COORD_PREFIX + "owner";
    private static final String COORD_SLEEP_ACTIVE = COORD_PREFIX + "sleep_active";
    private static final String COORD_POWER_MODE = COORD_PREFIX + "requested_power_mode";
    private static final String COORD_GENERATION = COORD_PREFIX + "generation";

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
                            // 构造 MenuItemData(TYPE_TITLE_CHECK).k(powerFunc3)
                            // 已是4项则跳过
                            if (data.size() >= 4) { sPowerMenuOpen = false; return; }

                            Class<?> typeEnum = XposedHelpers.findClass(
                                "com.bytedance.osui.popupmenu.MenuItemType", cl);
                            Object titleCheck = Enum.valueOf((Class<? extends Enum>) typeEnum, "TYPE_TITLE_CHECK");
                            Class<?> md = XposedHelpers.findClass(
                                "com.bytedance.osui.popupmenu.MenuItemData", cl);
                            Method l = md.getMethod("l", CharSequence.class);
                            Context context = (Context) p.args[0];

                            // 追加"性能" (Performance)
                            if (data.size() == 2) {
                                Object item = md.getConstructor(typeEnum).newInstance(titleCheck);
                                l.invoke(item, getPerfString(context));
                                data.add(item);
                            }
                            
                            // 追加"画质" (Quality)
                            if (data.size() == 3) {
                                Object item = md.getConstructor(typeEnum).newInstance(titleCheck);
                                l.invoke(item, getQualString(context));
                                data.add(item);
                            }

                            // 刷新
                            Method n = adapter.getClass().getMethod("notifyDataSetChanged");
                            n.invoke(adapter);
                            XposedBridge.log(TAG + ": added Performance & Quality items to power menu");
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

        // ---------- 3) hook U0(int i): 接管 0/1/2/3 档位切换 ----------
        try {
            XposedHelpers.findAndHookMethod(frag, "U0", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int i = (int) p.args[0];
                    if (i == 0 || i == 1 || i == 2 || i == 3) { // 接管所有档位 (续航/标准/性能/画质)
                        try {
                            Object activity = XposedHelpers.callMethod(p.thisObject, "getActivity");
                            Context context = activity instanceof Context ? (Context) activity : null;
                            if (context == null) throw new IllegalStateException("Settings activity has no Context");
                            publishRequestedMode(context, i);
                            if (sleepOwnsDisplay(context)) {
                                XposedBridge.log(TAG + ": deferred powerlevel=" + i + " while V-Sleep owns display state");
                            } else {
                                applyPowerMode(context, i, cl);
                            }
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
                            XposedBridge.log(TAG + ": powerlevel=" + i + " applied (eyebuffer/FFR forced)");
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

        // ---------- 4) hook Q(int): 当前方案/按钮文字 ----------
        try {
            XposedHelpers.findAndHookMethod(frag, "Q", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int i = (int) p.args[0];
                    Context ctx = (Context) XposedHelpers.callMethod(p.thisObject, "getActivity");
                    if (i == 2) {
                        p.setResult(getPerfString(ctx));
                    } else if (i == 3) {
                        p.setResult(getQualString(ctx));
                    }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": Q hook err " + t); }

        XposedBridge.log(TAG + ": installed");
    }

    private static void publishRequestedMode(Context context, int mode) throws Exception {
        Class<?> global = Class.forName("android.provider.Settings$Global");
        Class<?> resolver = Class.forName("android.content.ContentResolver");
        Object cr = context.getContentResolver();
        Method putInt = global.getMethod("putInt", resolver, String.class, int.class);
        Method getInt = global.getMethod("getInt", resolver, String.class, int.class);
        if (!((Boolean) putInt.invoke(null, cr, COORD_POWER_MODE, mode)).booleanValue()
                || ((Integer) getInt.invoke(null, cr, COORD_POWER_MODE, -1)).intValue() != mode) {
            throw new IllegalStateException("could not persist requested power mode");
        }
        int generation = ((Integer) getInt.invoke(null, cr, COORD_GENERATION, 0)).intValue() + 1;
        if (!((Boolean) putInt.invoke(null, cr, COORD_GENERATION, generation)).booleanValue()) {
            throw new IllegalStateException("could not advance coordination generation");
        }
    }

    private static boolean sleepOwnsDisplay(Context context) {
        try {
            Class<?> global = Class.forName("android.provider.Settings$Global");
            Class<?> resolver = Class.forName("android.content.ContentResolver");
            Object cr = context.getContentResolver();
            int active = ((Integer) global.getMethod("getInt", resolver, String.class, int.class)
                    .invoke(null, cr, COORD_SLEEP_ACTIVE, 0)).intValue();
            String owner = (String) global.getMethod("getString", resolver, String.class)
                    .invoke(null, cr, COORD_OWNER);
            return active == 1 && "vsleep".equals(owner);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": coordination read failed " + t);
            return false;
        }
    }

    private static void applyPowerMode(Context context, int mode, ClassLoader cl) throws Exception {
        Class<?> dsu = XposedHelpers.findClass("com.picovr.settings.custom.DeviceSwitchUtilsKt", cl);
        dsu.getMethod("e", Context.class, int.class).invoke(null, context, mode);
        String buffer = (mode == 3) ? "2448" : "1504"; // 画质 (3) 使用 2448, 性能 (2) 及其他档位使用 1504
        Class<?> properties = Class.forName("android.os.SystemProperties");
        Method set = properties.getMethod("set", String.class, String.class);
        Method get = properties.getMethod("get", String.class);
        set.invoke(null, "persist.pvr.config.eyebuffer_width", buffer);
        set.invoke(null, "persist.pvr.config.eyebuffer_height", buffer);
        String width = (String) get.invoke(null, "persist.pvr.config.eyebuffer_width");
        String height = (String) get.invoke(null, "persist.pvr.config.eyebuffer_height");
        if (!buffer.equals(width) || !buffer.equals(height)) {
            throw new IllegalStateException("eyebuffer verification failed: " + width + "x" + height);
        }
        if (mode == 2) {
            set.invoke(null, "persist.pvr.config.ffr", "0");
            String ffrNow = (String) get.invoke(null, "persist.pvr.config.ffr");
            if (!"0".equals(ffrNow)) {
                throw new IllegalStateException("FFR verification failed: " + ffrNow);
            }
        }
        XposedBridge.log(TAG + ": powerlevel=" + mode + " applied, eyebuffer=" + buffer + "x" + buffer + (mode == 2 ? ", ffr=0" : ""));
    }

    private String getPerfString(Context context) {
        if (context == null) return "性能";
        try {
            Object res = XposedHelpers.callMethod(context, "getResources");
            Object config = XposedHelpers.callMethod(res, "getConfiguration");
            Locale locale = (Locale) XposedHelpers.getObjectField(config, "locale");
            String lang = locale.getLanguage();
            String country = locale.getCountry();

            switch (lang) {
                case "cs": return "Výkon";
                case "da": return "Ydelse";
                case "nl": return "Prestatie";
                case "fi": return "Suorituskyky";
                case "fr": return "Performance";
                case "de": return "Leistung";
                case "el": return "Απόδοση";
                case "it": return "Prestazioni";
                case "ja": return "パフォーマンス";
                case "ko": return "성능";
                case "ms": return "Prestasi";
                case "nb": case "no": return "Ytelse";
                case "pl": return "Wydajność";
                case "pt": return "Desempenho";
                case "ro": return "Performanță";
                case "ru": return "Производительность";
                case "es": return "Rendimiento";
                case "sv": return "Prestanda";
                case "th": return "ประสิทธิภาพ";
                case "tr": return "Performans";
                case "zh":
                    if ("TW".equals(country) || "HK".equals(country) || "MO".equals(country)) {
                        return "效能";
                    }
                    return "性能";
                case "en":
                default: return "Performance";
            }
        } catch (Throwable t) {
            return "性能";
        }
    }

    private String getQualString(Context context) {
        if (context == null) return "画质";
        try {
            Object res = XposedHelpers.callMethod(context, "getResources");
            Object config = XposedHelpers.callMethod(res, "getConfiguration");
            Locale locale = (Locale) XposedHelpers.getObjectField(config, "locale");
            String lang = locale.getLanguage();
            String country = locale.getCountry();

            switch (lang) {
                case "cs": return "Kvalita";
                case "da": return "Kvalitet";
                case "nl": return "Kwaliteit";
                case "fi": return "Laatu";
                case "fr": return "Qualité";
                case "de": return "Qualität";
                case "el": return "Ποιότητα";
                case "it": return "Qualità";
                case "ja": return "画質";
                case "ko": return "화질";
                case "ms": return "Kualiti";
                case "nb": case "no": return "Kvalitet";
                case "pl": return "Jakość";
                case "pt": return "Qualidade";
                case "ro": return "Calitate";
                case "ru": return "Качество";
                case "es": return "Calidad";
                case "sv": return "Kvalitet";
                case "th": return "คุณภาพ";
                case "tr": return "Kalite";
                case "zh":
                    if ("TW".equals(country) || "HK".equals(country) || "MO".equals(country)) {
                        return "畫質";
                    }
                    return "画质";
                case "en":
                default: return "Quality";
            }
        } catch (Throwable t) {
            return "画质";
        }
    }
}
