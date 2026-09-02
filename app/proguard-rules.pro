# JavaMail (αποστολή μέσω Gmail API: χτίζουμε MIME με javax.mail)
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**

# SQLCipher
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Tink, μέσω androidx.security-crypto
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**

# Το JS bridge καλείται από JavaScript μέσω ανάκλασης — τα ονόματα δεν αλλάζουν.
-keepclassmembers class gr.scanmydata.taxcenter.engine.** {
    @android.webkit.JavascriptInterface <methods>;
}
