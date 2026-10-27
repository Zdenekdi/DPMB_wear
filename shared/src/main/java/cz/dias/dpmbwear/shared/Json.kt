package cz.dias.dpmbwear.shared

/**
 * Minimální JSON pro payload mezi telefonem a hodinkami.
 *
 * Vlastní místo Gsonu nebo org.json: org.json v jednotkových testech na JVM
 * nefunguje (je to stub z android.jar) a Gson by kvůli polím místo objektů
 * stejně potřeboval ruční adaptéry. Parser zvládá celý JSON, writer jen to,
 * co payload potřebuje.
 */
internal object Json {

    class Writer {
        private val sb = StringBuilder(1024)
        private val needComma = ArrayDeque<Boolean>()

        private fun sep() {
            if (needComma.isNotEmpty()) {
                if (needComma.last()) sb.append(',') else needComma[needComma.lastIndex] = true
            }
        }

        fun obj(body: Writer.() -> Unit) {
            sep(); sb.append('{'); needComma.addLast(false)
            body()
            needComma.removeLast(); sb.append('}')
        }

        /** Klíč uvnitř [obj]; hodnotu zapíše [value]. */
        fun field(name: String, value: Writer.() -> Unit) {
            sep(); quote(name); sb.append(':')
            // Hodnota po dvojtečce nesmí dostat čárku.
            needComma.addLast(false)
            value()
            needComma.removeLast()
        }

        fun arr(body: Writer.() -> Unit) {
            sep(); sb.append('['); needComma.addLast(false)
            body()
            needComma.removeLast(); sb.append(']')
        }

        fun <T> arr(items: List<T>, each: Writer.(T) -> Unit) = arr { items.forEach { each(it) } }

        fun str(v: String) { sep(); quote(v) }
        fun num(v: Int) { sep(); sb.append(v) }
        fun num(v: Long) { sep(); sb.append(v) }
        fun nul() { sep(); sb.append("null") }

        /** Desetinné číslo zaokrouhlené na [decimals] míst (5 míst ≈ 1 m). */
        fun num(v: Double, decimals: Int) {
            sep()
            require(v.isFinite()) { "JSON neumí $v" }
            var scale = 1L
            repeat(decimals) { scale *= 10 }
            val rounded = Math.round(v * scale)
            val neg = rounded < 0
            val abs = if (neg) -rounded else rounded
            if (neg) sb.append('-')
            sb.append(abs / scale)
            val frac = abs % scale
            if (frac != 0L) {
                sb.append('.')
                sb.append(frac.toString().padStart(decimals, '0').trimEnd('0'))
            }
        }

        private fun quote(s: String) {
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }

        fun string(): String = sb.toString()
        fun bytes(): ByteArray = sb.toString().encodeToByteArray()
    }

    /** Vrací Map<String, Any?>, List<Any?>, String, Long, Double, Boolean nebo null. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (p.i != text.length) p.fail("Nečekaný znak za koncem")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON na pozici $i: $msg")

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) fail("Konec vstupu")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Nečekaný znak '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Očekáváno $word")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("Očekáván klíč")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("Očekávána ':'")
                i++
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("Očekávána ',' nebo '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("Očekávána ',' nebo ']'")
                }
            }
        }

        private fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Neukončený řetězec")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Neukončený escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Krátký \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("Neznámý escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true; i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val t = s.substring(start, i)
            if (t == "-" || t.isEmpty()) fail("Neplatné číslo")
            return if (isDouble) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }
}
