/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.jenkins

import com.cloudbees.groovy.cps.NonCPS
import hudson.util.VersionNumber
import jenkins.model.Jenkins

class JenkinsUtils implements Serializable {

  @NonCPS
  static boolean isPluginActive(String pluginId, String version = null) {
    def p = Jenkins.get().pluginManager.plugins.find { plugin -> plugin.isActive() && plugin.shortName == pluginId }
    if (p && version) {
      return p.versionNumber.isNewerThanOrEqualTo(new VersionNumber(version))
    }
    return p
  }
}