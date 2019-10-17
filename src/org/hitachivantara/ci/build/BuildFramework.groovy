/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
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