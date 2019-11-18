This shared library serves the purpose of automating several repetitive pipeline definitions commonly used while building and testing Pentaho software. It allows the creation of a full execution pipeline based of a custom configuration file while maintaining an implementation as modular as possible, allowing only parts of the functionality to be leveraged when needed. 

For more information on what a Jenkins Shared Library is and how to set it up in your Jenkins environment please refer to [the oficial documentation](https://jenkins.io/doc/book/pipeline/shared-libraries/).

# Usage Documentation
* Requirements
* [Configuration](config/intro.md)
  - [The Build File](config/buildfile.md)
  - [General](config/general.md)
  - [SCM](config/scm.md)
  - [Builders](config/builders.md)
  - [Stages](config/stages.md)
  - [Cleanup](config/cleanup.md)
  - [Minions](config/minions.md)
  - [Job items](config/jobitems.md)
  - [Archiving](config/archiving.md)
  - [Slack Integration](config/slack.md)
  - [Overrides](config/overrides.md)
* Vars
  - stages
  - job
  - config
* Usage Examples

