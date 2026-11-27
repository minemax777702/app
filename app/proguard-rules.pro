# Классы, которые библиотеки создают через рефлексию. R8 не видит этих вызовов и вырезает конструкторы:
# без них виджет навсегда остаётся на крутилке загрузки (WorkManager: «OverwritingInputMerger has no zero argument constructor»).

# Виджет Glance рисуется фоновой задачей WorkManager.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Нажатие на товар в виджете запускает наш ActionCallback по имени класса.
-keep class * extends androidx.glance.appwidget.action.ActionCallback { <init>(); }

# JSch algorithm implementations are selected by class name. Keep only implementations
# used in the pinned P-256/AES-CTR/HMAC configuration of the diagnostic.
-keep class com.jcraft.jsch.DHEC256 { <init>(); }
-keep class com.jcraft.jsch.UserAuthNone { <init>(); }
-keep class com.jcraft.jsch.UserAuthPublicKey { <init>(); }
-keep class com.jcraft.jsch.jce.ECDHN { <init>(); }
-keep class com.jcraft.jsch.jce.SHA256 { <init>(); }
-keep class com.jcraft.jsch.jce.SignatureECDSA256 { <init>(); }
-keep class com.jcraft.jsch.jce.HMACSHA256 { <init>(); }
-keep class com.jcraft.jsch.jce.AES128CTR { <init>(); }
-keep class com.jcraft.jsch.jce.Random { <init>(); }
-keep class com.jcraft.jsch.jce.MD5 { <init>(); }
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
