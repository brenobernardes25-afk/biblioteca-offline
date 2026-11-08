# Prepara no computador a pasta BibliotecaDeBolso, para passar para o celular.
# Usa a biblioteca do HD quando ela está ligada (não baixa de novo). O que não estiver lá vem da internet.
# Pode fechar e rodar de novo: continua de onde parou.
param(
    [ValidateSet('', 'minimo', 'slim')][string]$Versao = '',
    [string]$Destino = '',
    [string]$Lista = '',
    [string]$Biblioteca = '',
    [switch]$SemPausa
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$Aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Lista) { $Lista = Join-Path $Aqui 'lista_celular.tsv' }
if (-not $Destino) { $Destino = Join-Path $Aqui 'BibliotecaDeBolso' }
$Latin1 = [Text.Encoding]::GetEncoding(28591)

function Fim([int]$Codigo) {
    if (-not $SemPausa) { Write-Host ''; Read-Host '  Tecle ENTER para fechar' | Out-Null }
    exit $Codigo
}

function Gb([double]$b) { return ('{0:N1} GB' -f ($b / 1e9)) }

function Pdf-Inteiro([string]$p) {
    $s = [IO.File]::OpenRead($p)
    try {
        $n = [int][Math]::Min(1024, $s.Length)
        if ($n -lt 8) { return $false }
        $b = New-Object byte[] $n
        [void]$s.Read($b, 0, $n)
        if (-not $Latin1.GetString($b).Contains('%PDF')) { return $false }
        [void]$s.Seek($s.Length - $n, 'Begin')
        [void]$s.Read($b, 0, $n)
        return $Latin1.GetString($b).Contains('%%EOF')
    } finally { $s.Dispose() }
}

function Arquivo-Bom($item, [string]$p, [bool]$OutraData) {
    if (-not (Test-Path -LiteralPath $p)) { return $false }
    $t = (Get-Item -LiteralPath $p).Length
    if ($t -le 0) { return $false }
    if ($item.tipo -eq 'zim') {
        if ($OutraData) { return $t -gt 1000000 }
        return [Math]::Abs($t - $item.tam) -le [Math]::Max($item.tam / 50, 1000000)
    }
    if ($t -ne $item.tam) { return $false }
    if ($item.tipo -eq 'pdf') { return (Pdf-Inteiro $p) }
    return $true
}

function Zim-Ja-Baixado($item) {
    if ($item.tipo -ne 'zim' -or -not $item.kiwix) { return $false }
    $d = Join-Path $Destino 'enciclopedias'
    if (-not (Test-Path -LiteralPath $d)) { return $false }
    $z = Get-ChildItem -LiteralPath $d -Filter "$($item.kiwix)_*.zim" -ErrorAction SilentlyContinue |
        Where-Object { $_.Length -gt 1000000 -and $_.Name -match ('^' + [regex]::Escape($item.kiwix) + '_\d{4}-\d{2}[a-z]?\.zim$') }
    return [bool]$z
}

function Achar-Biblioteca {
    if ($Biblioteca) {
        if (Test-Path -LiteralPath (Join-Path $Biblioteca '5_Acervo')) { return $Biblioteca }
        return $null
    }
    $c = @()
    $p = $Aqui
    while ($p) { $c += $p; $p = Split-Path -Parent $p }
    $c += (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue | ForEach-Object { $_.Root })
    foreach ($r in $c) {
        if ((Test-Path -LiteralPath (Join-Path $r '1_Enciclopedia')) -and (Test-Path -LiteralPath (Join-Path $r '5_Acervo'))) { return $r }
    }
    return $null
}

function Baixar([string]$url, [string]$parcial) {
    # Devolve 'ok', '404' ou o motivo. Continua um .parcial que já exista.
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        $saida = & $curl.Source -sS -L -C - --retry 3 --retry-delay 10 --connect-timeout 30 --speed-limit 1024 --speed-time 120 `
            -w '%{http_code}' -o $parcial $url 2>$null
        $codigo = "$saida".Trim()
        if ($LASTEXITCODE -eq 0 -and $codigo -match '^(200|206)$') { return 'ok' }
        if ($codigo -eq '416') { return 'ok' }   # Já estava completo.
        if ($codigo -match '^(200|206)$' -or $codigo -eq '000') { return "conexão interrompida (curl $LASTEXITCODE)" }
        if (Test-Path -LiteralPath $parcial) { Remove-Item -LiteralPath $parcial -Force }
        return $codigo
    }
    try {
        $ja = if (Test-Path -LiteralPath $parcial) { (Get-Item -LiteralPath $parcial).Length } else { 0 }
        $q = [Net.HttpWebRequest]::Create($url)
        $q.Timeout = 60000; $q.ReadWriteTimeout = 120000
        if ($ja -gt 0) { $q.AddRange([long]$ja) }
        $r = $q.GetResponse()
        try {
            $modo = if ($ja -gt 0 -and [int]$r.StatusCode -eq 206) { 'Append' } else { 'Create' }
            $sai = New-Object IO.FileStream($parcial, $modo, 'Write')
            try { $r.GetResponseStream().CopyTo($sai) } finally { $sai.Dispose() }
        } finally { $r.Close() }
        return 'ok'
    } catch [Net.WebException] {
        if ($_.Exception.Response) {
            $c = [int]$_.Exception.Response.StatusCode
            if ($c -eq 416) { return 'ok' }
            return "$c"
        }
        return $_.Exception.Message
    }
}

function Zim-Mais-Novo([string]$kiwix) {
    # O arquivo sumiu do servidor: pergunta ao catálogo qual é a versão atual.
    $nome = $kiwix; $sabor = $null
    foreach ($s in 'maxi', 'nopic', 'mini') { if ($kiwix.EndsWith("_$s")) { $sabor = $s; $nome = $kiwix.Substring(0, $kiwix.Length - $s.Length - 1) } }
    $u = "https://library.kiwix.org/catalog/v2/entries?name=$nome"
    if ($sabor) { $u += "&flavour=$sabor" }
    try {
        $x = [xml](New-Object Net.WebClient).DownloadString($u)
        $melhor = $null
        foreach ($e in $x.feed.entry) {
            if ($e.name -ne $nome) { continue }
            if ($sabor -and $e.flavour -ne $sabor) { continue }
            if (-not $melhor -or $e.updated -gt $melhor.updated) { $melhor = $e }
        }
        if (-not $melhor) { return $null }
        $l = $melhor.link | Where-Object { $_.type -eq 'application/x-zim' } | Select-Object -First 1
        if (-not $l) { return $null }
        return ($l.href -replace '\.meta4$', '')
    } catch { return $null }
}

Write-Host ''
Write-Host '  BIBLIOTECA DE BOLSO: preparar os arquivos para o celular' -ForegroundColor Cyan
Write-Host ''
if (-not (Test-Path -LiteralPath $Lista)) { Write-Host "  Não achei a lista: $Lista" -ForegroundColor Red; Fim 1 }

if (-not $Versao) {
    Write-Host '  1 = Slim mínimo (13 GB): saúde, água, sobrevivência e o essencial da Embrapa'
    Write-Host '  2 = Slim (78 GB): tudo do mínimo, mais a Wikipédia completa e muito mais'
    Write-Host ''
    $r = Read-Host '  Digite 1 ou 2 e tecle ENTER'
    if ($r -eq '2') { $Versao = 'slim' } elseif ($r -eq '1') { $Versao = 'minimo' } else { Write-Host '  Opção inválida.' -ForegroundColor Red; Fim 1 }
}

$itens = @(Get-Content -LiteralPath $Lista -Encoding UTF8 | Select-Object -Skip 1 | ForEach-Object {
    $c = $_ -split "`t"
    if ($c.Count -ge 7) {
        [pscustomobject]@{ pacote = $c[0]; tipo = $c[1]; tam = [long]$c[2]; destino = $c[3]; url = $c[4]; origem = $c[5]; kiwix = $c[6]; titulo = $c[7] }
    }
} | Where-Object { $_.pacote -eq 'minimo' -or ($Versao -eq 'slim' -and $_.pacote -eq 'slim') })

$raiz = Achar-Biblioteca
if ($raiz) { Write-Host "  Biblioteca encontrada em $raiz. O que estiver lá não será baixado de novo." -ForegroundColor Green }
[void](New-Item -ItemType Directory -Force -Path $Destino)

# Espaço: quanto ainda falta gravar nesta pasta.
$falta = [long]0
foreach ($i in $itens) { if (-not (Arquivo-Bom $i (Join-Path $Destino $i.destino) $false) -and -not (Zim-Ja-Baixado $i)) { $falta += $i.tam } }
$letra = (Get-Item -LiteralPath $Destino).PSDrive.Name
$livre = (Get-PSDrive $letra).Free
if ($livre -lt $falta + 200000000) {
    Write-Host ("  Não cabe: faltam {0} nesta pasta, e o disco tem {1} livres." -f (Gb $falta), (Gb $livre)) -ForegroundColor Red
    Write-Host '  Libere espaço, ou coloque este programa em outro disco, e rode de novo.'
    Fim 1
}

$total = ($itens | Measure-Object -Property tam -Sum).Sum
$feitos = [long]0; $n = 0; $copiados = 0; $baixados = 0; $jaTinha = 0
$faltou = New-Object Collections.ArrayList
foreach ($i in $itens) {
    $n++
    $final = Join-Path $Destino $i.destino
    Write-Host ("  [{0}/{1}] {2} de {3}  {4}" -f $n, $itens.Count, (Gb $feitos), (Gb $total), $i.titulo)
    $feitos += $i.tam
    if ((Arquivo-Bom $i $final $false) -or (Zim-Ja-Baixado $i)) { $jaTinha++; continue }
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $final))

    # 1. Da biblioteca do HD, se o arquivo estiver lá e estiver inteiro.
    if ($raiz -and $i.origem) {
        $o = Join-Path $raiz $i.origem
        if (Arquivo-Bom $i $o $false) {
            $tmp = "$final.parcial"
            [IO.File]::Copy($o, $tmp, $true)
            if ((Get-Item -LiteralPath $tmp).Length -eq (Get-Item -LiteralPath $o).Length) {
                if (Test-Path -LiteralPath $final) { Remove-Item -LiteralPath $final -Force }
                Move-Item -LiteralPath $tmp -Destination $final
                $copiados++
                continue
            }
        }
    }
    if ($raiz -and $i.tipo -eq 'zim' -and $i.kiwix) {
        $d = Join-Path $raiz '1_Enciclopedia'
        $z = Get-ChildItem -LiteralPath $d -Filter "$($i.kiwix)_*.zim" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match ('^' + [regex]::Escape($i.kiwix) + '_\d{4}-\d{2}[a-z]?\.zim$') } |
            Sort-Object Name | Select-Object -Last 1
        if ($z -and (Test-Path -LiteralPath "$($z.FullName).ok")) {
            $alvo = Join-Path (Split-Path -Parent $final) $z.Name
            if (-not (Test-Path -LiteralPath $alvo) -or (Get-Item -LiteralPath $alvo).Length -ne $z.Length) {
                [IO.File]::Copy($z.FullName, "$alvo.parcial", $true)
                if (Test-Path -LiteralPath $alvo) { Remove-Item -LiteralPath $alvo -Force }
                Move-Item -LiteralPath "$alvo.parcial" -Destination $alvo
            }
            $copiados++
            continue
        }
    }

    # 2. Da internet.
    $url = $i.url; $parcial = "$final.parcial"
    $res = Baixar $url $parcial
    if ($res -eq '404' -and $i.tipo -eq 'zim' -and $i.kiwix) {
        $novo = Zim-Mais-Novo $i.kiwix
        if ($novo) {
            $final = Join-Path (Split-Path -Parent $final) ($novo.Substring($novo.LastIndexOf('/') + 1))
            $parcial = "$final.parcial"
            $res = Baixar $novo $parcial
        }
    }
    if ($res -eq 'ok') {
        $outra = $final -ne (Join-Path $Destino $i.destino)
        if (Test-Path -LiteralPath $final) { Remove-Item -LiteralPath $final -Force }
        Move-Item -LiteralPath $parcial -Destination $final
        if (Arquivo-Bom $i $final $outra) { $baixados++; continue }
        Remove-Item -LiteralPath $final -Force
        $res = 'veio cortado ou com tamanho errado'
    }
    Write-Host "      não veio: $res" -ForegroundColor Yellow
    [void]$faltou.Add("$($i.titulo)`t$res`t$($i.url)")
}

$arqFaltou = Join-Path $Destino '_o_que_faltou.txt'
if ($faltou.Count -gt 0) { Set-Content -LiteralPath $arqFaltou -Value $faltou -Encoding UTF8 }
elseif (Test-Path -LiteralPath $arqFaltou) { Remove-Item -LiteralPath $arqFaltou -Force }

Write-Host ''
Write-Host ("  Pronto: {0} copiados da biblioteca, {1} baixados, {2} já estavam aqui." -f $copiados, $baixados, $jaTinha) -ForegroundColor Green
if ($faltou.Count -gt 0) {
    $texto = if ($faltou.Count -eq 1) { '1 arquivo não veio' } else { "$($faltou.Count) arquivos não vieram" }
    Write-Host "  $texto (lista em _o_que_faltou.txt). O celular tenta de novo pela internet." -ForegroundColor Yellow
}
Write-Host ''
Write-Host '  AGORA, PARA PASSAR PARA O CELULAR:' -ForegroundColor Cyan
Write-Host '  1. Ligue o celular no cabo USB. No aviso do celular, escolha Transferência de arquivos.'
Write-Host "  2. Copie a pasta $Destino"
Write-Host '     para a pasta Download do celular.'
Write-Host '  3. No app, toque em Já preparei a pasta no computador e escolha a pasta BibliotecaDeBolso.'
Write-Host ''
Write-Host '  Se a cópia for interrompida, é só copiar de novo: o app confere arquivo por arquivo.'
Fim 0
