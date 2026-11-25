package br.com.acouguedotim.orcamentos;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PdfExporter;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_CAMERA = 11, REQ_GALLERY = 12, REQ_SAVE = 13, REQ_OPEN = 14, REQ_CHOOSER = 15;
    private static final int PNG_WIDTH = 1080;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private WebViewAssetLoader assets;
    private boolean pageReady = false;
    private final List<String> pendingScripts = new ArrayList<>();
    private Uri cameraUri;
    private String pendingSaveContent;
    private ValueCallback<Uri[]> fileCallback;
    private TextRecognizer recognizer;
    private boolean exporting = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WebView.enableSlowWholeDocumentDraw();
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#AC1016"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#F5F5F7"));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        assets = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                for (String js : pendingScripts) web.evaluateJavascript(js, null);
                pendingScripts.clear();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_CHOOSER);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        if (web.getUrl() == null) web.loadUrl(START_URL);
        handleIncoming(getIntent());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
        if (cameraUri != null) out.putString("cameraUri", cameraUri.toString());
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        super.onRestoreInstanceState(in);
        String c = in.getString("cameraUri");
        if (c != null) cameraUri = Uri.parse(c);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    /** Lista ou foto enviada de outro app (ex.: “Compartilhar” no WhatsApp). */
    private void handleIncoming(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType() == null ? "" : intent.getType();
        if (type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) recognize(uri);
        } else {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) send("sharedText", JSONObject.quote(text.toString()));
        }
        intent.setAction(Intent.ACTION_MAIN);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!pageReady) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.handleBack&&window.handleBack())?1:0", v -> {
            if (!"1".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------- JavaScript ← Android ----------

    private void send(String type, String jsonValue) {
        String js = "window.fromAndroid&&window.fromAndroid(" + JSONObject.quote(type) + "," + jsonValue + ")";
        ui.post(() -> {
            if (pageReady) web.evaluateJavascript(js, null);
            else pendingScripts.add(js);
        });
    }

    private void sendError(String type, String message) {
        try {
            send(type, new JSONObject().put("error", message).toString());
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    // ---------- Ponte chamada pelo JavaScript ----------

    final class Bridge {
        @JavascriptInterface
        public String version() {
            return "1.0";
        }

        @JavascriptInterface
        public void shareText(String text, String phone, boolean whatsapp) {
            ui.post(() -> doShareText(text, phone, whatsapp));
        }

        @JavascriptInterface
        public void exportQuote(String kind, String html, String fileName, String caption, String phone, boolean whatsapp) {
            ui.post(() -> doExport(kind, html, fileName, caption, phone, whatsapp));
        }

        @JavascriptInterface
        public void print(String jobName) {
            ui.post(() -> {
                try {
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    PrintAttributes attrs = new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build();
                    pm.print(jobName, web.createPrintDocumentAdapter(jobName), attrs);
                } catch (Exception e) {
                    toast("Não foi possível abrir a impressão.");
                }
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String content, String mime) {
            ui.post(() -> {
                pendingSaveContent = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (ActivityNotFoundException e) {
                    pendingSaveContent = null;
                    toast("Não há app para salvar arquivos neste aparelho.");
                }
            });
        }

        @JavascriptInterface
        public void openFile() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (ActivityNotFoundException e) {
                    toast("Não há app para abrir arquivos neste aparelho.");
                }
            });
        }

        @JavascriptInterface
        public void pickPhoto(boolean camera) {
            ui.post(() -> {
                if (camera) openCamera();
                else openGallery();
            });
        }
    }

    // ---------- Compartilhar ----------

    private String whatsappPackage() {
        for (String p : new String[]{"com.whatsapp.w4b", "com.whatsapp"}) {
            try {
                getPackageManager().getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    private void doShareText(String text, String phone, boolean whatsapp) {
        String wa = whatsapp ? whatsappPackage() : null;
        if (whatsapp && wa == null) toast("WhatsApp não encontrado. Escolha outro app.");
        if (wa != null && phone != null && !phone.isEmpty()) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + phone + "&text=" + Uri.encode(text)));
            i.setPackage(wa);
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startShare(i, wa, "Enviar orçamento");
    }

    private void startShare(Intent i, String pkg, String title) {
        if (pkg != null) {
            Intent direct = new Intent(i);
            direct.setPackage(pkg);
            try {
                startActivity(direct);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(Intent.createChooser(i, title));
        } catch (Exception e) {
            toast("Nenhum app disponível para compartilhar.");
        }
    }

    private void shareFile(File f, String mime, String caption, String phone, boolean whatsapp) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        if (caption != null && !caption.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, caption);
        i.setClipData(ClipData.newRawUri(f.getName(), uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String wa = whatsapp ? whatsappPackage() : null;
        if (whatsapp && wa == null) toast("WhatsApp não encontrado. Escolha outro app.");
        if (wa != null && phone != null && !phone.isEmpty()) i.putExtra("jid", phone + "@s.whatsapp.net");
        startShare(i, wa, "Enviar orçamento");
    }

    private File shareDir() {
        File d = new File(getCacheDir(), "share");
        if (!d.exists()) d.mkdirs();
        File[] old = d.listFiles();
        if (old != null) for (File o : old) {
            if (System.currentTimeMillis() - o.lastModified() > 86_400_000L) o.delete();
        }
        return d;
    }

    // ---------- PDF e imagem do orçamento ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void doExport(String kind, String html, String fileName, String caption, String phone, boolean whatsapp) {
        if (exporting) return;
        exporting = true;
        final boolean png = "png".equals(kind);
        final WebView w = new WebView(this);
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setTextZoom(100);
        w.setBackgroundColor(Color.WHITE);
        w.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        // Fica atrás da tela principal: o usuário não vê.
        root.addView(w, 0, new FrameLayout.LayoutParams(PNG_WIDTH, 400));
        final Runnable cleanup = () -> {
            exporting = false;
            root.removeView(w);
            w.destroy();
            send("exportDone", "true");
        };
        w.setWebViewClient(new WebViewClient() {
            boolean started = false;

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (started) return;
                started = true;
                ui.postDelayed(() -> {
                    if (png) capturePng(w, fileName, caption, phone, whatsapp, cleanup);
                    else writePdf(w, fileName, caption, phone, whatsapp, cleanup);
                }, 350);
            }
        });
        w.loadDataWithBaseURL("https://" + HOST + "/assets/", html, "text/html", "utf-8", null);
        ui.postDelayed(() -> {
            if (exporting && w.getParent() != null) {
                toast("O arquivo demorou demais. Tente de novo.");
                cleanup.run();
            }
        }, 25_000);
    }

    private void writePdf(WebView w, String fileName, String caption, String phone, boolean whatsapp, Runnable cleanup) {
        File out = new File(shareDir(), fileName);
        PdfExporter.write(w.createPrintDocumentAdapter(fileName), out, new PdfExporter.Result() {
            @Override
            public void done(File file) {
                ui.post(() -> {
                    cleanup.run();
                    shareFile(file, "application/pdf", caption, phone, whatsapp);
                });
            }

            @Override
            public void failed(String message) {
                ui.post(() -> {
                    cleanup.run();
                    toast(message + " Abrindo a impressão: escolha “Salvar como PDF”.");
                    send("pdfFallback", "true");
                });
            }
        });
    }

    private void capturePng(WebView w, String fileName, String caption, String phone, boolean whatsapp, Runnable cleanup) {
        w.evaluateJavascript("(function(){var e=document.querySelector('.sheet')||document.body;return Math.ceil(e.getBoundingClientRect().height*window.devicePixelRatio)})()", v -> {
            int h;
            try {
                h = (int) Math.ceil(Double.parseDouble(v));
            } catch (Exception e) {
                h = 1600;
            }
            final int height = Math.max(400, Math.min(h, 12000));
            w.setLayoutParams(new FrameLayout.LayoutParams(PNG_WIDTH, height));
            w.measure(View.MeasureSpec.makeMeasureSpec(PNG_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            w.layout(0, 0, PNG_WIDTH, height);
            ui.postDelayed(() -> {
                try {
                    Bitmap b = Bitmap.createBitmap(PNG_WIDTH, height, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    c.drawColor(Color.WHITE);
                    w.draw(c);
                    File out = new File(shareDir(), fileName);
                    try (OutputStream os = new FileOutputStream(out)) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    b.recycle();
                    cleanup.run();
                    shareFile(out, "image/png", caption, phone, whatsapp);
                } catch (Throwable t) {
                    cleanup.run();
                    toast("Não foi possível gerar a imagem. Envie em PDF ou texto.");
                }
            }, 450);
        });
    }

    // ---------- Foto do pedido → texto (no próprio celular, sem internet) ----------

    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "pedido.jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
            i.setClipData(ClipData.newRawUri("pedido", cameraUri));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            sendError("photo", "Não encontrei o app da câmera. Use “Escolher da galeria”.");
        }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            sendError("photo", "Não encontrei a galeria neste aparelho.");
        }
    }

    private void recognize(Uri uri) {
        send("photoStart", "true");
        new Thread(() -> {
            String preview = null;
            try {
                preview = previewDataUrl(uri);
            } catch (Throwable ignored) {
            }
            final String prev = preview;
            ui.post(() -> {
                try {
                    if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                    InputImage image = InputImage.fromFilePath(this, uri);
                    recognizer.process(image)
                            .addOnSuccessListener(text -> {
                                try {
                                    JSONObject o = new JSONObject();
                                    o.put("text", rebuildLines(text));
                                    if (prev != null) o.put("preview", prev);
                                    send("photo", o.toString());
                                } catch (Exception e) {
                                    sendError("photo", "Falha ao ler o texto da foto.");
                                }
                            })
                            .addOnFailureListener(e -> sendError("photo", "Não consegui ler esta foto. Tente uma foto mais nítida."));
                } catch (Throwable t) {
                    sendError("photo", "Não consegui abrir esta imagem.");
                }
            });
        }).start();
    }

    /** Junta pedaços na mesma altura (ex.: “Contrafilé ....... 3 kg”) e mantém a ordem de cima para baixo. */
    private static String rebuildLines(Text text) {
        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks()) lines.addAll(b.getLines());
        if (lines.isEmpty()) return text.getText();
        for (Text.Line l : lines) if (l.getBoundingBox() == null) return text.getText();
        Collections.sort(lines, (a, b) -> Integer.compare(a.getBoundingBox().centerY(), b.getBoundingBox().centerY()));
        List<List<Text.Line>> rows = new ArrayList<>();
        for (Text.Line l : lines) {
            Rect r = l.getBoundingBox();
            List<Text.Line> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null) {
                Rect lr = last.get(0).getBoundingBox();
                int h = Math.max(1, Math.min(r.height(), lr.height()));
                if (Math.abs(r.centerY() - lr.centerY()) < h * 0.55) {
                    last.add(l);
                    continue;
                }
            }
            List<Text.Line> row = new ArrayList<>();
            row.add(l);
            rows.add(row);
        }
        StringBuilder sb = new StringBuilder();
        for (List<Text.Line> row : rows) {
            Collections.sort(row, (a, b) -> Integer.compare(a.getBoundingBox().left, b.getBoundingBox().left));
            StringBuilder line = new StringBuilder();
            for (Text.Line l : row) {
                if (line.length() > 0) line.append(' ');
                line.append(l.getText());
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private String previewDataUrl(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 900) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o2);
        }
        if (b == null) return null;
        int rotation = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {
        }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 72, bos);
        b.recycle();
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    // ---------- Resultados ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data == null ? null : data.getData();
        switch (requestCode) {
            case REQ_CAMERA:
                if (resultCode == RESULT_OK && cameraUri != null) recognize(cameraUri);
                else send("photoCancel", "true");
                break;
            case REQ_GALLERY:
                if (resultCode == RESULT_OK && uri != null) recognize(uri);
                else send("photoCancel", "true");
                break;
            case REQ_SAVE:
                if (resultCode == RESULT_OK && uri != null && pendingSaveContent != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        os.write(pendingSaveContent.getBytes(StandardCharsets.UTF_8));
                        toast("Backup salvo.");
                    } catch (Exception e) {
                        toast("Não foi possível salvar o backup.");
                    }
                }
                pendingSaveContent = null;
                break;
            case REQ_OPEN:
                if (resultCode == RESULT_OK && uri != null) {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                            if (bos.size() > 3_000_000) throw new Exception("grande");
                        }
                        send("openFile", JSONObject.quote(bos.toString("UTF-8")));
                    } catch (Exception e) {
                        toast("Não foi possível abrir o arquivo.");
                    }
                }
                break;
            case REQ_CHOOSER:
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                    fileCallback = null;
                }
                break;
            default:
                break;
        }
    }
}
