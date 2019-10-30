package org.hitachivantara.ci.utils

import com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration
import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.build.helper.BuilderUtils
import org.hitachivantara.ci.build.impl.AbstractBuilder
import org.hitachivantara.ci.config.BuildDataBuilder
import org.hitachivantara.ci.jenkins.JenkinsUtils
import org.junit.rules.RuleChain

class Rules {

  static RuleChain getCommonRules(BasePipelineSpecification specification) {
    return getCommonRules(specification, null)
  }

  static RuleChain getCommonRules(BasePipelineSpecification specification, LibraryConfiguration libConfig) {
    return RuleChain
      .outerRule(new JenkinsSetupRule(specification, libConfig))
      .around(new JenkinsVarRule(specification, 'log'))
      .around(new JenkinsVarRule(specification, 'utils'))
      .around(new ReplacePropertyRule((JenkinsUtils): ['static.isPluginActive': { String pluginId, String version -> true }]))
      .around(new ReplacePropertyRule((FileUtils): ['static.getSteps': { -> specification.mockScript }]))
      .around(new ReplacePropertyRule((BuilderUtils): ['static.getSteps': { -> specification.mockScript }]))
      .around(new ReplacePropertyRule((BuildDataBuilder): ['getSteps': { -> specification.mockScript }]))
      .around(new ReplacePropertyRule((AbstractBuilder): ['getSteps': { -> specification.mockScript }]))
  }
}
