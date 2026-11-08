package br.com.waltz.bibliotecadebolso.busca

import java.text.Normalizer

/** Prepara o que a pessoa digitou para a busca nas enciclopédias. */
object Consulta {
    private val PALAVRAS_VAZIAS = setOf(
        "a", "o", "as", "os", "um", "uma", "uns", "umas", "de", "da", "do", "das", "dos", "em", "no", "na",
        "nos", "nas", "por", "para", "pra", "com", "sem", "e", "ou", "que", "como", "qual", "quais", "quando",
        "onde", "porque", "se", "eu", "meu", "minha", "ao", "aos", "fazer", "faz", "posso", "devo", "preciso",
    )

    fun semAcento(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun palavras(t: String): List<String> =
        semAcento(t).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Tira "como", "de", "para"… que só atrapalham. Se sobrar nada, fica o texto original. */
    fun termosPt(t: String): String {
        val p = palavras(t)
        val uteis = p.filter { it !in PALAVRAS_VAZIAS }
        return (uteis.ifEmpty { p }).joinToString(" ")
    }

    /** Versão em inglês pelo dicionário; expressões de duas palavras têm preferência. */
    fun termosEn(t: String): String {
        val p = palavras(t).filter { it !in PALAVRAS_VAZIAS }
        val saida = ArrayList<String>()
        var traduziu = false
        val restantes = ArrayList<String>()
        var i = 0
        while (i < p.size) {
            val dupla = if (i + 1 < p.size) DicionarioPtEn.termos["${p[i]} ${p[i + 1]}"] else null
            val so = DicionarioPtEn.termos[p[i]]
            when {
                dupla != null -> { saida += dupla; traduziu = true; i += 2 }
                so != null -> { saida += so; traduziu = true; i++ }
                else -> { restantes += p[i]; i++ }
            }
        }
        // Palavra sem tradução costuma atrapalhar a busca em inglês, salvo se não houver mais nada.
        return if (traduziu) saida.joinToString(" ") else restantes.joinToString(" ")
    }

    private val PICADA = setOf("picada", "picadas", "mordida", "mordidas", "ferroada", "ferroadas", "acidente", "acidentes")
    private val BICHOS = setOf("cobra", "cobras", "serpente", "serpentes", "jararaca", "cascavel", "coral", "aranha", "aranhas", "escorpiao", "escorpioes", "lacraia")

    /** Os manuais usam termos técnicos que a pessoa não digita sob estresse. Só nos PDFs. */
    fun sinonimos(termos: String): List<String> {
        val p = palavras(termos).toSet()
        return if (p.any { it in BICHOS } && (p.any { it in PICADA } || p.size == 1)) listOf("animais peconhentos") else emptyList()
    }

    /** "a OR b": plano B quando a busca com todas as palavras não acha nada. */
    fun comOu(termos: String): String? {
        val p = termos.split(' ').filter { it.isNotEmpty() }
        return if (p.size > 1) p.joinToString(" OR ") else null
    }
}

/** Atalho da tela inicial: buscas prontas em português e em inglês (seção 8). */
data class Atalho(val nome: String, val icone: String, val pt: List<String>, val en: List<String>)

object Atalhos {
    val todos = listOf(
        Atalho("Água", "agua", listOf("purificar água", "tratamento água"), listOf("water purification", "drinking water")),
        Atalho("Primeiros socorros", "socorro", listOf("primeiros socorros"), listOf("first aid", "emergency treatment")),
        Atalho("Picadas e animais", "picada", listOf("picada cobra", "animais peçonhentos"), listOf("snake bite", "venomous animals")),
        Atalho("Fogo", "fogo", listOf("fazer fogo", "fogueira"), listOf("fire starting", "build a fire")),
        Atalho("Abrigo", "abrigo", listOf("abrigo emergência"), listOf("emergency shelter", "build shelter")),
        Atalho("Plantio", "plantio", listOf("plantio horta", "cultivo"), listOf("vegetable garden", "planting")),
        Atalho("Criação de animais", "criacao", listOf("criação galinhas", "pecuária"), listOf("raising chickens", "livestock")),
        Atalho("Conservar alimentos", "alimentos", listOf("conservação alimentos"), listOf("food preservation", "canning")),
        Atalho("Remédios e plantas", "plantas", listOf("plantas medicinais", "remédios caseiros"), listOf("medicinal plants", "herbal remedies")),
        Atalho("Consertos", "consertos", listOf("conserto", "reparo"), listOf("repair", "how to fix")),
    )
}

/**
 * Dicionário PT→EN de sobrevivência e saúde. Sempre funciona, sem rede.
 * Os ~500 termos da seção 8: a segunda parte está em DicionarioExtra.kt.
 * As chaves ficam sem acento e em minúsculas.
 */
object DicionarioPtEn {
    val termos: Map<String, String> = TERMOS_EXTRA + mapOf(
        "primeiros socorros" to "first aid", "agua potavel" to "drinking water", "picada cobra" to "snake bite",
        "parada cardiaca" to "cardiac arrest", "ataque cardiaco" to "heart attack", "pressao alta" to "hypertension",
        "plantas medicinais" to "medicinal plants", "falta ar" to "shortness of breath", "dor cabeca" to "headache",
        "dor barriga" to "abdominal pain", "corte profundo" to "deep wound", "energia solar" to "solar power",
        "agua" to "water", "purificar" to "purify", "purificacao" to "purification", "filtrar" to "filter",
        "filtro" to "filter", "ferver" to "boil", "poco" to "well", "chuva" to "rain", "rio" to "river",
        "cisterna" to "cistern", "cloro" to "chlorine", "sanitario" to "sanitation", "latrina" to "latrine",
        "esgoto" to "sewage", "lixo" to "waste", "higiene" to "hygiene", "sabao" to "soap",
        "picada" to "bite", "mordida" to "bite", "cobra" to "snake", "serpente" to "snake", "aranha" to "spider",
        "escorpiao" to "scorpion", "abelha" to "bee", "mosquito" to "mosquito", "carrapato" to "tick",
        "veneno" to "venom", "peconhento" to "venomous", "peconhentos" to "venomous", "raiva" to "rabies",
        "socorro" to "rescue", "ferida" to "wound", "ferimento" to "wound", "corte" to "cut",
        "sangramento" to "bleeding", "hemorragia" to "hemorrhage", "queimadura" to "burn", "queimaduras" to "burns",
        "fratura" to "fracture", "osso" to "bone", "entorse" to "sprain", "tala" to "splint", "curativo" to "bandage",
        "torniquete" to "tourniquet", "infeccao" to "infection", "febre" to "fever", "dor" to "pain",
        "tosse" to "cough", "gripe" to "flu", "diarreia" to "diarrhea", "vomito" to "vomiting",
        "desidratacao" to "dehydration", "soro" to "rehydration", "desmaio" to "fainting", "choque" to "shock",
        "convulsao" to "seizure", "engasgo" to "choking", "afogamento" to "drowning", "reanimacao" to "cpr",
        "respiracao" to "breathing", "coracao" to "heart", "infarto" to "heart attack", "derrame" to "stroke",
        "avc" to "stroke", "diabetes" to "diabetes", "asma" to "asthma", "alergia" to "allergy",
        "parto" to "childbirth", "gravidez" to "pregnancy", "gestante" to "pregnancy", "bebe" to "infant",
        "crianca" to "child", "criancas" to "children", "idoso" to "elderly", "dengue" to "dengue",
        "malaria" to "malaria", "colera" to "cholera", "tetano" to "tetanus", "antibiotico" to "antibiotic",
        "remedio" to "medicine", "remedios" to "medicines", "medicamento" to "medication", "vacina" to "vaccine",
        "planta" to "plant", "plantas" to "plants", "erva" to "herb", "ervas" to "herbs", "medicinal" to "medicinal",
        "medicinais" to "medicinal", "fogo" to "fire", "fogueira" to "campfire", "lenha" to "firewood",
        "fogao" to "stove", "carvao" to "charcoal", "isqueiro" to "lighter", "abrigo" to "shelter",
        "barraca" to "tent", "cabana" to "cabin", "telhado" to "roof", "parede" to "wall", "tijolo" to "brick",
        "construcao" to "construction", "madeira" to "wood", "corda" to "rope", "no" to "knot", "nos" to "knots",
        "enchente" to "flood", "inundacao" to "flood", "terremoto" to "earthquake", "incendio" to "wildfire",
        "tempestade" to "storm", "seca" to "drought", "desastre" to "disaster", "emergencia" to "emergency",
        "sobrevivencia" to "survival", "evacuacao" to "evacuation", "plantio" to "planting", "plantar" to "plant",
        "horta" to "vegetable garden", "cultivo" to "growing", "semente" to "seed", "sementes" to "seeds",
        "solo" to "soil", "adubo" to "fertilizer", "composto" to "compost", "compostagem" to "compost",
        "praga" to "pest", "pragas" to "pests", "irrigacao" to "irrigation", "colheita" to "harvest",
        "mandioca" to "cassava", "milho" to "corn", "feijao" to "beans", "arroz" to "rice", "batata" to "potato",
        "tomate" to "tomato", "fruta" to "fruit", "frutas" to "fruit", "criacao" to "raising",
        "animal" to "animal", "animais" to "animals", "galinha" to "chicken", "galinhas" to "chickens",
        "porco" to "pig", "porcos" to "pigs", "cabra" to "goat", "vaca" to "cow", "gado" to "cattle",
        "peixe" to "fish", "peixes" to "fish", "pesca" to "fishing", "abelhas" to "bees", "mel" to "honey",
        "alimento" to "food", "alimentos" to "food", "comida" to "food", "conservar" to "preserve",
        "conservacao" to "preservation", "conserva" to "canning", "secar" to "drying", "salgar" to "salting",
        "defumar" to "smoking", "fermentar" to "fermentation", "pao" to "bread", "leite" to "milk", "carne" to "meat",
        "conserto" to "repair", "consertar" to "repair", "reparo" to "repair", "ferramenta" to "tool",
        "ferramentas" to "tools", "solda" to "welding", "soldar" to "welding", "eletrica" to "electrical",
        "eletricidade" to "electricity", "bateria" to "battery", "gerador" to "generator", "solar" to "solar",
        "motor" to "engine", "carro" to "car", "bicicleta" to "bicycle", "bomba" to "pump", "cano" to "pipe",
        "radio" to "radio", "mapa" to "map", "bussola" to "compass", "navegacao" to "navigation", "roupa" to "clothing",
    )
}
