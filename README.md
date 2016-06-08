CloudWatchAppender
==================

Emits log4j events into AWS CloudWatch streams.

## Build

    $ git clone https://github.com/joelbrito/cloudwatch-log4j-appender.git
    $ cd cloudwatch-log4j-appender
    $ mvn install

## Dependency
```
	<dependency>
		<groupId>com.vi</groupId>
    	<artifactId>cloudwatch-log4j-appender</artifactId>
    	<version>1.0.1-log4j1</version>
	</dependency>
```

## Usage
```
    log4j.appender.cloudWatchAppender=com.vi.aws.logging.log4jappenders.CloudWatchAppender
	log4j.appender.cloudWatchAppender.logGroupName=TestLogGroup
	log4j.appender.cloudWatchAppender.logStreamName=TestLogStream
	log4j.appender.cloudWatchAppender.awsAccessKey=ACCESS_KEY
	log4j.appender.cloudWatchAppender.awsSecretKey=SECRET_KEY
	log4j.appender.cloudWatchAppender.awsRegion=us-east-1
	log4j.appender.cloudWatchAppender.layout=org.apache.log4j.PatternLayout
	log4j.appender.cloudWatchAppender.layout.ConversionPattern=[%t] %-5p %c %x - %m%n
```
## Configuration variables

Appender plugin attributes:

+ **logGroupName**: the name of the AWS log group (default: "unknown").
+ **logStreamName**: the name of the AWS log stream inside the AWS log group from above.
  Might also be be overridden by the LOG_STREAM_NAME environment variable (see below).
  Note that the stream name will always be suffixed by the current timestamp.
  This means that every time the logger restarts, it will create a new log stream.
+ **awsAccessKey**: AWS Access Key.
+ **awsSecretKey**: AWS Secret Key.
+ **awsRegion**: AWS Region name
+ **logStreamFlushPeriodInSeconds**: the period of the flusher (default: 5).

## Environment variables

Your AWS credentials should be specified in the AWS environment in the standard way.
For testing purposes, you can override the credentials in the environment:

+ **AWS_ACCESS_KEY**: sets the AWS Access Key.
+ **AWS_SECRET_KEY**: sets the AWS Secret Key.

You can also supply the AWS log stream name via environment:

+ **LOG_STREAM_NAME**: sets the AWS log stream name.

## License

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
