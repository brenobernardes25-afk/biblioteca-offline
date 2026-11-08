package br.com.waltz.bibliotecadebolso.ui

/** O manual do celular. Fica dentro do app para funcionar sem internet. Só diz o que o app já faz. */
val MANUAL: List<Pair<String, String>> = listOf(
    "O que é" to
        "Uma biblioteca de saúde, água, sobrevivência, agricultura e enciclopédias que funciona sem internet. " +
        "Você baixa uma vez e depois usa em qualquer lugar. É de graça, sem conta e sem anúncio.",
    "Qual versão escolher" to
        "Slim mínimo (13 GB): saúde, água, sobrevivência e o essencial da Embrapa. Serve para quase todo celular.\n\n" +
        "Slim (78 GB): tudo do mínimo, mais a Wikipédia em português completa, manuais militares, consertos e muito mais Embrapa.\n\n" +
        "Completo (~830 GB, num SSD): a biblioteca inteira, igual à do computador. O mesmo SSD funciona nos dois.\n\n" +
        "É um app só. Você escolhe a versão dentro dele e pode trocar depois, em Downloads e versão.",
    "Baixar direto no celular" to
        "1. Abra o app e toque em Começar.\n" +
        "2. Escolha a versão e toque em Continuar.\n" +
        "3. Toque em Começar a baixar.\n\n" +
        "Pronto. Pode levar horas. Deixe o celular no Wi-Fi e na tomada. Se a internet cair ou você reiniciar o celular, " +
        "o download continua de onde parou.",
    "Baixar no computador e passar para o celular" to
        "Bom para internet fraca no celular ou para gastar menos bateria.\n\n" +
        "No computador:\n" +
        "1. Abra o PREPARAR_CELULAR.bat e escolha a versão.\n" +
        "2. Quando terminar, ligue o celular no cabo USB e, no aviso do celular, escolha Transferência de arquivos.\n" +
        "3. Copie a pasta BibliotecaDeBolso para a pasta Download do celular.\n\n" +
        "No celular:\n" +
        "4. Abra o app e toque em Já preparei a pasta no computador (na primeira tela de versões) ou em Downloads e versão > Trazer do computador.\n" +
        "5. Toque em Escolher a pasta. O celular já abre na BibliotecaDeBolso: toque em Usar esta pasta e em Permitir.\n\n" +
        "O app confere cada arquivo. O que não veio é baixado pela internet depois.",
    "Como procurar" to
        "Digite na caixa do início, sem se preocupar com acento: agua acha água. " +
        "Os botões grandes (Água, Primeiros socorros, Picadas…) já fazem as buscas mais comuns.\n\n" +
        "Ligue Incluir fontes em inglês para procurar também nos materiais em inglês.\n\n" +
        "O resultado mostra de onde veio o trecho. Em Manuais, um toque abre o PDF já na página certa. Use Anterior e Seguinte para folhear, toque no número para ir a uma página e use dois dedos para ampliar.\n\n" +
        "Durante o download, a busca encontra o que já chegou. O índice dos manuais vem primeiro: sem ele, os manuais são achados só pelo título.",
    "Traduzir" to
        "O botão Traduzir, no início, traduz do inglês para o português, sem internet.\n\n" +
        "Num artigo ou manual em inglês, toque no ícone de tradução no alto. No artigo, vai o trecho que você marcou ou, sem marcar nada, o artigo inteiro. No PDF, vai a página que está aberta.\n\n" +
        "A primeira tradução demora um pouco, porque o tradutor precisa carregar. Um texto longo aparece parágrafo por parágrafo.\n\n" +
        "Tradução automática pode errar. Em dose de remédio ou procedimento de saúde, confira no original.\n\n" +
        "Prefere outro tradutor? Use Abrir em outro tradutor. Para o Google Tradutor funcionar sem internet, baixe antes o pacote de inglês e português no próprio app.",
    "Perguntar à IA (opcional)" to
        "A IA lê os manuais e enciclopédias que estão no aparelho e escreve a resposta em português. Só usa o que está na biblioteca. Os números entre colchetes, como [1], abrem a fonte: confira nela o que for importante.\n\n" +
        "Precisa de 8 GB de RAM: em celular com menos, o botão nem aparece, e a busca continua mostrando o texto original dos manuais. Ela ocupa 2,5 GB. Baixe em Perguntar à IA, ou escolha + IA ao instalar. Com um SSD da biblioteca ligado, ela é lida direto da pasta 4_IA, sem ocupar o celular.\n\n" +
        "Gasta bastante bateria, e a primeira resposta demora, porque a IA precisa carregar. Quando você sai do app, ela sai da memória.\n\n" +
        "A IA pode errar. Em saúde, confira sempre no manual e procure atendimento quando puder.",
    "Downloads e o que faltou" to
        "O ícone de seta no início mostra o andamento. Lá você pausa, retoma e liga ou desliga o Só no Wi-Fi.\n\n" +
        "Se algum arquivo não vier, ele aparece em O que faltou, com o motivo e o botão Tentar de novo. " +
        "Alguns sites recusam o download: o resto da biblioteca funciona normalmente.",
    "Mapa" to
        "O botão Mapa abre o Organic Maps, se estiver instalado. Se não estiver, o app explica como instalar e baixar o mapa do seu estado.",
    "Completo: ligar um SSD que já tem a biblioteca" to
        "1. Ligue o SSD no USB-C do celular.\n" +
        "2. Toque no ícone de disco no início e em Já tenho o SSD com a biblioteca.\n" +
        "3. Escolha a pasta principal do SSD, onde estão 1_Enciclopedia e 5_Acervo, e toque em Usar esta pasta e em Permitir.\n\n" +
        "Só precisa fazer isso uma vez. Na primeira conexão, o app copia o índice de busca para o celular (cerca de 7,5 GB): deixe o app aberto até terminar. Depois, é só ligar o SSD.\n\n" +
        "O app só lê: não grava nada no SSD. Se tirar o cabo, o app avisa e continua com o que estiver no celular. Ligou de novo, volta sozinho.\n\n" +
        "Não abre? O celular precisa ter USB-C com OTG. HD mecânico de 2,5 polegadas costuma precisar de hub com fonte, e HD de 3,5 polegadas não funciona. Veja também se o SSD está em exFAT.",
    "Completo: montar a biblioteca do zero, só pelo celular" to
        "Para quem não tem computador. Se puder usar um, mesmo emprestado, é bem melhor: use o INSTALAR_BIBLIOTECA.bat.\n\n" +
        "Precisa de um SSD de 1 TB em exFAT. O app confere, mas nunca formata nada: formatar apaga tudo, e é com você, num computador.\n\n" +
        "1. Ligue o celular na tomada, com um adaptador USB-C com entrada de carregador, e o SSD nele.\n" +
        "2. Toque no ícone de disco e em Montar a biblioteca completa neste SSD.\n" +
        "3. Leia os avisos, toque em Escolher o SSD e começar e escolha a pasta.\n\n" +
        "Leva dias. Pode desligar a tela. Não tire o cabo: o app retoma, mas cada desconexão atrasa. Enquanto o SSD não for aberto uma vez num computador, os manuais são achados só pelo nome do arquivo.",
    "Se der problema" to
        "Sem espaço: apague fotos e vídeos ou troque para o Slim mínimo, depois toque em Tentar de novo.\n\n" +
        "Parado em Esperando Wi-Fi: conecte no Wi-Fi ou desligue o Só no Wi-Fi.\n\n" +
        "Modo de economia de bateria: o download só continua na tomada ou com o modo desligado.\n\n" +
        "Se continuar com problema, vá em Sobre > Exportar os logs e mande para o Breno.",
    "Antes de desinstalar" to
        "Ao desinstalar, o Android pergunta se quer manter os dados. Diga sim, se pretende voltar a usar: senão, tudo que foi baixado é apagado.",
    "Importante" to
        "Uso pessoal e gratuito. Não substitui atendimento médico: em emergência, procure ajuda sempre que puder.",
)
