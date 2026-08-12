# Preserve useful Release crash traces while allowing R8 to rename source files.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Kotlin Serialization and Room publish their required R8 rules through their
# libraries. The application uses generated serializers and Room's generated
# implementation directly, so whole model/domain packages must remain shrinkable.
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-dontwarn androidx.room.paging.**

# Optional providers referenced by networking and AndroidX Security internals.
-dontwarn org.conscrypt.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.api.client.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.Instant
