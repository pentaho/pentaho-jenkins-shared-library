# Build File
The **Build File** defines what will built and any specific configuration needed to succeed in doing so. The build file is expected to be in yaml format and is composed by the following sections. The location of the build file is defined by a combination of two properties, **BUILD_DATA_ROOT_PATH** and **BUILD_DATA_FILE**.

## BUILD_DATA_ROOT_PATH
This property defines the path to a folder in the workspace where the build files can be found.

## BUILD_DATA_FILE
The file name for the build file that will be looked for inside the build data folder.

# Structure
The build file has 3 major components.

## Build Properties
This section contains global properties that will override the defaults defined in previous configuration sources.

## Job Groups
In this section the job groups are listed. Each job group is composed of one or more job items that will be able to run in parallel. Each job group is identified by a label that serves the sole purpose of identifying uniquely the group.

## Job Items
Job items define the necessary properties to build a specific project or part of a project.

# A Build File Example

```
buildProperties:
  BUILD_PLAN_ID    : Build Test
  
  PENTAHO_SCM_ROOT : https://github.com/pentaho

jobGroups:
  core:
    - jobID             :  pdi-engine-core
      scmUrl            :  ${PENTAHO_SCM_ROOT}/pentaho-kettle.git
      scmBranch         :  master
      buildFramework    :  maven
      directives        :  += -DskipDefault -P base,plugins,lowdeps

  plugins:
    - jobID             :  pdi-core-plugins
      scmUrl            :  ${PENTAHO_SCM_ROOT}/pentaho-kettle.git
      scmBranch         :  master
      buildFramework    :  maven
      directives        :  += -DskipDefault -P plugins,highdeps

    - jobID             :  pdi-teradata-tpt-plugin
      scmUrl            :  ${PENTAHO_SCM_ROOT}/pdi-teradata-tpt-plugin.git
      scmBranch         :  master
      buildFramework    :  maven
```
