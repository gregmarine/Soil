package com.symmetricalpalmtree.soil.seam

/**
 * The names an app may give its own tables and indexes: `^[a-z][a-z0-9_]{0,62}$`, and not in a
 * reserved space. The reserved prefixes protect what a file holds besides the app's tables —
 * Soil's own `soil_*` tables and SQLite's `sqlite_*` catalog — and shut two doors that are
 * ordinary words rather than keywords: the `pragma_*` table-valued functions, which read what
 * `PRAGMA` is refused for, and SQLCipher's `sqlcipher_*` functions, which can export a file.
 * [SeamSql] refuses **any** identifier in one of them, quoted or not, in every statement, and
 * any `'…'` string that starts with one, since SQLite reads a string as a name where a name
 * belongs (`SELECT * FROM 'soil_meta'`).
 */
object SeamNames {
    private val SHAPE = Regex("^[a-z][a-z0-9_]{0,62}$")
    val RESERVED_PREFIXES: List<String> = listOf("soil_", "sqlite_", "pragma_", "sqlcipher_")

    fun isReserved(name: String): Boolean {
        val n = name.lowercase()
        return RESERVED_PREFIXES.any { n.startsWith(it) }
    }

    fun isValid(name: String): Boolean = SHAPE.matches(name) && !isReserved(name)
}

/**
 * **The statement checker.** Pure and shared, so an app can check what Soil will refuse, and Soil
 * checks every statement again before it runs. A small tokenizer, honest about `'…'`, `"…"`,
 * `` `…` ``, `[…]`, `--` and `/* */`, feeds a handful of rules. Nothing here parses SQL: it only
 * refuses the shapes the seam does not carry.
 *
 * - **One statement**: no `;` outside literals and comments (one trailing `;` is tolerated).
 * - **The head keyword** decides the kind: `SELECT` / `WITH` for [checkQuery]; `INSERT` /
 *   `REPLACE` / `UPDATE` / `DELETE` / `WITH` for [checkExec]; `CREATE` / `ALTER` for [checkDdl].
 *   A query may not carry a write under `WITH`.
 * - **Refused anywhere**: `ATTACH DETACH PRAGMA VACUUM CREATE DROP ALTER BEGIN COMMIT ROLLBACK
 *   SAVEPOINT RELEASE REINDEX ANALYZE load_extension`. DDL keeps its own head word, refuses a
 *   second, and refuses `DROP VIEW TRIGGER VIRTUAL TEMP TEMPORARY`.
 * - **Reserved names**: every identifier, bare or quoted, in a [SeamNames] reserved space, and
 *   every `'…'` string that starts with one. The one exception is the link mirror: an exec that
 *   is exactly one of [SeamLinks]' five writes (`PUT`, `PUT_BIBLE`, `PUT_CAL`, `DROP`,
 *   `DROP_PAGE`), whitespace aside, may name `soil_link` as its table.
 * - **Positional binds only** (`?`, `?NNN`).
 * - **DDL shape**: `CREATE TABLE`, `CREATE [UNIQUE] INDEX … ON`, `ALTER TABLE … ADD [COLUMN]`,
 *   each with its `IF NOT EXISTS`. The name made or altered is [SeamNames.isValid] and bare.
 *
 * Every refusal is an `IllegalArgumentException` whose message says which rule, and never quotes
 * a literal: what a person wrote does not reach a log through here.
 */
object SeamSql {

    enum class Kind { QUERY, EXEC, DDL }

    /** Refuses anything but a single `SELECT` / `WITH … SELECT`. */
    fun checkQuery(sql: String) = check(sql, Kind.QUERY)

    /** Refuses anything but a single `INSERT` / `REPLACE` / `UPDATE` / `DELETE` / `WITH …` write. */
    fun checkExec(sql: String) = check(sql, Kind.EXEC)

    /** Refuses anything but one supported DDL statement. */
    fun checkDdl(sql: String) = check(sql, Kind.DDL)

    /**
     * How many binds [sql] takes, as SQLite numbers them, in order: `?N` is N, and a bare `?` is
     * one more than the largest number given so far (`?5, ?` takes 6).
     */
    fun bindCount(sql: String): Int {
        var highest = 0
        for (t in tokenize(sql)) {
            if (t.kind != T.BIND) continue
            highest = if (t.text.length == 1) minOf(highest, Int.MAX_VALUE - 1) + 1 else maxOf(highest, t.text.drop(1).toIntOrNull() ?: Int.MAX_VALUE)
        }
        return highest
    }

    fun check(sql: String, kind: Kind) {
        val tokens = statementTokens(sql)
        require(tokens.isNotEmpty()) { "empty statement" }
        val head = tokens[0]
        require(head.kind == T.WORD) { "a statement starts with a keyword" }
        val heads = when (kind) {
            Kind.QUERY -> QUERY_HEADS
            Kind.EXEC -> EXEC_HEADS
            Kind.DDL -> DDL_HEADS
        }
        require(head.upper in heads) { "${kind.name.lowercase()} cannot start with ${head.text}" }
        val deny = if (kind == Kind.DDL) DDL_DENY else DENY
        // The one reserved name an exec may carry: the link mirror, as the table of one of
        // SeamLinks' own five writes, at that one position. Anywhere else, it is refused.
        val mirrorAt = if (kind == Kind.EXEC && isMirrorWrite(sql)) 2 else -1
        for ((i, t) in tokens.withIndex()) {
            when (t.kind) {
                T.WORD -> {
                    require(t.upper !in deny) { "${t.text} is not allowed" }
                    if (kind == Kind.DDL && i > 0) require(t.upper !in DDL_HEADS) { "one statement: ${t.text} again" }
                    if (kind == Kind.QUERY) {
                        require(t.upper !in QUERY_WRITE_WORDS) { "a query cannot ${t.text}" }
                        if (t.upper == "REPLACE" && tokens.getOrNull(i + 1)?.isWord("INTO") == true) {
                            throw IllegalArgumentException("a query cannot REPLACE INTO")
                        }
                    }
                    require(i == mirrorAt || !SeamNames.isReserved(t.text)) { "${t.text} is a reserved name" }
                }
                T.QUOTED -> require(!SeamNames.isReserved(t.text)) { "a quoted name is in a reserved space" }
                // SQLite takes a string for a name where a name belongs: `FROM 'soil_meta'`.
                T.STRING -> require(!SeamNames.isReserved(t.text)) { "a string is in a reserved space" }
                T.NAMED_BIND -> throw IllegalArgumentException("named binds are not supported: use ?")
                else -> Unit
            }
        }
        require(bindCount(sql) <= SeamLimits.MAX_ARGS) { "more than ${SeamLimits.MAX_ARGS} binds" }
        if (kind == Kind.DDL) checkDdlShape(tokens)
    }

    /**
     * Whether [sql] is a write of the link mirror: exactly one of the five statements [SeamLinks]
     * gives an app, whitespace and a trailing `;` aside. Soil re-reads the mirror after a batch
     * that holds one. Never throws: anything else is simply false.
     */
    fun writesLinkMirror(sql: String): Boolean = isMirrorWrite(sql)

    private val MIRROR_WRITES: Set<String> by lazy {
        listOf(SeamLinks.PUT, SeamLinks.PUT_BIBLE, SeamLinks.PUT_CAL, SeamLinks.DROP, SeamLinks.DROP_PAGE)
            .map(::normalized).toSet()
    }

    private fun isMirrorWrite(sql: String): Boolean =
        sql.length <= SeamLimits.MAX_SQL_CHARS && normalized(sql) in MIRROR_WRITES

    /** Runs of whitespace as one space, no ends, no trailing `;`. */
    private fun normalized(sql: String): String =
        sql.trim().removeSuffix(";").trim().split(WHITESPACE).joinToString(" ")

    private val WHITESPACE = Regex("\\s+")

    // ── DDL shape ──────

    private fun checkDdlShape(t: List<Token>) {
        var i = 1
        fun word(): Token = t.getOrNull(i) ?: throw IllegalArgumentException("incomplete DDL")
        fun expect(vararg any: String): Token {
            val w = word()
            require(w.kind == T.WORD && w.upper in any) { "expected ${any.joinToString(" | ")}" }
            i++
            return w
        }
        fun skipIfNotExists() {
            if (t.getOrNull(i)?.isWord("IF") == true) {
                i++
                expect("NOT")
                expect("EXISTS")
            }
        }
        fun ownName() {
            val w = word()
            require(w.kind == T.WORD) { "a table or index name is bare, not quoted" }
            require(SeamNames.isValid(w.text)) { "'${w.text}' is not a name an app may use (lowercase, [a-z0-9_], 1..63)" }
            i++
        }
        when (t[0].upper) {
            "CREATE" -> {
                val what = expect("TABLE", "INDEX", "UNIQUE")
                if (what.upper == "UNIQUE") expect("INDEX")
                skipIfNotExists()
                ownName()
                if (what.upper != "TABLE") {
                    expect("ON")
                    ownName()
                }
            }
            "ALTER" -> {
                expect("TABLE")
                ownName()
                expect("ADD")
            }
        }
    }

    // ── Tokens ──────

    enum class T { WORD, QUOTED, STRING, NUMBER, BIND, NAMED_BIND, PUNCT, SEMI }

    class Token(val kind: T, val text: String) {
        val upper: String get() = text.uppercase()
        fun isWord(w: String): Boolean = kind == T.WORD && upper == w
        override fun toString() = kind.name
    }

    /** The tokens of one statement: length-capped, one trailing `;` dropped, any other refused. */
    private fun statementTokens(sql: String): List<Token> {
        require(sql.isNotBlank()) { "empty statement" }
        require(sql.length <= SeamLimits.MAX_SQL_CHARS) {
            "statement exceeds ${SeamLimits.MAX_SQL_CHARS} chars (${sql.length})"
        }
        val tokens = tokenize(sql)
        val body = if (tokens.lastOrNull()?.kind == T.SEMI) tokens.dropLast(1) else tokens
        require(body.none { it.kind == T.SEMI }) { "one statement per call: no ';'" }
        return body
    }

    fun tokenize(sql: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = sql.length
        fun isIdentStart(c: Char) = c.isLetter() || c == '_'
        fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        while (i < n) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    val end = sql.indexOf('\n', i)
                    i = if (end < 0) n else end + 1
                }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    val end = sql.indexOf("*/", i + 2)
                    require(end >= 0) { "unterminated comment" }
                    i = end + 2
                }
                c == '\'' -> { val (text, next) = quoted(sql, i, '\'', '\''); out += Token(T.STRING, text); i = next }
                c == '"' -> { val (text, next) = quoted(sql, i, '"', '"'); out += Token(T.QUOTED, text); i = next }
                c == '`' -> { val (text, next) = quoted(sql, i, '`', '`'); out += Token(T.QUOTED, text); i = next }
                c == '[' -> { val (text, next) = quoted(sql, i, '[', ']'); out += Token(T.QUOTED, text); i = next }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < n && isIdentPart(sql[j])) j++
                    out += Token(T.WORD, sql.substring(i, j)); i = j
                }
                c.isDigit() || (c == '.' && i + 1 < n && sql[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '.' || sql[j] == '_')) j++
                    out += Token(T.NUMBER, sql.substring(i, j)); i = j
                }
                c == '?' -> {
                    var j = i + 1
                    while (j < n && sql[j].isDigit()) j++
                    out += Token(T.BIND, sql.substring(i, j)); i = j
                }
                (c == ':' || c == '@' || c == '$') && i + 1 < n && isIdentStart(sql[i + 1]) -> {
                    var j = i + 2
                    while (j < n && isIdentPart(sql[j])) j++
                    out += Token(T.NAMED_BIND, sql.substring(i, j)); i = j
                }
                c == ';' -> { out += Token(T.SEMI, ";"); i++ }
                else -> { out += Token(T.PUNCT, c.toString()); i++ }
            }
        }
        return out
    }

    /** A quoted run from [start] (at the opening quote): the inner text with doubled closers
     *  unescaped, and the index after the closing quote. */
    private fun quoted(sql: String, start: Int, open: Char, close: Char): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            if (c == close) {
                if (open == close && i + 1 < n && sql[i + 1] == close) { sb.append(close); i += 2; continue }
                return sb.toString() to i + 1
            }
            sb.append(c); i++
        }
        throw IllegalArgumentException("unterminated quote")
    }

    // ── Word sets ──────

    private val QUERY_HEADS = setOf("SELECT", "WITH")
    private val EXEC_HEADS = setOf("INSERT", "REPLACE", "UPDATE", "DELETE", "WITH")
    private val DDL_HEADS = setOf("CREATE", "ALTER")
    private val QUERY_WRITE_WORDS = setOf("INSERT", "UPDATE", "DELETE")

    /** Refused anywhere in a query or an exec. */
    val DENY: Set<String> = setOf(
        "ATTACH", "DETACH", "PRAGMA", "VACUUM", "CREATE", "DROP", "ALTER", "BEGIN", "COMMIT",
        "ROLLBACK", "SAVEPOINT", "RELEASE", "REINDEX", "ANALYZE", "LOAD_EXTENSION",
    )

    /** Refused anywhere in DDL: the list above without the DDL heads, and the kinds of object the
     *  seam does not carry. */
    val DDL_DENY: Set<String> = (DENY - DDL_HEADS) + setOf("VIEW", "TRIGGER", "VIRTUAL", "TEMP", "TEMPORARY")
}
