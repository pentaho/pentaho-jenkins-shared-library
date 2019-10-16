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