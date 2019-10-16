package org.hitachivantara.ci

import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.build.impl.AntBuilder
import org.hitachivantara.ci.build.impl.DSLScriptBuilder
import org.hitachivantara.ci.build.impl.GradleBuilder
import org.hitachivantara.ci.build.impl.JenkinsJobBuilder
import org.hitachivantara.ci.build.impl.MavenBuilder
import org.hitachivantara.ci.utils.FileUtilsRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.hitachivantara.ci.build.BuildFramework.ANT
import static org.hitachivantara.ci.build.BuildFramework.DSL_SCRIPT
import static org.hitachivantara.ci.build.BuildFramework.GRADLE
import static org.hitachivantara.ci.build.BuildFramework.JENKINS_JOB
import static org.hitachivantara.ci.build.BuildFramework.MAVEN

class BuilderFactoryTest extends BasePipelineSpecification {

  @Rule public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(new FileUtilsRule(this))

  def "test correct builder instantiation"() {
    setup:
      JobItem jobItem = new JobItem('', [buildFramework: framework], [:])

    when:
      Class<Builder> builder = BuilderFactory.builderFor(jobItem).class

    then:
      builder == expected

    where:
      framework   | expected
      MAVEN       | MavenBuilder
      ANT         | AntBuilder
      DSL_SCRIPT  | DSLScriptBuilder
      JENKINS_JOB | JenkinsJobBuilder
      GRADLE      | GradleBuilder
  }

}
