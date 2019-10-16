package org.hitachivantara.ci.utils

import org.hitachivantara.ci.BasePipelineSpecification
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

import java.nio.charset.StandardCharsets

class GitRule implements TestRule {
  BasePipelineSpecification specification
  List<String> mockCommitLog = []
  List<String> revList = []
  Set<String> paths = []
  Random random = new Random()

  GitRule(BasePipelineSpecification specification) {
    this.specification = specification
  }

  @Override
  Statement apply(Statement base, Description description) {
    return base
  }

  /**
   * emulates a commit entry log
   * @param id
   * @param whatChanged
   */
  void addCommitLog(String id, String message = 'testing this stuff', String... whatChanged) {
    revList << id
    def log = 'commit ' << id << '\n'
    log << 'tree ' << nextRandomHex() << '\n'
    log << 'parent ' << nextRandomHex() << '\n'
    log << 'author John Doe <jdoe@example.com> 2019-01-01 00:00:00 +0100\n'
    log << 'committer John Doe <jdoe@example.com> 2019-01-01 00:00:00 +0100\n'
    log << '\n    '<< message <<'\n\n'
    whatChanged.each { String path ->
      log << ":100644 100644 ${nextRandomHex()} ${nextRandomHex()} M\t${path}\n"
      paths << path
    }
    mockCommitLog << log.toString()
  }

  InputStream getCommitLogInputStream() {
    return new ByteArrayInputStream(mockCommitLog.join('\n').getBytes(StandardCharsets.UTF_8))
  }

  String nextRandomHex() {
    StringBuffer sb = new StringBuffer(40)
    while (sb.length() < 40) {
      sb.append(Integer.toHexString(random.nextInt()))
    }

    return sb.substring(0, 40)
  }

}
