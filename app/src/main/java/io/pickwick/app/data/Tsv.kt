package io.pickwick.app.data

/**
 * Free-text fields in the hand-rolled TSV stores must never carry a tab (it
 * splits the row) or a line break (readLines() treats \n, \r and \r\n all as
 * row boundaries) — any of them silently corrupts the row on the next parse.
 * Lossy on purpose: these are display strings, and a space reads fine.
 */
internal fun String.tsvCell(): String =
    replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
