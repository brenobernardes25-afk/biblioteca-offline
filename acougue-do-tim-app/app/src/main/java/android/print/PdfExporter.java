package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Grava em arquivo PDF o conteúdo de um PrintDocumentAdapter (o mesmo que o
 * Android usa para imprimir uma página), sem abrir a tela de impressão.
 * Fica no pacote android.print porque os construtores dos callbacks só são
 * visíveis dentro dele.
 */
public final class PdfExporter {

    public interface Result {
        void done(File file);

        void failed(String message);
    }

    private PdfExporter() {
    }

    public static void write(final PrintDocumentAdapter adapter, final File out, final Result result) {
        final PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();
        try {
            adapter.onStart();
            adapter.onLayout(null, attributes, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
                @Override
                public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                    final ParcelFileDescriptor descriptor;
                    try {
                        descriptor = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                    } catch (Exception e) {
                        result.failed("Não foi possível criar o PDF.");
                        return;
                    }
                    adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, descriptor, new CancellationSignal(),
                            new PrintDocumentAdapter.WriteResultCallback() {
                                @Override
                                public void onWriteFinished(PageRange[] pages) {
                                    close(descriptor);
                                    adapter.onFinish();
                                    if (out.length() > 0) result.done(out);
                                    else result.failed("O PDF ficou vazio.");
                                }

                                @Override
                                public void onWriteFailed(CharSequence error) {
                                    close(descriptor);
                                    adapter.onFinish();
                                    result.failed("Falha ao gravar o PDF.");
                                }

                                @Override
                                public void onWriteCancelled() {
                                    close(descriptor);
                                    adapter.onFinish();
                                    result.failed("Geração do PDF cancelada.");
                                }
                            });
                }

                @Override
                public void onLayoutFailed(CharSequence error) {
                    adapter.onFinish();
                    result.failed("Falha ao montar o PDF.");
                }

                @Override
                public void onLayoutCancelled() {
                    adapter.onFinish();
                    result.failed("Geração do PDF cancelada.");
                }
            }, null);
        } catch (Throwable t) {
            result.failed("Este aparelho não gerou o PDF direto.");
        }
    }

    private static void close(ParcelFileDescriptor d) {
        try {
            d.close();
        } catch (Exception ignored) {
        }
    }
}
