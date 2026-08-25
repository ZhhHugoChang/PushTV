# Ktor & Coroutines Rules
-keep class io.ktor.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.ktor.**
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-keepattributes *Annotation*
