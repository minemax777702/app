# Классы, которые библиотеки создают через рефлексию. R8 не видит этих вызовов и вырезает конструкторы:
# без них виджет навсегда остаётся на крутилке загрузки (WorkManager: «OverwritingInputMerger has no zero argument constructor»).

# Виджет Glance рисуется фоновой задачей WorkManager.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Нажатие на товар в виджете запускает наш ActionCallback по имени класса.
-keep class * extends androidx.glance.appwidget.action.ActionCallback { <init>(); }

# JSch selects implementations, crypto providers and authentication handlers by class name.
# Preserve this library in release APKs; keep R8 enabled for the rest of the app.
-keep class com.jcraft.jsch.** { *; }
# Optional JSch agents/providers are not used on Android. No global missing-class suppression.
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn java.lang.foreign.**

# JGit uses service descriptors and optional non-Android platform integrations.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**

# JGit 7.7+ GC.PidLock.getPID explicitly returns 0 on Android via SystemReader.isAndroid().
# The desktop-only ProcessHandle branch remains in bytecode, but Android never executes it.
-dontwarn java.lang.ProcessHandle
