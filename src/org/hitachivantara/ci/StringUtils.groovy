package org.hitachivantara.ci

import java.text.Normalizer
import java.time.Duration
import java.util.regex.Matcher
import java.util.regex.Pattern

class StringUtils implements Serializable {
  static String DEFAULT_REPLACER = '_'
  static Pattern DIACRITICALMARKS = Pattern.compile('[\\p{InCombiningDiacriticalMarks}\\p{IsLm}\\p{IsSk}]+')
  static Map NONDIACRITICS = [
      // crap chars with no semantics
      '\"': '',
      '\'': '',

      // relevant chars as separation
      ' ' : DEFAULT_REPLACER,
      '[' : DEFAULT_REPLACER,
      ']' : DEFAULT_REPLACER,
//      '(' : DEFAULT_REPLACER,
//      ')' : DEFAULT_REPLACER,
      '=' : DEFAULT_REPLACER,
      '!' : DEFAULT_REPLACER,
      '/' : DEFAULT_REPLACER,
      '\\': DEFAULT_REPLACER,
      '&' : DEFAULT_REPLACER,
//      ',' : DEFAULT_REPLACER,
      '?' : DEFAULT_REPLACER,
      '|' : DEFAULT_REPLACER,
      '+' : DEFAULT_REPLACER,
      '*' : DEFAULT_REPLACER,
      '<' : DEFAULT_REPLACER,
      '>' : DEFAULT_REPLACER,
      ';' : DEFAULT_REPLACER,
      ':' : DEFAULT_REPLACER,
      '#' : DEFAULT_REPLACER,
  ]

  static String normalizeString(String orig) {
    String str = orig
    str = stripDiacritics(str)
    str = stripNonDiacritics(str)
    return str
  }

  static String stripDiacritics(String str) {
    return DIACRITICALMARKS.matcher(Normalizer.normalize(str, Normalizer.Form.NFD)).replaceAll('')
  }

  static String stripNonDiacritics(String orig) {
    StringBuffer sb = ''<<''
    orig.each { c ->
      sb << (NONDIACRITICS.get(c) ?: c)
    }
    return sb.toString()
  }

  static String truncate(String str, int size) {
    return str.size() > size ? str.take(size) + '...' : str
  }

/**
 * Turns a Duration object into a nicer string representation
 * @param duration the number of milliseconds
 * @return
 */
  static String formatDuration(Long duration) {
    new PrettyPrinter(Duration.ofMillis(duration)).toPrettyPrint()
  }

  static StringBuilder replaceAll(StringBuilder self, Pattern pattern, String replacement) {
    Matcher m = pattern.matcher(self)
    int index = 0
    while (m.find(index)) {
      self.replace(m.start(), m.end(), replacement)
      index = m.start() + replacement.size()
    }
    return self
  }

  static String fixNull(Object s) {
    s ? String.valueOf(s) : ''
  }

  static boolean isEmpty(Object obj) {
    fixNull(obj).trim().isEmpty()
  }

  static String wordWrap(String text, int maxLineSize = 70) {
    def words = text.split()
    def lines = ['']
    words.each { word ->
      def lastLine = (lines[-1] + ' ' + word).trim()
      if (lastLine.size() <= maxLineSize) {
        // Change last line.
        lines[-1] = lastLine
      } else {
        // Add word as new line.
        lines << word
      }
    }
    return lines.join(' \\\n')
  }
}
