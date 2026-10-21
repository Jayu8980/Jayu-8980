package com.gateee.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gateee.core.Calculator

/** GATE-style scientific virtual calculator. */
@Composable
fun CalculatorDialog(onDismiss: () -> Unit) {
    val calc = remember { Calculator(degrees = true) }
    var expr by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("0") }
    var deg by remember { mutableStateOf(true) }
    var inverse by remember { mutableStateOf(false) }

    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; result = "0" }
            "⌫" -> expr = expr.dropLast(1)
            "=" -> {
                calc.degrees = deg
                result = runCatching { calc.evaluate(expr) }.fold({ v -> formatResult(v) }, { it.message ?: "Error" })
            }
            "sin", "cos", "tan" -> expr += (if (inverse) "a$k(" else "$k(")
            "ln", "log", "√", "exp", "abs" -> expr += "$k("
            "x²" -> expr += "^2"
            "xʸ" -> expr += "^"
            "1/x" -> expr = "1/($expr)"
            "ANS" -> expr += "ANS"
            else -> expr += k
        }
    }

    val rows = listOf(
        listOf("sin", "cos", "tan", "ln", "log"),
        listOf("√", "x²", "xʸ", "(", ")"),
        listOf("7", "8", "9", "÷", "⌫"),
        listOf("4", "5", "6", "×", "C"),
        listOf("1", "2", "3", "−", "π"),
        listOf("0", ".", "e", "+", "="),
        listOf("exp", "1/x", "!", "%", "ANS"),
    )
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = deg, onClick = { deg = true }, label = { Text("Deg") })
                    FilterChip(selected = !deg, onClick = { deg = false }, label = { Text("Rad") })
                    FilterChip(selected = inverse, onClick = { inverse = !inverse }, label = { Text("Inv") })
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                }
                Text(expr.ifBlank { " " }, Modifier.fillMaxWidth(), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge)
                Text(result, Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontSize = 28.sp)
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { k ->
                            val label = if (inverse && k in listOf("sin", "cos", "tan")) "${k}⁻¹" else k
                            Button(
                                onClick = { press(k) },
                                modifier = Modifier.weight(1f),
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                colors = if (k == "=") ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

private fun formatResult(v: Double): String {
    val a = kotlin.math.abs(v)
    return if (a == 0.0 || a in 1e-6..1e12) java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
    else "%.6e".format(v)
}
