-repackageclasses 'o'
-allowaccessmodification
-optimizations !code/simplification/arithmetic
-keepattributes InnerClasses,EnclosingMethod,Signature,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep the native methods in our backend classes
-keepclasseswithmembernames,includedescriptorclasses class com.yervant.huntmem.backend.** {
    native <methods>;
}

-keep class java.security.** { *; }
-keep class javax.crypto.** { *; }

# Keep classes used by libsuperuser
-keep class com.topjohnwu.superuser.** { *; }
-keep interface com.topjohnwu.superuser.** { *; }

# General rules for Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <fields>;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}
