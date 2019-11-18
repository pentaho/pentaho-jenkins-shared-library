# Overriding Properties
These properties allow a quick override of any configuration for the pipeline. These properties are applied over params even if they are defined in the job.

### OVERRIDE_PARAMS
This configuration is meant to be used as a job param allowing override of any property or param passed to the build. The values here will override even other params present on the pipeline. The properties specified should be in yaml format.

### OVERRIDE_JOB_PARAMS
This configuration, just like the OVERRIDE_PARAMS, is meant to be used as a job param to provide the means to quickly override a job item configuration for the current build.
The provided value should be in a valid yaml format and all the job item properties are available for use.

Some examples.

#### Multi Item Entry
Provide a list of job item entries.

```
- jobID: my-item-id
  directives: clean install
  
- jobID: my-second-item-id
  directives: test
  scmBranch: my-branch
```

or in an alternate yaml form that might be easier to write depending on how big the override is.

```
- {jobID: my-item-id, directives: clean install}
- {jobID: my-second-item-id, directives: test, scmBranch: my-branch}
```

#### Single Item Entry
When specifying a single item, provide values as if you were specifying a job item directly.

```
jobID: my-item-id
directives: clean install
```

#### Omitting jobID 

If the `jobID` field is not specified the overrides for that item will be applied to all job items. 

```
directives: clean install
```

Will override all job item directives.

```
- directives: clean install
  
- jobID: my-item-id
  scmBranch: my-branch
```

Will override `scmBranch` for the job item `my-item-id` and the `directives` of all the other existing job items.


### Builder Configuration
Builder global properties. Each of these properties can be defined with the specific builder name prefixed to them.

Available builders:
- MAVEN
- GRADLE
- JENKINS_JOB
- DSL_SCRIPT

Some of the following options might only apply to some of the Builders.

##### <BUILDER_NAME>_PRIVATE_RELEASE_REPO_URL
Defines the url for the private repo when publishing release artifacts.

##### <BUILDER_NAME>_PRIVATE_SNAPSHOT_REPO_URL
Defines the url for the private repo when publishing snapshot artifacts.

##### <BUILDER_NAME>_PUBLIC_RELEASE_REPO_URL
Defines the url for the public repo when publishing release artifacts.

##### <BUILDER_NAME>_PUBLIC_SNAPSHOT_REPO_URL
Defines the url for the public repo when publishing snapshot artifacts.

##### <BUILDER_NAME>_RESOLVE_REPO_URL
Defines the url for repository from where dependencies should be resolved.

##### <BUILDER_NAME>_OPTS
Options for the builder.

##### <BUILDER_NAME>_SETTINGS
Path to the builder settings file.

##### <BUILDER_NAME>_DEFAULT_DIRECTIVES
Default directives defined globally so that items can use and build upon. The same format as the job item directives is expected here.

##### <BUILDER_NAME>_DEFAULT_COMMAND_OPTIONS
Default options to include on the build command.