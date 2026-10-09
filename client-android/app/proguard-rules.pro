# ============================================================
# NasGameHub Android — ProGuard / R8 rules
#
# 这个文件之前根本没建, 但 build.gradle.kts 的 release buildType 引用了它,
# 导致 `./gradlew assembleRelease` 直接失败。加上了。
# ============================================================

# ---- 保留签名与注解 ----
# 接口 + 泛型返回类型 (suspend / List<T>) 靠反射签名还原
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- OkHttp / okio ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**
# OkHttp 引用了一些编译期不存在的可选依赖
-dontwarn javax.annotation.**

# ---- kotlinx.coroutines ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Hilt / Dagger ----
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
# @AndroidEntryPoint 生成的类
-keep class * extends androidx.lifecycle.ViewModel { <init>(); }
-keep class dagger.hilt.android.internal.managers.ViewComponentManager$*
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager*

# ---- Compose ----
-dontwarn androidx.compose.**

# ---- ZXing (115 扫码登录) ----
-dontwarn com.google.zxing.**

# ---- 我们的数据模型 ----
# 全部走 SQLiteOpenHelper 手写映射, 不需要反射保活; 但字段名要留着方便排查
-keep class com.nasgame.data.model.** { *; }

# ---- 模拟器集成 ----
# 包名常量字符串必须完整保留 (不能被裁掉字符串)
-keepclassmembers class com.nasgame.emulator.RetroArchManager$Companion { *; }
