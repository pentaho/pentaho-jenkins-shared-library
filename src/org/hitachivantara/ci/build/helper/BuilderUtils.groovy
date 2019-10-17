/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.build.helper

import com.cloudbees.groovy.cps.NonCPS
import hudson.AbortException
import hudson.model.Result
import org.hitachivantara.ci.IllegalArgumentException
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.build.BuildException
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.PipelineSignalException
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.cps.CpsScript

import java.util.Map.Entry
import java.util.regex.Matcher
import java.util.regex.Pattern

class BuilderUtils implements Serializable {

  final static String SPACER = ' '
  final static String ADDITIVE_EXPR = '+='
  final static String SUBTRACTIVE_EXPR = '-='

  static Script getSteps() {
    ({} as CpsScript)
  }

  static DirectivesData parseDirectivesData(final String configuredDirectives) {

    if (!configuredDirectives) {
      return null
    }

    if (configuredDirectives.split("\\${ADDITIVE_EXPR}", -1).length - 1 > 1 ||
        configuredDirectives.split("${SUBTRACTIVE_EXPR}", -1).length - 1 > 1) {
      throw new BuildDirectivesException("Only one occurrence of each of the chars '${ADDITIVE_EXPR}' and '${SUBTRACTIVE_EXPR}' is permitted ['${configuredDirectives}']")
    }

    def addPosition = configuredDirectives.indexOf(ADDITIVE_EXPR)
    def subPosition = configuredDirectives.indexOf(SUBTRACTIVE_EXPR)

    if (addPosition == -1 && subPosition == -1) {
      return null
    }

    DirectivesData directivesData = new DirectivesData()

    if (addPosition > -1) {
      directivesData.additive = getDirective(configuredDirectives, "\\${ADDITIVE_EXPR}.*?${SUBTRACTIVE_EXPR}")

      if (!directivesData.additive) {
        directivesData.additive = configuredDirectives.substring(addPosition + ADDITIVE_EXPR.length(), configuredDirectives.length()).trim()
      }
      directivesData.additive += SPACER
    }

    if (subPosition > -1) {
      directivesData.subtractive = getDirective(configuredDirectives, "${SUBTRACTIVE_EXPR}.*?\\${ADDITIVE_EXPR}")

      if (!directivesData.subtractive) {
        directivesData.subtractive = configuredDirectives.substring(subPosition + SUBTRACTIVE_EXPR.length(), configuredDirectives.length()).trim()
      }
      directivesData.subtractive += SPACER
    }

    return directivesData
  }

  @NonCPS static String getDirective(String configuredDirectives, final String regExpr) {
    Pattern p = Pattern.compile(regExpr)
    Matcher m = p.matcher(configuredDirectives)

    if (m.find())
      return m.group().subSequence(ADDITIVE_EXPR.length(), m.group().length() - ADDITIVE_EXPR.length()).toString().trim()
    return null
  }

  /**
   * If job directives exist:
   *   Replace or merge default directive with job directive
   * If no job directives, just use the data default directives
   *
   * Additional custom directives that are specific to the build framework will be appended by the caller
   *
   * @param self
   * @param defaultDirectives
   * @param directives
   * @return
   */
  static <T> T applyBuildDirectives(T self, String defaultDirectives, String directives) {
    if (directives?.empty) {
      self << ' ' << defaultDirectives
      return self
    }

    DirectivesData directivesData = parseDirectivesData(directives)
    if (directivesData) {
      if (defaultDirectives) self << ' ' << defaultDirectives
      if (directivesData.additive) self << ' ' << directivesData.additive.trim()
      //TODO: use a proper command object that validates it's input instead of a StringBuilder
      if (self instanceof StringBuilder) {
        String regex = directivesData.subtractive?.split()?.join('|')
        StringUtils.replaceAll(self, ~/(?i) ($regex)/, '')
      } else {
        self -= directivesData.subtractive
      }
      return self
    }

    //TODO: use a proper command object that validates it's input instead of a StringBuilder
    //override defaults with job directives if they exist
    if (self instanceof StringBuilder) {
      String regex = defaultDirectives?.split()?.join('|')
      StringUtils.replaceAll(self, ~/(?i)\s?($regex)\s?/, '')
    } else {
      self -= defaultDirectives
    }
    self << ' ' << directives
    return self
  }

  /**
   * Find the closest build file to the given file
   * @param base
   * @param current
   * @return
   */
  static File findBuildFile(File base, File current, String buildFilename) {
    /*
     * Search the for the closest build file to the given file
     * Location: a/build.xml
     * Iterations: a/b/c/version.xml -> a/b/c/ -> a/b/ -> a/
     */

    if (!current.exists() || base == current) {
      // file doesn't belong to this tree, give up.
      return null
    }
    if (current.directory) {
      // Does the build file exist under current dir?
      File file = new File(current, buildFilename)
      if (file.exists()) {
        return file
      }
    } else if (current.name == buildFilename) {
      return current
    }

    // Keep searching up
    findBuildFile(base, current.parentFile, buildFilename)
  }

  /**
   * Given a JobItem, it will update the remaining groups items to FORCE
   * @param jobItem
   * @param buildMap
   */
  static void forceRemainingJobItems(JobItem jobItem, LinkedHashMap<String, List<JobItem>> buildMap) {
    boolean start = false
    for (Entry<String, List<JobItem>> entry in buildMap) {
      String grp = entry.getKey()
      if (grp == jobItem.jobGroup) {
        start = true
        continue
      }
      if (start) {
        List<JobItem> jobs = entry.getValue()
        jobs.each { JobItem job ->
          if (!job.execNoop) {
            job.execType = JobItem.ExecutionType.FORCE
          }
        }
      }
    }
  }

  /**
   * execute the shell command, but handle the exit code for proper logging
   * @param String cmd
   * @param Script dsl
   * @param boolean abort When true (default), throws an exception if script returns in error
   * @return the script's exit code
   * @throws Exception
   */
  static int process(String cmd, Script dsl, boolean abort = true) throws Exception {
    int exitCode = dsl.sh(returnStatus: true, script: cmd)
    if (exitCode != 0) {
      int errorSignal = getErrorSignal(exitCode)
      if (errorSignal > 0) {
        // Not good! Someone stopped this process manually.
        throw new PipelineSignalException(Result.ABORTED, "Job item was terminated by ${getSignalName(errorSignal)}")
      }
      if (abort) throw new BuildException("script returned exit code $exitCode")
    }
    return exitCode
  }

  /**
   *
   * @param String cmd
   * @param Script dsl
   * @param abort When true (default), throws an exception if script returns in error
   * @return the output of the script
   * @throws Exception
   */
  static String processOutput(String cmd, Script dsl, boolean abort = true) throws Exception {
    try {
      return dsl.sh(returnStdout: true, script: cmd)?.trim()
    } catch (AbortException err) {
      int exitCode = getExitCode(err.message)
      int errorSignal = getErrorSignal(exitCode)
      if (errorSignal > 0) {
        // Not good! Someone stopped this process manually.
        throw new PipelineSignalException(Result.ABORTED, "Job item was terminated by ${getSignalName(errorSignal)}")
      }
      if (abort) throw err
    }
    return ""
  }

  @NonCPS
  static int getExitCode(String cause) {
    def matcher = (cause =~ /script returned exit code (\d+)/)
    def exitCode = 0
    if (matcher.matches() && matcher.hasGroup()) {
      exitCode = matcher.group(1).toInteger()
    }
    return exitCode
  }

  /**
   * list of known reserved exit codes http://www.tldp.org/LDP/abs/html/exitcodes.html
   * 1       SIGHUP (hang up)
   * 2       SIGINT (interrupt)
   * 3       SIGQUIT (quit)
   * 6       SIGABRT (abort)
   * 9       SIGKILL (non-catchable, non-ignorable kill)
   * 14      SIGALRM (alarm clock)
   * 15      SIGTERM (software termination signal)
   *
   * @param exitCode
   * @return
   */
  static int getErrorSignal(int exitCode) {
    // 128+n  --> Fatal error signal "n"
    if (exitCode > 128 && exitCode <= 143) return exitCode ^ 128
    return 0
  }

  static String getSignalName(int errorSignal) {
    switch (errorSignal) {
      case 1 : return 'SIGHUP (hang up)'
      case 2 : return 'SIGINT (interrupt)'
      case 3 : return 'SIGQUIT (quit)'
      case 6 : return 'SIGABRT (abort)'
      case 9 : return 'SIGKILL (non-catchable, non-ignorable kill)'
      case 14: return 'SIGALRM (alarm clock)'
      case 15: return 'SIGTERM (software termination signal)'
      default: return "signal $errorSignal"
    }
  }

  /**
   * If this job can be skipped
   * @param jobItem
   * @return Boolean
   */
  static boolean canSkipItem(JobItem jobItem, Closure includeItem) {
    BuildData buildData = BuildData.instance

    if (buildData.noop || jobItem.execNoop) {
      return true
    }
    if (jobItem.skip || (jobItem.execAuto && jobItem.changeLog?.empty)) {
      // mark the job as skipped to prevent future pipeline interaction
      jobItem.set('skip', true)
      return true
    }
    if (!includeItem(jobItem)) {
      return true
    }
    return false
  }

  /**
   * Given a list of possible expanded jobItems, reorganize and group the items to be processed by a parallel step.
   * @param items
   * @return List of grouped items
   */
  static List<List<?>> organizeItems(List<?> items) {
    if (!items) return items
    def result = []
    def remaining = []
    items.each {
      switch (it) {
        case List:
          result += it.remove(0)
          if (it) remaining << it
          break
        default:
          result << it
      }
    }
    return [result] + organizeItems(remaining)
  }

  static List<List<JobItem>> prepareForExecution(List<JobItem> jobItems) {
    prepareForExecution(jobItems, true, { true })
  }

  static List<List<JobItem>> prepareForExecution(List<JobItem> jobItems, boolean allowParallel) {
    prepareForExecution(jobItems, allowParallel, { true })
  }

  static List<List<JobItem>> prepareForExecution(List<JobItem> jobItems, Closure itemCheck) {
    prepareForExecution(jobItems, true, itemCheck)
  }

  /**
   * Gets a list of JobItems and prepares it for execution, excluding entries that may be skipped
   * @param jobItems
   * @param buildData
   * @param testPhase
   * @return
   */
  static List<List<JobItem>> prepareForExecution(List<JobItem> jobItems, boolean allowParallel, Closure itemCheck) {
    BuildData buildData = BuildData.instance
    List<JobItem> workableItems = []

    if (buildData.useMinions) {
      workableItems = jobItems.findAll { JobItem jobItem -> !canSkipItem(jobItem, itemCheck) }
      return [workableItems]
    }

    for (JobItem jobItem : jobItems) {
      Builder builder = BuilderFactory.builderFor(steps, jobItem)
      builder.applyScmChanges()

      if (!canSkipItem(jobItem, itemCheck)) {
        workableItems << (jobItem.parallel && allowParallel ? builder.expandItem() : jobItem)
      }
    }

    return organizeItems(workableItems)
  }

  /**
   * Returns consecutive {@linkplain List#subList(int, int) sublists} of a list,
   * each of the same size (the final list may be smaller).
   *
   * For example, partitioning a list containing [a, a, b, c, d, e] with a partition
   * size of 3 and a rule that sublists must be unique ( !sublist.contains(obj) ), yields [[a, b, c], [a, d, e]]
   *
   * @param list the list to return consecutive sublists of
   * @param size the desired size of each sublist (the last may be
   *     smaller)
   * @param criteria the closure rule each sublist must abide ( closure must accepts 2 args as in List<T>, T )
   * @return a list of consecutive sublists
   * @throws org.hitachivantara.ci.IllegalArgumentException if partitionSize is nonpositive,
   */
  static <T> List<List<T>> partition(List<T> list, int size, Closure criteria) {
    if (size < 1) throw new IllegalArgumentException("Partition size must be greater than 0")
    if (criteria == null) throw new IllegalArgumentException("Rule criteria must not be null")
    if (criteria.maximumNumberOfParameters != 2) throw new IllegalArgumentException("Rule criteria must accept 2 arguments [List<T> currentSublist, T nextItem]")
    if (!list) return []

    List<List<T>> partitions = []
    while (list) {
      int idx = 0
      List<T> subList = [list.remove(idx)]
      while (subList.size() < size && idx < list.size()) {
        if (criteria.call(subList, list.get(idx))) {
          subList += list.remove(idx)
        } else {
          idx++
        }
      }
      partitions << subList
    }
    return partitions
  }

  /**
   * Returns consecutive {@linkplain List#subList(int, int) sublists} of a list,
   * each of the same size (the final list may be smaller).
   *
   * For example, partitioning a list containing [a, b, c, d, e] with a partition
   * size of 3 yields [[a, b, c], [d, e]]
   *
   * @param list the list to return consecutive sublists of
   * @param size the desired size of each sublist (the last may be
   *     smaller)
   * @return a list of consecutive sublists
   * @throws IllegalArgumentException if partitionSize is nonpositive
   */
  static <T> List<List<T>> partition(List<T> list, int size) throws IllegalArgumentException {
    if (size < 1) throw new IllegalArgumentException("Partition size must be greater than 0")
    if (!list) return []

    List<List<T>> partitions = []
    int N = list.size()
    for (int idx = 0; idx < N; idx += size) {
      partitions << new ArrayList<T>(list.subList(idx, Math.min(N, idx + size)))
    }
    return partitions
  }

}
