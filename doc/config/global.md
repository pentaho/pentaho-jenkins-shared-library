# Global Configuration Properties

Global build configuration properties and their usage. 
See [default-properties.yaml](../../resources/default-properties.yaml) for a list of available property combinations. 

### DEFAULT_BUILD_PROPERTIES
Default location to look for an external configuration that overrides the default one. 

### OVERRIDE_JOB_PARAMS
This configuration is meant to be used as a job param to provide the means to quickly override a job item configuration for the current build.
The provided value should be in a valid yaml format and all the job item properties are available for use.

Some examples.

##### Multi Item Entry
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

##### Single Item Entry
When specifying a single item, provide values as if you were specifying a job item directly.

```
jobID: my-item-id
directives: clean install
```

##### Omitting jobID 

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
