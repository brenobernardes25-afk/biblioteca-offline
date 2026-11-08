package br.com.waltz.bibliotecadebolso

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.waltz.bibliotecadebolso.ui.AppVM
import br.com.waltz.bibliotecadebolso.ui.Raiz
import br.com.waltz.bibliotecadebolso.ui.TemaBolso

class MainActivity : ComponentActivity() {
    /** Seção 10: a IA sai da memória quando o app vai para segundo plano. */
    override fun onStop() {
        super.onStop()
        (application as br.com.waltz.bibliotecadebolso.BolsoApp).ia.aoSairDoApp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaBolso {
                Raiz(viewModel<AppVM>())
            }
        }
    }
}
