# Сборка и установка ClapCtrl

Все команды ниже выполняются из корня проекта. Сборка требует установленный JDK 25, интернет для Gradle/Maven dependencies и сборку на целевой ОС. Используется Gradle wrapper из исходников. `createDistributable` создаёт приложение с собственным runtime: конечному пользователю отдельная Java не нужна. Исходники не загружаются из Git или с несуществующего release URL.

Версия рецепта и имён архивов — `0.1.2`; при выпуске её нужно менять вместе с версией Gradle. `CLAPCTRL_VERSION` меняет имя архива и не подменяет metadata приложения.

## Arch Linux / Omarchy

Установите зависимости сборки через обычный пакетный менеджер, затем собирайте от обычного пользователя:

```sh
sudo pacman -S --needed base-devel java25-openjdk
makepkg --dir packaging/arch
```

`--dir` необходим: makepkg требует PKGBUILD непосредственно в своём рабочем каталоге; `makepkg -p packaging/arch/PKGBUILD` из корня этому контракту не соответствует. Рецепт копирует текущие исходники, включая локальные изменения, в `$srcdir/clapctrl`. Он исключает Git, Gradle caches, результаты сборки и пользовательские аудиоданные. `build()` создаёт Linux app image; `check()` запускает тесты. `source=()` здесь обозначает локальный рецепт, который требует полный checkout рядом; это не готовый standalone AUR-рецепт. Не используйте `--noextract` после изменения исходников, иначе makepkg может повторно использовать старый snapshot. Первый Gradle запуск скачивает Gradle distribution через wrapper и Maven dependencies; air-gapped сборка требует заранее наполненных caches.

Для установки выберите созданный файл для своей архитектуры:

```sh
sudo pacman -U ./packaging/arch/clapctrl-0.1.2-1-x86_64.pkg.tar.zst
clapctrl
```

Пакет размещает app image с runtime в `/opt/clapctrl`, launcher в `/usr/bin/clapctrl`, desktop entry и SVG icon в стандартных XDG каталогах. Package manager может использовать другой формат сжатия согласно `/etc/makepkg.conf` — в таком случае выберите фактическое имя файла.

Опциональные зависимости: `xorg-xwayland` для отображения AWT окна, если выбранный JDK использует X11 в Wayland сессии, `pipewire-alsa` для Java Sound через PipeWire, `wireplumber`/`wpctl` или `libpulse`/`pactl` для mute, `playerctl` для media, `grim` для Wayland screenshot, `hyprland`/`hyprctl` для workspace, `hyprlock` для блокировки, поддерживаемые terminal и launcher. Поддержка native Wayland зависит от выбранного JDK и не считается проверенной только по наличию `WAYLAND_DISPLAY`. Приложение определяет доступные инструменты. Наличие пакета не подтверждает поддержку compositor protocol или доступ к устройству; недоступный backend сообщает ошибку. Реальная проверка Arch/Hyprland и микрофона на этой Windows машине не выполнялась.

Удаление приложения:

```sh
sudo pacman -R clapctrl
```

Пользовательская конфигурация и калибровка сохраняются; путь данных показывает приложение. Пакет не включает автоматическое удаление пользовательских файлов.

## Автозапуск Linux: только по выбору пользователя

Установка пакета не включает autostart. Чтобы открывать приложение при входе, скопируйте установленный шаблон в пользовательский XDG каталог:

```sh
mkdir -p "${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
cp /usr/share/clapctrl/clapctrl-autostart.desktop "${XDG_CONFIG_HOME:-$HOME/.config}/autostart/clapctrl.desktop"
```

Это запускает приложение, а не даёт согласие на захват микрофона. Для отключения удалите только созданный `autostart/clapctrl.desktop`. Hyprland может не обрабатывать XDG autostart без соответствующего session manager: при необходимости добавьте `exec-once = clapctrl` в свою конфигурацию Hyprland вместо XDG шаблона. Не включайте оба варианта одновременно.

## Linux portable

```sh
bash packaging/linux/build-portable.sh
```

В `packaging/dist` появятся `.tar.gz` и `.sha256`. Скрипт выполняет тесты, создаёт host app image и сохраняет executable permissions в tar. После уже выполненной сборки можно использовать `--package-only`.

Для запуска проверьте checksum из доверенного источника, распакуйте архив и выполните `./ClapCtrl/bin/ClapCtrl`. Для удаления закройте приложение и удалите распакованный каталог. Этот вариант не устанавливает системные desktop entry или autostart. Runtime содержит Java, но системные библиотеки и совместимость glibc по-прежнему зависят от целевой Linux-системы; собирайте на совместимой с ней системе.

## Windows portable

```powershell
.\packaging\windows\build-portable.ps1
```

Скрипт использует `JAVA_HOME` или `java.exe` из `PATH`, запускает тесты и `createDistributable`, затем сохраняет полный runtime image в `packaging/dist/clapctrl-0.1.2-windows-x86_64.zip` с SHA256 sidecar. `-PackageOnly` архивирует уже созданный image; `-ImageDirectory <путь>` позволяет выбрать отдельную подготовленную сборку. JDK 25 нужен для сборки; WiX для portable ZIP не требуется. Для MSI отдельно выполните `gradlew.bat :app-desktop:packageMsi` в окружении Windows с инструментами, требуемыми `jpackage`. Свойство Gradle `-Pclapctrl.outputDir=out/action-fix-binaries` сохраняет новый image отдельно, когда прежняя версия запущена.

Проверьте архив через `Get-FileHash -Algorithm SHA256`, распакуйте целиком и запустите `ClapCtrl\ClapCtrl.exe`. Не перемещайте один `.exe` отдельно от остальных файлов. Для удаления закройте приложение и удалите распакованную папку; пользовательские настройки сохранятся. Подпись и Windows SmartScreen reputation в этот workflow не входят. Сборка и проверка MSI установщика не считаются выполненными этим ZIP workflow.

## Воспроизводимый snapshot исходников

На Linux с GNU tar/gzip:

```sh
SOURCE_DATE_EPOCH=0 bash packaging/source-archive.sh
cd packaging/dist
sha256sum -c clapctrl-0.1.2-source.tar.gz.sha256
tar -xzf clapctrl-0.1.2-source.tar.gz
cd clapctrl-0.1.2
makepkg --dir packaging/arch
```

Архив включает текущие выбранные исходники, документацию, packaging и CI, включая uncommitted файлы внутри этих каталогов. Git history, caches, build outputs и локальные записи не включаются. Для одинаковых bytes, file modes и `SOURCE_DATE_EPOCH` исходный tar.gz детерминирован: порядок, uid/gid, mtime и gzip metadata нормализованы. SHA256 проверяет идентичность архива, но не удостоверяет его автора. Воспроизводимость исходного snapshot не означает bit-for-bit воспроизводимость JDK image, Gradle outputs или native packages.

## CI и границы проверки

`.github/workflows/build.yml` выполняет `test` и `createDistributable` на Ubuntu 24.04 и Windows 2025 с JDK 25, публикует portable архивы, checksums и test reports как временные artifacts workflow. Linux job также создаёт source snapshot. Сборки не запускают микрофон и desktop actions, не публикуют release, не включают autostart и не устанавливают приложение. Успешная CI-сборка подтверждает host packaging, но не runtime-поведение микрофона, tray, горячих действий или Hyprland. Arch `makepkg` нужно проверять отдельно на Arch; CI не симулирует этот результат.

Основания: [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html), [makepkg](https://man.archlinux.org/man/core/pacman/makepkg.8.en), [PKGBUILD](https://man.archlinux.org/man/PKGBUILD.5.en.html).
