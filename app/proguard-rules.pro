# Ktor & Coroutines Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Ktor Server & Engine
-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.netty.** { *; }
-keep class io.ktor.server.application.** { *; }
-keep class io.ktor.server.routing.** { *; }
-keep class io.ktor.server.response.** { *; }
-keep class io.ktor.server.request.** { *; }
-keep class io.ktor.http.** { *; }
-dontwarn io.ktor.**

# Netty: 仅保留运行时反射需要的核心类和工厂方法，允许 R8 剪裁全部无关模块（DNS, SOCKS, Proxy, Epoll, KQueue 等）
-keep class io.netty.channel.socket.nio.NioServerSocketChannel { <init>(); }
-keep class io.netty.channel.socket.nio.NioSocketChannel { <init>(); }
-keep class io.netty.util.internal.** { *; }
-keep class io.netty.util.internal.shaded.org.jctools.** { *; }
-keep class io.netty.bootstrap.** { *; }
-keep class io.netty.channel.ChannelFactory { *; }
-keep class io.netty.channel.ReflectiveChannelFactory { *; }
-dontwarn io.netty.**

# 忽略常见无关警告
-dontwarn org.slf4j.**
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**
-dontwarn java.nio.file.**
-dontwarn org.fusesource.jansi.**
-dontwarn okhttp3.**
-dontwarn okio.**
