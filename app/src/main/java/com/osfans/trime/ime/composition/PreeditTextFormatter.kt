package com.osfans.trime.ime.composition

object PreeditTextFormatter {
    private val leadingOneRegex = Regex("1(.*)$")
    private val leadingZeroRegex = Regex("0(.*)$")

    fun format(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return when {
            leadingOneRegex.matches(text) -> "壹${text.removePrefix("1")}"
            leadingZeroRegex.matches(text) -> "零${text.removePrefix("0")}"
            else -> text
        }
    }
}
