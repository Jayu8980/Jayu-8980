package com.gateee.core

import kotlin.math.*

/**
 * Expression engine behind the GATE-style scientific virtual calculator.
 * Supports + - * / ^ ( ), unary minus, %, n!, constants pi and e, ANS, and
 * sin cos tan asin acos atan sinh cosh tanh ln log sqrt cbrt exp abs.
 * Trig functions follow [degrees]; the same evaluator also recomputes NAT answers from a
 * question's `verification` expression (named variables, radians).
 */
class Calculator(var degrees: Boolean = true) {
    var ans: Double = 0.0
        private set

    fun evaluate(expr: String, variables: Map<String, Double> = emptyMap()): Double {
        val p = Parser(tokenize(expr.replace('×', '*').replace('÷', '/').replace('−', '-').replace("√", "sqrt ").replace("π", "pi")), variables)
        val v = p.parseExpression()
        if (!p.done()) throw IllegalArgumentException("Syntax error")
        if (v.isNaN() || v.isInfinite()) throw ArithmeticException("Math error")
        ans = v
        return v
    }

    private sealed interface Tok {
        data class Num(val v: Double) : Tok
        data class Id(val name: String) : Tok
        data class Op(val c: Char) : Tok
    }

    private fun tokenize(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val m = Regex("""^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""").find(s.substring(i)) ?: throw IllegalArgumentException("Syntax error")
                    out += Tok.Num(m.value.toDouble()); i += m.value.length
                }
                c.isLetter() || c == '_' -> {
                    val m = Regex("""^[A-Za-z_][A-Za-z_0-9]*""").find(s.substring(i))!!
                    out += Tok.Id(m.value); i += m.value.length
                }
                c in "+-*/^()!%," -> { out += Tok.Op(c); i++ }
                else -> throw IllegalArgumentException("Unexpected '$c'")
            }
        }
        return out
    }

    private inner class Parser(val t: List<Tok>, val vars: Map<String, Double>) {
        var p = 0
        fun done() = p == t.size
        private fun peekOp(c: Char) = (t.getOrNull(p) as? Tok.Op)?.c == c
        private fun eat(c: Char) { if (!peekOp(c)) throw IllegalArgumentException("Syntax error"); p++ }

        fun parseExpression(): Double {
            var v = term()
            while (peekOp('+') || peekOp('-')) {
                val plus = peekOp('+'); p++
                v = if (plus) v + term() else v - term()
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                v = when {
                    peekOp('*') -> { p++; v * factor() }
                    peekOp('/') -> { p++; val d = factor(); if (d == 0.0) throw ArithmeticException("Division by zero"); v / d }
                    // implicit multiplication: 2pi, 3(4+1), 2sin(30)
                    t.getOrNull(p) is Tok.Id || t.getOrNull(p) is Tok.Num || peekOp('(') -> v * factor()
                    else -> return v
                }
            }
        }

        private fun factor(): Double {
            if (peekOp('-')) { p++; return -factor() }
            if (peekOp('+')) { p++; return factor() }
            val base = postfix()
            if (peekOp('^')) { p++; return base.pow(factor()) }
            return base
        }

        private fun postfix(): Double {
            var v = primary()
            while (true) {
                v = when {
                    peekOp('!') -> { p++; factorial(v) }
                    peekOp('%') -> { p++; v / 100 }
                    else -> return v
                }
            }
        }

        private fun primary(): Double {
            return when (val tok = t.getOrNull(p) ?: throw IllegalArgumentException("Syntax error")) {
                is Tok.Num -> { p++; tok.v }
                is Tok.Op -> { eat('('); val v = parseExpression(); eat(')'); v }
                is Tok.Id -> {
                    p++
                    if (tok.name in FUNCTIONS && !peekOp('(')) {
                        // calculator-style prefix use without brackets: √3, sin30
                        call(tok.name, listOf(postfix()))
                    } else if (peekOp('(')) {
                        eat('(')
                        val args = mutableListOf(parseExpression())
                        while (peekOp(',')) { p++; args += parseExpression() }
                        eat(')')
                        call(tok.name, args)
                    } else when (tok.name) {
                        in vars -> vars.getValue(tok.name)
                        "pi" -> PI
                        "e" -> E
                        "ANS", "ans" -> ans
                        else -> throw IllegalArgumentException("Unknown '${tok.name}'")
                    }
                }
            }
        }

        private fun toRad(x: Double) = if (degrees) Math.toRadians(x) else x
        private fun fromRad(x: Double) = if (degrees) Math.toDegrees(x) else x

        private val FUNCTIONS = setOf("sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "ln", "log", "log10", "sqrt", "cbrt", "exp", "abs", "deg", "rad")

        private fun call(name: String, a: List<Double>): Double {
            val x = a[0]
            return when (name) {
                "sin" -> sin(toRad(x)).let { if (abs(it) < 1e-12) 0.0 else it }
                "cos" -> cos(toRad(x)).let { if (abs(it) < 1e-12) 0.0 else it }
                "tan" -> tan(toRad(x))
                "asin" -> fromRad(asin(x))
                "acos" -> fromRad(acos(x))
                "atan" -> fromRad(atan(x))
                "sinh" -> sinh(x)
                "cosh" -> cosh(x)
                "tanh" -> tanh(x)
                "ln" -> ln(x)
                "log", "log10" -> log10(x)
                "sqrt" -> sqrt(x)
                "cbrt" -> cbrt(x)
                "exp" -> exp(x)
                "abs" -> abs(x)
                "deg" -> Math.toDegrees(x)
                "rad" -> Math.toRadians(x)
                "pow" -> x.pow(a[1])
                else -> throw IllegalArgumentException("Unknown function $name")
            }
        }

        private fun factorial(v: Double): Double {
            if (v < 0 || v != floor(v) || v > 170) throw ArithmeticException("Math error")
            var r = 1.0
            for (i in 2..v.toInt()) r *= i
            return r
        }
    }
}
