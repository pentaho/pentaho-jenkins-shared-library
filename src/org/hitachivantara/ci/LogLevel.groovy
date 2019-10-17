/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

enum LogLevel implements Serializable {
  ERROR('ERROR', 1),
  WARNING('WARN', 2),
  INFO('INFO', 3),
  DEBUG('DEBUG', 4)

  String label
  int precedence

  LogLevel(String label, int precedence) {
    this.label = label
    this.precedence = precedence
  }

  boolean encompasses(LogLevel level) {
    level.getPrecedence() <= getPrecedence()
  }

  static LogLevel lookup(String label) {
    LogLevel.getEnumConstants().find { it.name().startsWith(label.toUpperCase()) } ?: INFO
  }
}