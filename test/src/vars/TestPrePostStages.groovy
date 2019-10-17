/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package vars

import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.config.ConfigurationException
import org.hitachivantara.ci.utils.JenkinsVarRule
import org.junit.Rule

class TestPrePostStages extends BasePipelineSpecification {

  @Rule
  JenkinsVarRule buildRule = new JenkinsVarRule(this, 'building')

  def "test pre/post stage invalid configuration"() {
    setup:
      JobItem jobItem = new JobItem('', ['buildFramework': 'Ant'], [:])

    when:
      buildRule.var.getItemClosure(jobItem, 'fake label')

    then:
      thrown(ConfigurationException)

    cleanup:
      BuildData.instance.reset()
  }

  def "test pre/post stage valid configuration"() {
    setup:
      JobItem jobItem = new JobItem('', ['buildFramework': 'DSL_SCRIPT'], [:])

    when:
      buildRule.var.getItemClosure(jobItem, 'fake label')

    then:
      noExceptionThrown()

    cleanup:
      BuildData.instance.reset()
  }
}
