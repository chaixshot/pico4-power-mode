# PICO 4 High Performance Power Mode Unlock

An **LSPosed module** that unlocks the hidden **High Performance** option in **PICO 4 (A8110)** `Settings → Lab → Power Management`.

> 中文: [README.md](README.md) · Русский: [README.ru-RU.md](README.ru-RU.md)

## Features

- Adds a third **High Performance** option to the `Settings → Lab → Power Management` dropdown (stock only shows Battery Saver / Standard).
- Selecting High Performance invokes the system's official switch (`DeviceSwitchUtilsKt.e()`, `powerlevel=2`), equivalent to the stock high-perf mode:
  - **eyebuffer resolution forced to 2448×2448** (full quality, sharper)
  - **FFR off** (fixed foveated rendering removed — no edge blur)
  - **stencil mesh off**
  - `target_fps=-1` (uncapped frame rate)
  - **CPU/GPU high-performance scheduling** (`CpuFreqServer onPowerLevelChange 2`)
- **Bidirectional eyebuffer enforcement** (overrides stock/eco modes too):
  - High Performance (2) → **2448×2448**
  - Standard / Battery Saver (0/1) → **1504×1504** (back to stock default, saves power)
- Dropdown item and button text both show "**性能模式**" (Perf Mode) instead of the stock "效果优先" (Effect-First).

## Requirements

- PICO 4 (A8110), rooted
- Magisk + **Zygisk** (stock Zygisk verified working; Zygisk-Next did not work on this unit)
- **Zygisk Vector** (LSPosed-compatible framework, `zygisk_vector` module)

## Installation

1. Build the module (see Build) or use `picolab-power.apk` from a release.
2. Install: `adb install -r app/build/picolab-power.apk`
3. Configure scope (via Vector cli, or add in the Vector manager):

   ```
   su -c '/data/adb/modules/zygisk_vector/cli modules enable com.peaklab.powermode'
   su -c '/data/adb/modules/zygisk_vector/cli scope add com.peaklab.powermode com.picovr.settings'
   ```

4. Restart the settings app (`pkill -f com.picovr.settings`), open `Settings → Lab → Power Management`, and select "性能模式".

## Build

Standard LSPosed module project. Depends on JDK (`--release 8`), `r8.jar` (D8), `apktool.jar`, `platform.keystore`.

```
build.bat
```

Output: `app/build/picolab-power.apk`

- javac must use `--release 8` (JDK 26 emits class v52, rejected by D8).
- Xposed API uses hand-written stubs (`app/stub/`), compile-only, not packaged into dex.
- apktool package + jarsigner self-sign (no native lib, no zipalign needed).

## How it works

Hooks `com.picovr.fragments.PicolabFragment` in `com.picovr.settings`:

1. **`T0(View)`** — sets a flag indicating the power menu is opening.
2. **`PopupMenuHelper.c(...)`** — reflects into the power menu's `List<MenuItemData>` and appends a third "性能模式" item (text set directly via `MenuItemData.l(CharSequence)`).
3. **`U0(int)`** — intercepts all three levels (0/1/2): calls `DeviceSwitchUtilsKt.e(context, i)` (system switch, writes `powerlevel=i` + persist props), **and extra-forces eyebuffer**: perf(2)→2448×2448, standard/eco(0/1)→1504×1504.
4. **`Q(int)`** — makes the button/current-mode text show "性能模式".

### Gotchas

- `picolab_powerFunc3` resource string is proguard-obfuscated at runtime; **do not reflect into R.string** (`NoSuchFieldError`). Instead set text via `MenuItemData.l("性能模式")` or hardcode the resource ID.
- `xposed_init` must **not have a UTF-8 BOM** (otherwise the class name's first byte is corrupted → `ClassNotFoundException`).
- `T0` is a private method taking a `View` (not parameterless).
- The real source of eyebuffer resolution at runtime is the **`persist.pvr.config.eyebuffer_width/height`** system properties (not `PXRuleValueFile.txt`), so this module directly writes `setSystemProperties` for 2448/1504 on switch.

## Layout

```
pico4-power-mode/
├── app/                      # LSPosed module project
│   ├── AndroidManifest.xml
│   ├── apktool.yml
│   ├── assets/xposed_init    # entry class declaration
│   ├── res/values/arrays.xml # xposedscope
│   ├── src/com/peaklab/powermode/PowerModeHook.java  # entry hook
│   └── stub/                 # compile-only Xposed/Android stubs
├── build.bat                 # build script
└── README.md / README.en-US.md / README.ru-RU.md
```

## Related

- [pico4-paper_tracker-autostart](https://github.com/hhhbwc/pico4-paper_tracker-autostart)
- [pico4-winlimit](https://github.com/hhhbwc/pico4-winlimit)

## License

MIT
