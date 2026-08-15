package com.breadmoirai.garnet.editor.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wall-clock formatting shared by the Local History and Structure Info panels, so a revision's
 * timestamp and the open structure's last-saved time read identically.
 *
 * [Locale.ROOT] rather than the default locale: this is a fixed 24-hour pattern, and a host locale
 * that formats it differently would make the two panels disagree with each other.
 */
private val CLOCK_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

fun formatClock(millis: Long): String = CLOCK_FORMAT.format(Date(millis))
