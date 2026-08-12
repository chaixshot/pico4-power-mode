# PICO 4 — разблокировка высокопроизводительного режима

**LSPosed-модуль**, открывающий скрытый **High Performance** в **PICO 4 (A8110)** в `Настройки → Лаборатория → Управление питанием`.

> 中文: [README.md](README.md) · English: [README.en-US.md](README.en-US.md)

## Возможности

- Добавляет в выпадающий список `Настройки → Лаборатория → Управление питанием` третий пункт **High Performance** (в стоке только «Экономия» и «Стандарт»).
- Выбор High Performance вызывает штатное системное переключение (`DeviceSwitchUtilsKt.e()`, `powerlevel=2`), эквивалентное заводскому режиму:
  - **eyebuffer принудительно до 2448×2448** (полное качество, чётче)
  - **FFR выкл** (отключается фиксированное внимание к центру — без размытых краёв)
  - **stencil mesh выкл**
  - `target_fps=-1` (без ограничения кадров)
  - **высокочастотное планирование CPU/GPU** (`CpuFreqServer onPowerLevelChange 2`)
- **Двунаправленное принуждение eyebuffer** (переопределяет также стандартный/экономичный режимы):
  - Производительность (2) → **2448×2448**
  - Стандарт / Экономия (0/1) → **1504×1504** (возврат к заводскому, экономия энергии)
- И пункт меню, и текст кнопки показывают «**性能模式**» вместо стокового «效果优先».

## Требования

- PICO 4 (A8110), с root
- Magisk + **Zygisk** (штатный Zygisk проверен; Zygisk-Next на этом устройстве не заработал)
- **Zygisk Vector** (LSPosed-совместимый фреймворк, модуль `zygisk_vector`)

## Установка

1. Соберите модуль (см. Сборка) или используйте `picolab-power.apk` из релиза.
2. Установите: `adb install -r app/build/picolab-power.apk`
3. Настройте scope (через cli Vector или в менеджере Vector):

   ```
   su -c '/data/adb/modules/zygisk_vector/cli modules enable com.peaklab.powermode'
   su -c '/data/adb/modules/zygisk_vector/cli scope add com.peaklab.powermode com.picovr.settings'
   ```

4. Перезапустите приложение настроек (`pkill -f com.picovr.settings`), откройте `Настройки → Лаборатория → Управление питанием` и выберите «性能模式».

## Сборка

Стандартный проект LSPosed-модуля. Нужны JDK (`--release 8`), `r8.jar` (D8), `apktool.jar`, `platform.keystore`.

```
build.bat
```

Результат: `app/build/picolab-power.apk`

- javac обязан использовать `--release 8` (JDK 26 выдаёт class v52, который D8 не принимает).
- Xposed API — самописные стабы (`app/stub/`), только для компиляции, в dex не попадают.
- apktool пакетирует + jarsigner подписывает (без нативных lib, zipalign не нужен).

## Как это работает

Хуки в `com.picovr.fragments.PicolabFragment` из `com.picovr.settings`:

1. **`T0(View)`** — ставит флаг открытия меню питания.
2. **`PopupMenuHelper.c(...)`** — отражательно добавляет в `List<MenuItemData>` меню третий пункт «性能模式» (текст через `MenuItemData.l(CharSequence)`).
3. **`U0(int)`** — перехватывает все три уровня (0/1/2): вызывает `DeviceSwitchUtilsKt.e(context, i)` (системное переключение, пишет `powerlevel=i` и persist props), **и дополнительно принудительно задаёт eyebuffer**: производительность(2)→2448×2448, стандарт/экономия(0/1)→1504×1504.
4. **`Q(int)`** — показывает «性能模式» в кнопке/текущем режиме.

### Подводные камни

- Ресурс `picolab_powerFunc3` во время выполнения обфусцирован proguard; **не отражайте в R.string** (`NoSuchFieldError`). Ставьте текст через `MenuItemData.l("性能模式")` или хардкодьте ID ресурса.
- В `xposed_init` **не должно быть UTF-8 BOM** (иначе первый байт имени класса превратится в мусор → `ClassNotFoundException`).
- `T0` — приватный метод с параметром `View` (не без параметров).
- Реальный источник eyebuffer во время выполнения — **системные свойства `persist.pvr.config.eyebuffer_width/height`** (не `PXRuleValueFile.txt`), поэтому модуль напрямую пишет `setSystemProperties` для 2448/1504 при переключении.

## Структура

```
pico4-power-mode/
├── app/                      # проект LSPosed-модуля
│   ├── AndroidManifest.xml
│   ├── apktool.yml
│   ├── assets/xposed_init    # объявление входного класса
│   ├── res/values/arrays.xml # xposedscope
│   ├── src/com/peaklab/powermode/PowerModeHook.java  # входной хук
│   └── stub/                 # стабы Xposed/Android (только для компиляции)
├── build.bat                 # скрипт сборки
└── README.md / README.en-US.md / README.ru-RU.md
```

## Связанное

- [pico4-paper_tracker-autostart](https://github.com/hhhbwc/pico4-paper_tracker-autostart)
- [pico4-winlimit](https://github.com/hhhbwc/pico4-winlimit)

## Лицензия

MIT