# iTextPDF
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# Jsoup
-keep class org.jsoup.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# SLF4J — iTextPDF logs through SLF4J but only the API is on the classpath
# (no implementation). R8 sees a reference to StaticLoggerBinder and refuses
# to build in strict mode. The runtime falls back to NOP_LOGGER when the
# impl is missing, so a -dontwarn is the correct fix.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.**
