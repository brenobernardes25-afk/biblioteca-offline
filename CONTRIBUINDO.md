# Como contribuir

Obrigado por querer ajudar! A biblioteca cresce com sugestões de quem usa.

## Sugerir um conteúdo novo (livro, manual, enciclopédia, curso)

1. Vá em **[Issues › New issue](https://github.com/brenobernardes25-afk/biblioteca-offline/issues/new/choose)**
   e escolha **Sugerir conteúdo**.
2. Preencha:
   - **o que é** e por que ajuda numa situação difícil;
   - **o link** de onde baixar;
   - **a licença** (domínio público, Creative Commons, uso livre...). Se não souber, diga onde viu;
   - **a categoria** (saúde, agricultura, sobrevivência, enciclopédia, literatura...) e o idioma;
   - **o tamanho**, se souber.
3. O autor confere a licença e a fonte. Se aprovar, o conteúdo entra numa versão nova do kit e aparece aqui
   nos Releases.

### O que entra

- Conteúdo **que pode ser redistribuído**: domínio público, Creative Commons ou licença que permita.
- Fontes **confiáveis**: governos, universidades, organizações de saúde, projetos conhecidos (Kiwix, Wikimedia...).
- De preferência **em português**, ou prático mesmo em outro idioma.

### O que não entra

- Material pirata ou com licença que proíbe copiar.
- Conteúdo de saúde sem fonte confiável.
- Arquivos que só existem num lugar que bloqueia downloads automáticos.

## Mexer no código

- **Celular (Android):** pasta [`celular/`](celular/). O [README](celular/README.md) explica como compilar.
  A lista do que o app baixa é o `celular/pacotes.json`.
- **Computador (Windows):** pasta [`computador/`](computador/). A lista da biblioteca é o
  `_Baixador/instalador/manifesto.tsv.gz`, e as categorias do menu "o que baixar" ficam em
  `_Baixador/instalador/categorias.py`.

Para mudanças no código, abra um **pull request** explicando o que muda e como você testou.

## Achou um erro?

Abra uma issue dizendo:
- a versão (computador ou celular, e qual);
- o que você fez;
- o que apareceu.

Um print da tela ajuda muito. No celular, **Sobre › Exportar os logs** gera um arquivo para anexar.
