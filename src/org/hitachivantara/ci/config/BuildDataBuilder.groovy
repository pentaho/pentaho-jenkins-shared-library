/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.config

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.LogLevel
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.jenkins.MinionHandler
import org.jenkinsci.plugins.workflow.cps.CpsScript

import java.nio.file.Paths

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_DATA_FILE
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_DATA_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_DATA_YAML
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_VERSIONS_FILE
import static org.hitachivantara.ci.config.LibraryProperties.DEFAULT_BUILD_PROPERTIES
import static org.hitachivantara.ci.config.LibraryProperties.FIRST_JOB
import static org.hitachivantara.ci.config.LibraryProperties.LAST_JOB
import static org.hitachivantara.ci.config.LibraryProperties.LOG_LEVEL
import static org.hitachivantara.ci.config.LibraryProperties.OVERRIDE_JOB_PARAMS
import static org.hitachivantara.ci.config.LibraryProperties.OVERRIDE_PARAMS
import static org.hitachivantara.ci.config.LibraryProperties.PUSH_CHANGES
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE

class BuildDataBuilder {
  String defaults = 'default-properties.yaml'

  private def env
  private String globalConfigPath
  private String buildConfigPath
  private Map params = [:]

  // deferring logging messages
  private List<Closure> messages = []


  BuildDataBuilder() {}

  private Script getSteps() {
    ({} as CpsScript)
  }

  /**
   * Pipeline execution parameters
   *
   * @param params
   * @return
   */
  BuildDataBuilder withParams(Map params) {
    this.params = params
    return this
  }

  /**
   * Jenkins environment parameters
   *
   * @param env
   * @return
   */
  BuildDataBuilder withEnvironment(env) {
    // Jenkins will send us an instance of org.jenkinsci.plugins.workflow.cps.EnvActionImpl
    // including the project dependency to strongly type it here will cause some issues with
    // test compilation. Let it be generic and make it easier on tests by allowing a map too.
    this.env = env
    return this
  }

  /**
   * Global config path
   *
   * @param globalConfigPath
   * @return
   */
  BuildDataBuilder withGlobalConfig(String globalConfigPath) {
    this.globalConfigPath = globalConfigPath
    return this
  }

  /**
   * Pipeline config path
   *
   * @param buildConfigPath
   * @return
   */
  BuildDataBuilder withBuildConfig(String buildConfigPath) {
    this.buildConfigPath = buildConfigPath
    return this
  }

  /**
   * Creates and returns the configuration to be used on the build
   * Property priority resolution order:
   * 1. Library Defaults (defaults)
   * 2. Jenkins Environment (env)
   * 3. Global Config (DEFAULT_BUILD_PROPERTIES)
   * 4. Pipeline Config (BUILD_DATA_YAML/BUILD_DATA_FILE)
   * 5. Run Parameters (params)
   * 6. OVERRIDE_PARAMS param
   *
   * @return
   */
  BuildData build() throws ConfigurationException, ConfigurationFileNotFoundException {
    // Merge all the properties sources in order of override
    // 1. Load library default properties
    Map<String, Object> libraryDefaults = loadLibraryDefaults(defaults)

    // 2. Set Jenkins Environment and library defaults in this order to search for undefined values
    Map<String, Object> buildProperties = new ConfigurationMap(env ? [env, libraryDefaults] : libraryDefaults)

    // apply any param overrides given
    Map<String, Object> pipelineParams = [:]
    pipelineParams << params
    pipelineParams << parseOverrides(params[OVERRIDE_PARAMS])

    // 3. Load global properties
    Map globalProperties = loadGlobalConfig(buildProperties + pipelineParams)
    buildProperties << globalProperties

    // 4. Load pipeline properties
    // send the params in here too so we can get the build file from it
    // but don't merge the params yet cause we want them after the build file properties
    Map pipelineData = loadPipelineConfig(buildProperties + pipelineParams)
    Map pipelineProperties = pipelineData['buildProperties'] as Map ?: [:]

    // 5. OVERRIDE_PARAMS param
    buildProperties << pipelineProperties
    buildProperties << pipelineParams

    sanitize(buildProperties)

    BuildData buildData = BuildData.instance
    buildData.globalProperties = globalProperties
    buildData.pipelineParams = pipelineParams
    buildData.pipelineProperties = pipelineProperties
    buildData.buildProperties = buildProperties

    buildData.preBuildMap = parsePipelineJobData(pipelineData['preBuild'] as Map, buildProperties)
    buildData.postBuildMap = parsePipelineJobData(pipelineData['postBuild'] as Map, buildProperties)
    buildData.buildMap = parsePipelineJobData(pipelineData['jobGroups'] as Map, buildProperties)
    buildData.logLevel = LogLevel.lookup(buildProperties.getString(LOG_LEVEL))

    validate(buildProperties, buildData)

    return buildData
  }

  /**
   * Read a given yaml file from the shared resources
   */
  private Map<String, Object> loadLibraryDefaults(String resourcePath) throws ConfigurationException {
    messages << { -> steps.log.info "Loading default configuration from:", resourcePath }
    try {
      String content = steps.libraryResource encoding: 'UTF-8', resource: resourcePath
      return (parseContent(content) as Map)
    } catch (Throwable e) {
      throw new ConfigurationException("Couldn't read $resourcePath, file doesn't exist or is an invalid YAML!", e)
    }
  }

  /**
   * Parses a String as a YAML text
   * @param content
   * @return
   * @throws ConfigurationException
   */
  private Object parseContent(String content) throws ConfigurationException {
    try {
      return steps.readYaml(text: content)
    } catch(Throwable e) {
      throw new ConfigurationException("Invalid YAML!", e)
    }
  }

  void consumeMessages() {
    // we wanted to use the dsl.log var, but can't use it during the config evaluation,
    // so we defer it to be used when the config is set.
    messages.each { message -> message() }
    messages.clear()
  }

  /**
   * Perform any final cleanup/sanitization of configuration
   *
   * @param buildProperties
   */
  private void sanitize(ConfigurationMap buildProperties) {
    // Some properties are dependent of the job's name,
    // we calculate and inject them here
    if (StringUtils.isEmpty(buildProperties[BUILD_VERSIONS_FILE])) {
      String jobName = steps.utils.jobName()
      buildProperties[BUILD_VERSIONS_FILE] = "${jobName}.versions"
    }

    // Set Workspace to the current one where things are checked out or we'll have problems
    // when running in different nodes. Jenkins will use "workspace@N" folders
    // as the workspace folder and that will not work with our implementation.
    // Here we get the WORKSPACE value from the buildProperties, which might come from
    // any of our configuration sources, but will most likely come from the environment and store it
    // on the buildProperties in its base map. See ConfigurationMap to understand that this is
    // not an "x=x" scenario.
    buildProperties[WORKSPACE] = buildProperties.getString(WORKSPACE)

    // evaluate dynamic tag name
    buildProperties[TAG_NAME] = evaluateTagName(buildProperties.getString(TAG_NAME))

    // apply minion specific sanitization
    MinionHandler.sanitize(buildProperties)
  }

  /**
   * Perform a validation of configuration
   *
   * @param buildProperties
   * @param buildData
   */
  private void validate(Map buildProperties, BuildData buildData) {

    Boolean allJobItemsWithASC = buildData.getAllItems().any({ it.atomicScmCheckout })

    if ( buildProperties[PUSH_CHANGES] && allJobItemsWithASC){
      throw new ConfigurationException('If PUSH_CHANGES is TRUE, jobItems can\'t have atomicScmCheckout as TRUE also!')
    }

  }

  /**
   * Parses build file data to gather all the job and jobGroup information
   *
   * @param jobGroups
   * @param buildProperties
   * @return
   */
  private Map<String, List<JobItem>> parsePipelineJobData(Map jobGroups, Map buildProperties) throws ConfigurationException {
    // read job parameter overrides
    Map jobOverrides = parseJobOverrides(buildProperties)

    // first and last job flags
    String firstJob = buildProperties[FIRST_JOB]?.trim() ?: null
    String lastJob = buildProperties[LAST_JOB]?.trim() ?: null

    // Setup a state variable on whether jobs should ran
    // if a first/last job was defined
    boolean executeJobs = !firstJob

    Map<String, List<JobItem>> jobBuildMap = [:]

    // parse the jobGroups in the build file into JobItems
    jobGroups.each { jobGroup, List<Map> jobs ->
      List jobItems = jobs.collect { Map job ->
        // Apply any existing property overrides for this job or for all (null key)
        if (jobOverrides && (jobOverrides[job.jobID] || jobOverrides[null])) {
          job << (jobOverrides[job.jobID] ?: jobOverrides[null]) as Map
        }

        JobItem jobItem = new JobItem(jobGroup as String, job, buildProperties)

        // Start executing jobs if a first job was set and this is the one
        if (firstJob && jobItem.jobID == firstJob) {
          executeJobs = true
        }

        // Set the job to a NOOP if before the first job or after the last job
        if (!executeJobs) {
          jobItem.setExecType(JobItem.ExecutionType.NOOP)
        }

        // Disable executing jobs if this is the last job
        if (lastJob && jobItem.jobID == lastJob) {
          executeJobs = false
        }

        return jobItem
      }

      if (jobItems) {
        jobBuildMap[jobGroup as String] = jobItems
      }
    }

    return jobBuildMap
  }

  /**
   * Parses the OVERRIDE_JOB_PARAMS build parameter to be applied to the job configuration.
   *
   * @param buildProperties
   * @return
   */
  private Map parseJobOverrides(Map buildProperties) throws ConfigurationException {
    def overrides = parseOverrides(buildProperties[OVERRIDE_JOB_PARAMS])

    // allow a single job declaration without it being a list
    List jobOverridesList = (overrides instanceof List ? overrides : [overrides]) as List<Map>
    return jobOverridesList.collectEntries { Map job ->
      // if the jobID is null we'll consider it's an override to all job items
      String jobID = job.remove('jobID')
      [(jobID): job]
    }
  }

  /**
   * Parses any configuration for overriding
   * @param param
   * @return
   * @throws ConfigurationException
   */
  private Object parseOverrides(Object param) throws ConfigurationException {
    if (!param) return [:]

    switch (param) {
      case Map:
        return param
      case String:
        if (StringUtils.isEmpty(param)) return [:]
        try {
          return parseContent(param as String)
        } catch (Throwable e) {
          throw new ConfigurationException('Wrong override param format.', e)
        }
    }

    throw new ConfigurationException('Wrong override param format.')
  }

  /**
   * Loads the build data file
   *
   * @return
   */
  private Map loadPipelineConfig(Map buildProperties) throws ConfigurationException, ConfigurationFileNotFoundException {
    if (!StringUtils.isEmpty(buildProperties[BUILD_DATA_YAML])) {
      messages << { -> steps.log.info "Loading build data from '${BUILD_DATA_YAML}' property!" }
      return parseContent(buildProperties[BUILD_DATA_YAML] as String) as Map
    }

    return loadFromDataFile(buildProperties)
  }

  /**
   * Get the job's configuration.
   * The file usually sits in the {@link LibraryProperties#BUILD_DATA_ROOT_PATH},
   * and is defined in the {@link LibraryProperties#BUILD_DATA_FILE}.
   * If {@link LibraryProperties#BUILD_DATA_FILE} is blank, we try one more time with a file
   * named has the current job.
   * If none are valid, return an empty configuration.
   * @param buildProperties
   * @return the configs loaded from a data file, empty if none found
   */
  private Map loadFromDataFile(Map buildProperties) throws ConfigurationException, ConfigurationFileNotFoundException {
    // not directly defined, fetch from properties
    if (!buildConfigPath) {
      String buildDataFile = StringUtils.fixNull(buildProperties[BUILD_DATA_FILE])
      boolean noBuildDataFile = StringUtils.isEmpty(buildDataFile)

      if (noBuildDataFile) {
        // the property is undefined!
        // Try loading one that matches this job's name before anything else.
        String jobName = steps.utils.jobName()
        buildDataFile = "${jobName}.yaml"
      } else {
        try {
          String remoteBuildData = new URL(buildDataFile).text
          messages << { -> steps.log.info "Parsing data from remote location '${buildDataFile}'" }
          return parseContent(remoteBuildData) as Map
        } catch (MalformedURLException | UnknownHostException e) {
          // it's not an URL or it's a URL for something that doesn't exist. Ignore and continue
          messages << { -> steps.log.warn "'${BUILD_DATA_FILE}' does not refer to an URL!" }
        }
      }

      String resultingPath = Paths.get(
        StringUtils.fixNull(buildProperties[BUILD_DATA_ROOT_PATH]),
        buildDataFile
      )

      if (steps.fileExists(resultingPath)) {
        // The path seems to be valid, set its property value
        buildConfigPath = resultingPath
        buildProperties[BUILD_DATA_FILE] = buildDataFile
      } else if (noBuildDataFile) {
        messages << { -> steps.log.warn "No '${BUILD_DATA_FILE}' specified!" }
        // invalid resultingPath, proceed with an empty data
        return [:]
      }
    }

    messages << { -> steps.log.info "Loading build data from:", buildConfigPath }
    return loadProperties(buildConfigPath)
  }

  /**
   * Loads the pipeline configuration file
   *
   * @return
   */
  private Map loadGlobalConfig(Map buildProperties) throws ConfigurationException, ConfigurationFileNotFoundException {
    String configPath = globalConfigPath ?: StringUtils.fixNull(buildProperties[DEFAULT_BUILD_PROPERTIES])
    if (configPath && steps.fileExists(configPath)) {
      messages << { -> steps.log.info "Loading global configuration from:", configPath }
      return loadProperties(configPath)
    }
    return [:]
  }

  private Map loadProperties(String configPath) throws ConfigurationException, ConfigurationFileNotFoundException {
    if (steps.fileExists(configPath)) {
      try {
        return steps.readYaml(file: configPath) as Map
      } catch (Throwable e) {
        throw new ConfigurationException("Not a valid build data file!", e)
      }
    }
    throw new ConfigurationFileNotFoundException("Configuration file not found! ${configPath}")
  }

  /**
   * Evaluate a given tag name to provide dynamic tag naming expressions
   *
   * Currently supported expressions:
   * - date|<format expression> : date|yyyyMMdd-${BUILD_NUMBER}
   *
   * @param tagName
   * @return
   */
  String evaluateTagName(String tagName) {
    List parts = tagName.tokenize('|')

    if (parts.size() < 2) {
      return tagName
    }

    switch (parts[0]) {
      case 'date':
        return BuildData.instance.clock.format(parts[1])
      default:
        return tagName
    }
  }
}
