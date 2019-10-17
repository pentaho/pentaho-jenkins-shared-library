/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import com.cloudbees.groovy.cps.NonCPS

import java.time.Duration


class PrettyPrinter implements Serializable {
  transient StringWriter writer
  transient IndentPrinter indentPrinter
  transient def obj

  PrettyPrinter(def obj, String indent = '  ') {
    this.obj = obj
    writer = new StringWriter()
    indentPrinter = new IndentPrinter(writer, indent, true, false)
  }

  @NonCPS
  def incrementIndent(int times = 1) {
    indentPrinter.setIndentLevel(indentPrinter.getIndentLevel() + times)
    this
  }

  @NonCPS
  def decrementIndent(int times = 1) {
    indentPrinter.setIndentLevel(indentPrinter.getIndentLevel() - times)
    this
  }

  @NonCPS
  String toPrettyPrint() {
    printObject(obj)
    indentPrinter.flush()
    return writer.toString()
  }

  @NonCPS
  private void indent() {
    indentPrinter.printIndent()
  }

  @NonCPS
  private void printEnclosing(String open, String close, Closure call) {
    indentPrinter.println(open)
    indentPrinter.incrementIndent()
    call()
    indentPrinter.println()
    indentPrinter.decrementIndent()
    indentPrinter.printIndent()
    indentPrinter.print(close)
  }

  @NonCPS
  private void printLines(List<String> lines, boolean withIndent = true) {
    if (withIndent) indent()
    int size = lines.size()
    boolean first = true
    lines.eachWithIndex { s, idx ->
      if (first) {
        first = false
      } else {
        indentPrinter.printIndent()
      }
      indentPrinter.print(s)
      if (idx < size - 1) {
        indentPrinter.println()
      }
    }
  }

  @NonCPS
  private void printCollection(def obj, boolean withIndent = true) {
    if (withIndent) indent()
    if (obj) {
      printEnclosing('[', ']') {
        boolean first = true
        obj.each { v ->
          if (first) {
            first = false
          } else {
            indentPrinter.println(', ')
          }
          indentPrinter.printIndent()
          printObject(v, false)
        }
      }
    } else {
      indentPrinter.print('[]')
    }
  }

  @NonCPS
  private void printDictionary(Map obj, boolean withIndent = true) {
    if (withIndent) indent()
    if (obj) {
      printEnclosing('{', '}') {
        boolean first = true
        obj.each { k, v ->
          if (first) {
            first = false
          } else {
            indentPrinter.println(',')
          }
          indentPrinter.printIndent()
          indentPrinter.print(String.valueOf(k))
          indentPrinter.print(': ')
          printObject(v, false)
        }
      }
    } else {
      indentPrinter.print('{:}')
    }
  }

  @NonCPS
  private void printDuration(Duration duration) {
    if (duration.isNegative()) duration = duration.negated()

    long days = duration.toDays()
    duration = duration.minusDays(days)
    long hours = duration.toHours()
    duration = duration.minusHours(hours)
    long minutes = duration.toMinutes()
    duration = duration.minusMinutes(minutes)
    long seconds = duration.getSeconds()

    boolean forceSeconds = ![days,hours,minutes,seconds].any()

    def sb = ''<<''
    if (days) sb << days << 'd '
    if (hours) sb << hours << 'h '
    if (minutes) sb << minutes << 'm '
    if (forceSeconds || seconds) sb << seconds << 's'
    indentPrinter.print(sb.toString().trim())
  }

  @NonCPS
  private void printObject(obj, boolean withIndent = true) {
    switch (obj) {
      case Enum:
        if (withIndent) indent()
        if (obj.declaringClass.enclosingClass) {
          indentPrinter.print(obj.declaringClass.enclosingClass.simpleName)
          indentPrinter.print('.')
        }
        indentPrinter.print(obj.declaringClass.simpleName)
        indentPrinter.print('.')
        indentPrinter.print(obj.name())
        break
      case CharSequence:
        List<String> lines = obj.readLines()
        if (lines.size() > 1) {
          indentPrinter.println('"""\\')
          incrementIndent(2)
          printLines(lines)
          decrementIndent(2)
          indentPrinter.print('"""')
        } else {
          if (withIndent) indent()
          indentPrinter.print("\"$obj\"")
        }
        break
      case Map:
        printDictionary(obj, withIndent)
        break
      case Iterable:
      case Object[]:
        printCollection(obj, withIndent)
        break
      case Duration:
        if (withIndent) indent()
        printDuration(obj)
        break
      default:
        List<String> lines = String.valueOf(obj).readLines()
        printLines(lines, withIndent)
    }
  }

}
