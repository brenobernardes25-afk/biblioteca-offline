# libkiwix/libzim: as classes são acessadas por JNI, pelo nome.
-keep class org.kiwix.** { *; }
# A parte nativa da IA chama aoPedaco pelo nome.
-keep class br.com.waltz.bibliotecadebolso.ia.MotorIa { *; }
-keep class com.getkeepsafe.relinker.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
