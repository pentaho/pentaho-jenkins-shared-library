/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package vars

import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsLoggingRule
import org.hitachivantara.ci.utils.JenkinsVarRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.hitachivantara.ci.config.LibraryProperties.RUN_DEPENDENCY_CHECK
import static org.hitachivantara.ci.config.LibraryProperties.RUN_NEXUS_LIFECYCLE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_SONARQUBE

class TestAudit extends BasePipelineSpecification {
  JenkinsLoggingRule loggingRule = new JenkinsLoggingRule(this)
  ConfigurationRule configRule = new ConfigurationRule(this)
  JenkinsVarRule auditRule = new JenkinsVarRule(this, 'audit')

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(configRule)
    .around(loggingRule)
    .around(auditRule)

  // mapping copied from audit.groovy
  static int DEPENDENCY_CHECK = 0x1
  static int NEXUS_IQ_SCAN = 0x2
  static int SONARQUBE = 0x4

  static Map hm = [(RUN_DEPENDENCY_CHECK): [true, false], (RUN_NEXUS_LIFECYCLE): [true, false], (RUN_SONARQUBE): [true, false]]
  static List permutations = hm.values().combinations { args ->
    [hm.keySet().asList(), args].transpose().collectEntries { [(it[0]): it[1]] }
  }

  def "test scanners mappings"() {
    given:
      configRule.addProperties(params)
    expect:
      auditRule.var.getEnabledScanners(configRule.buildData) == calculate(params)
    where:
      params << permutations
  }

  def "test scanner selection"() {
    setup:
      JobItem jobItem = Mock(JobItem)
    when:
      Map result = auditRule.var.getEntries(configRule.buildData, jobItem, enabledScanners)
    then:
      result.size() == expected
    where:
      enabledScanners                              || expected
      DEPENDENCY_CHECK + NEXUS_IQ_SCAN + SONARQUBE || 3
      SONARQUBE + DEPENDENCY_CHECK                 || 2
      SONARQUBE                                    || 1
  }


  static private int calculate(Map map) {
    (map[RUN_DEPENDENCY_CHECK] ? DEPENDENCY_CHECK : 0) + (map[RUN_NEXUS_LIFECYCLE] ? NEXUS_IQ_SCAN : 0) + (map[RUN_SONARQUBE] ? SONARQUBE : 0)
  }
}
