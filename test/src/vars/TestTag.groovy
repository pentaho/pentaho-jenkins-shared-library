/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package vars

import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.config.BuildClock
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.utils.JenkinsVarRule
import org.junit.Rule

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class TestTag extends BasePipelineSpecification {

  @Rule
  JenkinsVarRule tagRule = new JenkinsVarRule(this, 'utils')

  def "test tag name expression evaluation"() {
    setup:
      BuildData buildData = BuildData.instance

      buildData.clock = Spy(BuildClock) {
        instant() >> Instant.parse('2019-12-03T12:30:00.00Z')
        getZone() >> ZoneId.ofOffset('UTC', ZoneOffset.of('Z'))
      }

    when:
      String evaluated = tagRule.var.evaluateTagName(tagName)

    then:
      expected == evaluated

    cleanup:
      buildData.reset()

    where:
      tagName             | expected
      'date|yyyyMMdd-10'  | '20191203-10'
      "date|yyyy-'rc'"    | '2019-rc'
      'date|HH.mm-325'    | '12.30-325'
      'tag-not-evaluated' | 'tag-not-evaluated'
  }

}
