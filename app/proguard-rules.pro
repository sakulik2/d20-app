# --- D20 App R8/Proguard Rules ---

# 1. Kotlin Serialization 核心规则
# 保证带有 @Serializable 注解的类及其字段名被保留，这是规则集加载的核心
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.json.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembernames class * {
    @kotlinx.serialization.Serializable *;
}
# 保留序列化器生成的辅助类
-keep class **$$serializer { *; }

# 2. Room 数据库规则
# 保留 Entity 类及其字段，确保 SQLite 映射正常
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep class * {
    @androidx.room.PrimaryKey *;
    @androidx.room.ColumnInfo *;
    @androidx.room.Embedded *;
    @androidx.room.Relation *;
}

# 3. 业务模型与 JSON 映射类 (核心保留区)
# 必须保留字段名，否则 API 交互和本地存储解析会因找不到字段而崩溃
-keep class xyz.sakulik.d20.app.data.model.** { *; }
-keep class xyz.sakulik.d20.app.data.local.** { *; }
-keep class xyz.sakulik.d20.app.domain.rules.** { *; }
-keep class xyz.sakulik.d20.app.domain.worldview.** { *; }
-keep class xyz.sakulik.d20.app.domain.combat.** { *; }

# 4. OkHttp 兼容规则
-keepattributes Signature
-keepattributes AnnotationDefault
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# 5. Compose / Navigation 规则
-keep class androidx.navigation.compose.** { *; }
-keep class androidx.compose.material.icons.** { *; }


# 7. Android Security Crypto 相关兼容修复
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
# 修复 Tink 内部对缺失依赖的报错 (Google API Client, Errorprone, Joda-Time)
-dontwarn com.google.api.client.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.Instant
-dontwarn com.google.crypto.tink.**

# 8. 常见 Kotlin / Java 优化保留
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * {
    @kotlin.jvm.JvmField *;
}
