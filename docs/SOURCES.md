# Implementation references

Primary sources inspected during implementation2026-10-15. Exact dependency resolution and runtime checks are the build's evidence; documentation alone does not establish compatibility.

- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html): Kotlin2.4.0 with Gradle9.4.1; Kotlin/Compose compiler plugin versions match.
- [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html), [Compose1.10.3 plugin](https://plugins.gradle.org/plugin/org.jetbrains.compose/1.10.3), [native packaging](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).
- [Coroutines1.10.2](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.2), [serialization1.9.0](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0).
- [Java Sound AudioSystem](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/sound/sampled/AudioSystem.html), [TargetDataLine](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/sound/sampled/TargetDataLine.html).
- [Hyprland hyprctl](https://wiki.hypr.land/configuring/core/advanced-configuration/using-hyprctl/), [WirePlumber wpctl](https://pipewire.pages.freedesktop.org/wireplumber/man/wpctl.html).
- [Windows SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput), [LockWorkStation](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-lockworkstation).
- [Arch makepkg](https://man.archlinux.org/man/core/pacman/makepkg.8.en), [PKGBUILD](https://man.archlinux.org/man/PKGBUILD.5.en.html).

Checklist Design [settings](https://www.checklist.design/mobile/settings) and [toggle](https://www.checklist.design/design-system/toggle) pages were requested, but returned no readable checklist through the browser reader. No site-specific checklist items were asserted. UI quality checks used actual rendered screenshots, resizing and automated Compose interactions instead.
