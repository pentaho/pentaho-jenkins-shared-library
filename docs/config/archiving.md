# Archiving Configuration

### ARCHIVING_CONFIG
The archiving configuration that defines what build artifacts are to be archived. This can be specified in different formats:

- path to a manifest yaml file where the different artifacts are specified by filename:
```
ARCHIVING_CONFIG: "${root-path}/artifacts/manifest.yaml"
```
- a list of file names:
```
ARCHIVING_CONFIG:
  - artifac-file-1.zip
  - artifact-file-2.tar.gz
```
- a map with a manifest like structure:
```
ARCHIVING_CONFIG:
  'some-artifacts-group': 'something.zip', 
  'other-artifacts-group': 'something-2.zip'
```
- a list of regular expressions:
```
ARCHIVING_CONFIG:
  - '.*\\.(zip|properties)'
```
### ARCHIVING_EXCLUDE_PATTERN
The pattern that specifies the exclusions when doing the file search. Defaults to the regular expression `'(.*/.git/.*)` 

### ARCHIVING_PATH_GROUP_REGEX
The complementary pattern when searching for artifacts. Since, by default, we only have the file names from the manifest file and in order to improve the artifacts search, this property allows you to tweak where to search for artifacts. Defaults to the regular expression `.*/(target|dist|build)/(?:(?!.*(dependencies)).*/)?`   

### ARCHIVE_TESTS_PATTERN
Allows a way to define a Ant like expression to archive test reports. Defaults to `**/bin/**/TEST*.xml, **/target/**/TEST*.xml, **/build/**/*Test.xml, **/target/**/test*.xml`.