# Slack Integration
Slack configuration properties to configure the slack report when it's part of the pipeline.

### SLACK_INTEGRATION
If true, messages will be sent to the configured slack channel.

**Default:** false

### SLACK_CHANNEL
Channel names to send the reports. Multiple channels can be defined separated with a spaces. Additionally channels can also be defined per build status.

#### Simple definition
```
SLACK_CHANNEL: channel1 channel2
```

#### Build result definition
```
SLACK_CHANNEL: 
  BUILD_NOT_BUILT: channel1
  BUILD_UNSTABLE: channel2
  BUILD_FAILURE: channel3
  BUILD_SUCCESS: channel4
  BUILD_ABORTED: channel5
```

### SLACK_TEAM_DOMAIN
Slack team domain.

### SLACK_CREDENTIALS_ID
Credentials id to use from jenkins. This should be a secret text key holding the access token.
