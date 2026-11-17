# Shizuku user service is started by class name in another process.
-keep class app.framex.core.shizuku.ShellUserService { *; }
-keep class app.framex.core.shizuku.IShellService** { *; }
# Wireless-debugging client and its crypto helpers use reflection / JNI.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class android.sun.** { *; }
-dontwarn org.conscrypt.**
-dontwarn android.sun.**
-dontwarn org.bouncycastle.**
