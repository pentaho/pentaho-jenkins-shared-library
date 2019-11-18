## Configuration
Pipelines are configured using data files within the jenkins-pipelines repository. There are 3 main files used to create pipelines:

1. [Jenkinsfile](https://github.com/pentaho/jenkins-pipelines/blob/master/Jenkinsfile)
2. [buildProperties.yaml](https://github.com/pentaho/jenkins-pipelines/blob/master/resources/config/buildProperties.yaml)
3. build specific data file (eg. [sample-pipeline.yaml](https://github.com/pentaho/jenkins-pipelines/blob/master/resources/builders/sample-pipeline.yaml))

It is rare that a typical user of this repository should need to edit the first or second file in the list above. The **Jenkinsfile** is used to setup the stages of the build (Build, Unit Test, etc) but does have a little bit of configuration in them. The **buildProperties.yaml** is used for setting up reasonable global build properties that could be overrode in *all* the build specific data files in this respository.

At it's simplest level the build data files are used to specify build properties. [YAML](http://yaml.org/) was used for the file format as it is a easy to read configuration format. It is also really flexible, it supports simple key-value pairs, lists, and maps. The format also gives the ability to make user created properties.

## Build Data File

Build data files are so important they require their own page. See [Build Data File](https://github.com/pentaho/jenkins-pipelines/wiki/2.2.-Build-File) for more details.

## Build Property Override Priority

There are several places where configuration can be passed to the pipeline. The sources, in order of priority, are the following:

0. Library Defaults (See [default-properties.yaml](../../resources/default-properties.yaml)
1. Jenkins Environment
2. Default Properties (buildProperties.yaml)
3. Build File (my-build.yaml)
4. Jenkinsfile
5. Pipeline Run Parameters

If you specify a parameter named MY_BUILD_PROPERTY in the default properties file (buildProperties.yaml), it will be overrode if also specified in the Build File, Jenkinsfile, or Pipeline Run Parameters.

### Property Composition
It is possible to use property composition while defining properties. For example:

**Default Properties:**

`HELLO_PROPERTY: Hello`

**Build File:**

`WORLD_PROPERTY: ${HELLO_PROPERTY} world!`

Requesting the property `WORLD_PROPERTY` will return "Hello World!"
