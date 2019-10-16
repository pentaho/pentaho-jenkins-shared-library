package org.hitachivantara.ci.build

abstract class SonarAnalyser {
  List<String> inclusions
  List<String> exclusions

  abstract String getCommand()
}
