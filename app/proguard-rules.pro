-keep class com.topjohnwu.superuser.** { *; }
-keepclassmembers class * extends com.topjohnwu.superuser.Shell$Initializer { *; }

-keep class android.view.SurfaceControl { *; }
-keep class android.media.MediaCodec { *; }

-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.yourapp.remotectrl.proto.** { *; }

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
