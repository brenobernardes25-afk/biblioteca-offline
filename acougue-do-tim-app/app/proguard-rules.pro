# Métodos chamados pelo JavaScript da tela
-keepclassmembers class br.com.acouguedotim.orcamentos.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class android.print.PdfExporter { *; }
-keep class android.print.PdfExporter$* { *; }
