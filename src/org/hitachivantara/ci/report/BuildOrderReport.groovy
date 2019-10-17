/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.report

import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.build.impl.MavenBuilder
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

class BuildOrderReport implements Report {
  Script steps
  String report
  int indent
  int width

  BuildOrderReport(Script steps, int indent = 2, int width = 120) {
    this.steps = steps
    this.indent = indent
    this.width = width
  }

  Report build(BuildData buildData) {
    StringBuilder sb = new StringBuilder()

    def header = { String title ->
      sb << '\n' << '*' * width << '\n'
      sb << '** ' << title.padRight(width - 5) << '**\n'
    }

    def lastStringLength = { ->
      sb.size() - (sb.lastIndexOf('\n') + 1)
    }

    buildData.getBuildMap().each { String jobGroup, List<JobItem> jobItems ->
      header("jobGroup: ${jobGroup}")

      jobItems.each { JobItem jobItem ->
        sb << '*' * width << '\n'
        sb << ''.padRight(indent) << "jobID:      ${jobItem.jobID}\n"
        sb << ''.padRight(indent) << "scmUrl:     ${jobItem.scmUrl}\n"
        sb << ''.padRight(indent) << "root:       ${jobItem.root}\n"
        sb << ''.padRight(indent) << "directives: ${jobItem.directives}\n"

        Builder builder = BuilderFactory.builderFor(steps, jobItem)
        switch (builder) {
          case MavenBuilder:
            builder = (MavenBuilder) builder
            sb << ''.padRight(indent) << "projects:\n"
            try {
              List<?> activeMavenProjects = builder.getActiveModules(jobItem)
              activeMavenProjects.each { mavenProject ->
                sb << ''.padRight(indent * 2)
                sb << mavenProject.path << ' '
                sb << " (${mavenProject.versionlessKey})".padLeft(width - lastStringLength(), '.')
                sb << '\n'
              }
            } catch(Throwable ignored) {
              sb << ''.padRight(indent * 2)
              sb << "!!! Could't read ${jobItem.buildFile ?: 'pom.xml'} file !!!\n"
            }
        }
      }
    }

    report = sb.toString()

    return this
  }

  void send() {
    RunWrapper build = steps.currentBuild
    String filePath = 'report/buildorder.txt'
    steps.writeFile encoding: 'UTF-8', file: filePath, text: report

    File target = build.rawBuild.artifactsDir.toPath().resolve(filePath).toFile()
    FileUtils.copyToMaster(filePath, target)

    def sb = ''<<''
    sb << 'Build Order Report:\n'
    sb << ''.padRight(2)
    sb << "${build.absoluteUrl}artifact/report/buildorder.txt"

    steps.echo sb.toString()
  }

}