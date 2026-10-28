# Proguard rules for InterLink
# Keep models and network protocol serialization intact
-keepclassmembers class * implements java.io.Serializable { *; }
-keep class com.interlink.app.model.** { *; }
-keep class com.interlink.app.network.** { *; }
-keep class com.interlink.app.security.** { *; }

