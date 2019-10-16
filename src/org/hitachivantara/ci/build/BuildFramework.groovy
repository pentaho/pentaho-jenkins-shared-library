package org.hitachivantara.ci.build

import org.hitachivantara.ci.build.impl.AntBuilder
import org.hitachivantara.ci.build.impl.DSLScriptBuilder
import org.hitachivantara.ci.build.impl.GradleBuilder
import org.hitachivantara.ci.build.impl.JenkinsJobBuilder
import org.hitachivantara.ci.build.impl.MavenBuilder

enum BuildFramework {
  MAVEN(MavenBuilder, 'pom.xml'),
  ANT(AntBuilder, 'build.xml'),
  GRADLE(GradleBuilder, 'build.gradle'),
  JENKINS_JOB(JenkinsJobBuilder, 'Jenkinsfile'),
  DSL_SCRIPT(DSLScriptBuilder, '')

  Class<Builder> builder
  String buildFile

  BuildFramework(Class<Builder> builder, String buildFile) {
    this.builder = builder
    this.buildFile = buildFile
  }
}