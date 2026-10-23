# Rezka Console — плавающий терминал поверх приложений

Полноценная консоль в маленьком плавающем окне над любыми приложениями
Android (проверено на 4.2.2, API 17, armeabi-v7a; minSdk 14):

* PTY-шелл — настоящий `/bin/sh`, а не эмуляция построчного ввода;
* root через `su` (на прошитом планшете — сразу);
* ssh-клиент (dropbear), пароли вводятся в терминал, а не в диалог;
* спецклавиши с экранной клавиатуры: стрелки, Ctrl+буква, Tab;
* ANSI-фильтр: вырезает CSI/OSC/цвета, но понимает очистку экрана.

Ставится на любое устройство и работает поверх любого приложения.

## Сборка

Зависимости (Debian/Ubuntu):

```bash
apt install openjdk-17-jdk-headless gcc-arm-linux-gnueabi zip
```

Тулчейн (build-tools + android.jar 4.2.2) кладётся в `./android` — вне git:

```bash
mkdir -p android && cd android
curl -O https://dl.google.com/android/repository/build-tools_r34-linux.zip
curl -O https://dl.google.com/android/repository/android-17_r03.zip
unzip build-tools_r34-linux.zip    # -> android-14: aapt2, d8, zipalign, apksigner
unzip android-17_r03.zip           # -> android-4.2.2: android.jar
cd ..
./build.sh                         # -> RezkaConsole.apk
```

Если тулчейн уже есть в другом месте — `ANDROID_TOOLCHAIN=/путь ./build.sh`
(по умолчанию ищется `./android`, затем `~/android-toolchain`).
Ключ подписи берётся из `$ANDROID_TOOLCHAIN/debug.keystore` (создаётся сам,
пароль `android`), переопределяется через `KEYSTORE=/путь/к.jks`.

Конвейер без gradle и IDE: `aapt2 → javac → d8 → zip → zipalign → apksigner`.

## Установка

```bash
adb install -r RezkaConsole.apk
```

Package намеренно оставлен `org.rezka.console`, чтобы обновление вставало
поверх уже установленной консоли (смена package = только удалять и ставить
заново, вместе с настройками).

## Как пользоваться

1. Запустить приложение — появится маленькое окно терминала поверх экрана.
2. Окно таскается за заголовок, размер — углами; шелл при этом переживает
   пересоздание поверхности (PTY живёт в сервисе).
3. Строка ввода — снизу; спецклавиши на экранной клавиатуре транслируются
   в сырые байты: стрелки `ESC[A-D`, Ctrl+буква `0x01–0x1A`, Tab —
   дополнение набранного. Backspace не перехватывается (правится локально).

## Внутренности

`src/org/rezka/console/ConsoleService.java` — сервис с PTY, `MainActivity` —
окно. Ключевые решения:

* **PTY через jni/pty.c** — голые syscalls (`-nostdlib`), потому что libc
  планшета 4.2 несовместима с хост-тулчейном.
* **`su` с чистым env без `USER`** — mkshrc прошивки берёт имя пользователя
  из переменной окружения, а не из `id`: с `USER=u0_aXX` промпт врал даже
  под root.
* **Спецклавиши** перехватываются на строке ввода и уходят в PTY байтами,
  а не через `KeyEvent` в шелл.
* **ANSI-фильтр** вырезает escape-последовательности, но `ESC[J`, `ESC[2J`,
  `ESC[3J` стирают экран — иначе `clear` и полноэкранные программы ломают
  вывод.

## Грабли (проверено на устройстве)

* `-Wl,--hash-style=sysv -Wl,--build-id=none` обязательны: linker Android 4.2
  не знает `.gnu.hash`, при `dlopen` — SIGSEGV без внятной ошибки.
* Без `android.permission.INTERNET` сокеты не создаются вообще — ssh падает
  с `EPERM` ещё до connect.
* Без `<supports-screens android:anyDensity="true">` планшет включает
  compatibility mode, метрики врут (455×320), окно 300×380dp раздувается
  в 1350×1710px и уезжает за экран.
* `SYSTEM_ALERT_WINDOW` на 4.x выдаётся при установке, отдельного диалога нет.

## Лицензия

Личное поделие под собственные древние устройства. Код как есть, гарантий нет.
