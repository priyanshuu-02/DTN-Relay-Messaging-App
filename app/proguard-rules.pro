# R8 rules for the release / staging builds. Libraries that ship their own consumer rules
# (Room, Hilt, WorkManager, Compose, coroutines, zxing-android-embedded) need nothing here.

# ── Readable crash reports ─────────────────────────────────────────────────
# Keep file/line info (Play Console de-obfuscates stack traces with the uploaded mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── HiveMQ MQTT client (internet relay) ───────────────────────────────────
# Netty and JCTools look fields up by name (Unsafe offsets / atomic field updaters), so member
# names must survive. Rules from the HiveMQ client's Android installation guide.
-keepclassmembernames class io.netty.** { *; }
-keepclassmembernames class org.jctools.** { *; }

# Netty references optional native transports, compression codecs, logging back-ends and
# server-side TLS engines that are not on Android and never loaded by the client.
-dontwarn io.netty.**
-dontwarn org.jctools.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
-dontwarn reactor.blockhound.**
-dontwarn sun.security.**
-dontwarn javax.naming.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.graalvm.nativeimage.**

# ── BouncyCastle (X25519 / Ed25519 / HKDF, used through the lightweight API) ──
# Optional LDAP certificate-store code paths reference JNDI (javax.naming, covered above).
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.x509.util.LDAPStoreHelper
