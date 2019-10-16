package org.hitachivantara.ci.utils

import groovy.transform.Canonical
import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.FilePathException
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.config.BuildDataBuilder
import org.jenkinsci.plugins.pipeline.utility.steps.shaded.org.yaml.snakeyaml.Yaml
import org.jenkinsci.plugins.pipeline.utility.steps.shaded.org.yaml.snakeyaml.constructor.SafeConstructor
import org.junit.rules.ExternalResource

import java.nio.file.Paths

@Canonical
class ConfigurationRule extends ExternalResource {
  BasePipelineSpecification specification

  Map params = [:]
  String buildConfigPath = ''
  String globalConfigPath = 'test/resources/globalDefaults.yaml'
  Map environment = [:]

  @Delegate BuildData buildData = BuildData.instance

  @Override
  protected void before() throws Throwable {
    registerKnownMethods()
    buildData([WORKSPACE: 'builds'] + params, buildConfigPath, environment)
  }

  private void registerKnownMethods() {
    specification.registerAllowedMethod('libraryResource', [Map], { Map m ->
      if (m.resource) {
        File resource = new File(Paths.get('resources', m.resource as String) as String)
        if (resource.exists()) {
          return resource.text
        } else {
          throw new FilePathException("Library resource ${m.resource} not found.")
        }
      } else {
        throw new FilePathException('No library resource specified.')
      }
    })

    specification.registerAllowedMethod('readYaml', [Map], { Map m ->
      // adapted from ReadYamlStep
      String yamlText = ''
      if (m.file) {
        yamlText = new File(m.file).text
      }
      if (m.text) {
        yamlText += System.getProperty("line.separator") + m.text
      }
      // Use SafeConstructor to limit objects to standard Java objects like List or Long
      def result = new Yaml(new SafeConstructor()).loadAll(yamlText).collect()
      // if only one YAML document, return it directly
      if (result.size() == 1) {
        return result[0]
      }
      return result
    })

    specification.registerAllowedMethod('fileExists', [String], { String path ->
      return path && new File(path).exists()
    })
  }

  @Override
  protected void after() {
    buildData.reset()
  }

  /**
   * Add a property to the current build properties
   * @param key
   * @param value
   */
  void addProperty(String key, String value) {
    buildProperties[key] = value
  }

  /**
   * Add multiple properties to the current build properties
   * @param params
   */
  void addProperties(Map params) {
    buildProperties.putAll(params)
  }

  /**
   * Set the current build properties to the given values
   * @param params
   */
  void setProperties(Map params) {
    buildProperties = params
  }

  /**
   * Build/Rebuild build data with given configuration
   * @param params
   * @param buildConfigPath
   * @param environment
   */
  void buildData(Map params = this.params, String buildConfigPath = this.buildConfigPath, Map environment = this.environment){
    if ([params, buildConfigPath, environment].any()) {
      buildData.reset()
      new BuildDataBuilder()
        .withEnvironment(environment)
        .withGlobalConfig(globalConfigPath)
        .withBuildConfig(buildConfigPath)
        .withParams(params)
        .build()
    }
  }

  /**
   * Creates a new instance of JobItem with the current configuration
   * @param group
   * @param jobConfig
   * @return
   */
  JobItem newJobItem(String group = 'jobs', Map jobConfig){
    new JobItem(group, jobConfig, buildProperties)
  }
}
