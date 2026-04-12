# iTextPDF
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# Jsoup
-keep class org.jsoup.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
