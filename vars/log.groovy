import org.hitachivantara.ci.LogLevel
import org.hitachivantara.ci.PrettyPrinter
import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.LogLevel.ERROR
import static org.hitachivantara.ci.LogLevel.WARNING
import static org.hitachivantara.ci.LogLevel.INFO
import static org.hitachivantara.ci.LogLevel.DEBUG

def error(msg, dump = null) {
  message ERROR, msg, dump
}

def warn(msg, dump = null) {
  message WARNING, msg, dump
}

def info(msg, dump = null) {
  message INFO, msg, dump
}

def debug(msg, dump = null) {
  message DEBUG, msg, dump
}

def message(LogLevel level, Object body, Object dump) {
  if (BuildData.instance.logLevel.encompasses(level)) {
    def message = "[${level.label}] " << String.valueOf(body)
    if (dump) {
      if (dump instanceof Throwable) {
        def dumpExclusions = ['org.codehaus', 'com.cloudbees', 'sun', 'java', 'hudson', 'org.jenkinsci', 'jenkins', 'groovy']
        dump.getStackTrace().each { traceElement ->
          if (!dumpExclusions.any { traceElement.className.startsWith(it) }) {
            message << "\n  at $traceElement"
          }
        }
      } else {
        message << "\n" << new PrettyPrinter(dump).incrementIndent().toPrettyPrint()
      }
    }
    echo message.toString()
  }
}
