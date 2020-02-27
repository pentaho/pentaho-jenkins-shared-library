/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.build.BuildException

import java.text.Normalizer
import java.time.Duration
import java.util.regex.Matcher
import java.util.regex.Pattern

class StringUtils implements Serializable {
  static String DEFAULT_REPLACER = '_'
  static Pattern DIACRITICALMARKS = Pattern.compile('[\\p{InCombiningDiacriticalMarks}\\p{IsLm}\\p{IsSk}]+')
  static Map NONDIACRITICS = [
      // crop chars with no semantics
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

  @NonCPS
  static String normalizeString(String orig) {
    String str = orig
    str = stripDiacritics(str)
    str = stripNonDiacritics(str)
    return str
  }

  @NonCPS
  static String stripDiacritics(String str) {
    return DIACRITICALMARKS.matcher(Normalizer.normalize(str, Normalizer.Form.NFD)).replaceAll('')
  }

  @NonCPS
  static String stripNonDiacritics(String orig) {
    StringBuffer sb = ''<<''
    orig.each { c ->
      sb << (NONDIACRITICS.get(c) ?: c)
    }
    return sb.toString()
  }

  @NonCPS
  static String truncate(String str, int size) {
    return str.size() > size ? str.take(size) + '...' : str
  }

/**
 * Turns a Duration object into a nicer string representation
 * @param duration the number of milliseconds
 * @return
 */
  @NonCPS
  static String formatDuration(Long duration) {
    new PrettyPrinter(Duration.ofMillis(duration)).toPrettyPrint()
  }

  @NonCPS
  static StringBuilder replaceAll(StringBuilder self, Pattern pattern, String replacement) {
    Matcher m = pattern.matcher(self)
    int index = 0
    while (m.find(index)) {
      self.replace(m.start(), m.end(), replacement)
      index = m.start() + replacement.size()
    }
    return self
  }

  @NonCPS
  static String fixNull(Object s) {
    s ? String.valueOf(s) : ''
  }

  @NonCPS
  static boolean isEmpty(Object obj) {
    fixNull(obj).trim().isEmpty()
  }

  @NonCPS
  static String wordWrap(String text, int maxLineSize = 70, String lineBreak = ' \\\n') {
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
    return lines.join(lineBreak)
  }

  @NonCPS
  static void printStackTrace(Throwable t, PrintWriter w) {
    w.println(t)

    if (t instanceof BuildException) {
      w.println("    with command: '${t.command}'")
    }

    def exclusions = ['org.codehaus', 'com.cloudbees', 'sun', 'java', 'hudson', 'org.jenkinsci', 'jenkins', 'groovy']
    for (traceElement in t.getStackTrace()) {
      String className = traceElement.className
      if (className == '___cps') {
        break
      }
      if (exclusions.any { className.startsWith(it) }) {
        continue
      }
      w.println("  at $traceElement")
    }

    Throwable cause = t.cause
    if (cause != null) {
      w.print("Caused by: ")
      printStackTrace(cause, w)
    }
  }
}
