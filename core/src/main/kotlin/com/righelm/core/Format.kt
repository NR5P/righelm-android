package com.righelm.core

import java.util.Locale

/**
 * `String.format` with ASCII digits and a decimal point whatever the phone's
 * language: CAT commands and frequencies must never be localised.
 */
fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)
