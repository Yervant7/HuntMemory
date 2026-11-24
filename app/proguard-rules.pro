# HuntMemory - ProGuard & R8 Configuration
# Optimized according to modern AGP and R8 standards

-repackageclasses 'o'
-allowaccessmodification
-optimizations !code/simplification/arithmetic
-keepattributes InnerClasses,EnclosingMethod,Signature,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Native JNI entry points (Backend memory engine, maps, scanner, and script bridge)
-keepclasseswithmembernames,includedescriptorclasses class com.yervant.huntmem.backend.** {
    native <methods>;
}

# AIDL IPC Stub & Interface for libsu RootService and Lua UI Callback (called via JNI reflection)
-keep public class com.yervant.huntmem.IHMemService** { *; }
-keep public interface com.yervant.huntmem.IHMemService** { *; }
-keep public class com.yervant.huntmem.ILuaUiCallback** { *; }
-keep public interface com.yervant.huntmem.ILuaUiCallback** { *; }

# Lua UI Bridge called via JNI from Rust scripting runtime
-keep class com.yervant.huntmem.ui.overlay.tabs.LuaUiBridge {
    public static <methods>;
}
