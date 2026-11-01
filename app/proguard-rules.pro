# R8 rules for the release build. Hilt, kotlinx.serialization, OkHttp and Tink ship
# their own consumer rules; the rules below cover what this app adds on top.

# The xterm.js page calls these by name through `TroupeBridge` (XtermWebViewRenderer.Bridge).
-keepclassmembers class dev.troupe.android.terminal.render.XtermWebViewRenderer$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization: keep the generated serializers of this app's @Serializable classes
# (protocol models, stored servers, navigation routes), as in the library's README.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses
-if @kotlinx.serialization.Serializable class dev.troupe.android.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class dev.troupe.android.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class dev.troupe.android.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class dev.troupe.android.**$$serializer { *; }

# Stack traces from the release APK stay readable with the uploaded mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
