package com.vi.aws.logging.log4jappenders;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.logs.AWSLogsClient;
import com.amazonaws.services.logs.model.*;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import static com.vi.aws.logging.log4jappenders.Config.*;

/**
 * Created by mihailo.despotovic on 4/8/15.
 */
public class CloudWatchAppender extends AppenderSkeleton {

    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy.MM.dd HH.mm.ss"); // aws doesn't allow
                                                                                                   // ":" in stream name

    private static final String AWS_INSTANCE_ID; // per-instance, so static

    static {
        AWS_INSTANCE_ID = retrieveInstanceId();
    }

    private final BlockingQueue<InputLogEvent> queue = new LinkedBlockingQueue<>(AWS_LOG_STREAM_MAX_QUEUE_DEPTH);
    private volatile boolean shutdown = false;
    private int flushPeriodMillis = AWS_LOG_STREAM_FLUSH_PERIOD_IN_SECONDS * 1000;
    private Thread deliveryThread;
    private final Object monitor = new Object();

    private String sequenceTokenCache = null; // aws doc: "Every PutLogEvents request must include the sequenceToken
                                              // obtained from the response of the previous request.
    private long lastReportedTimestamp = -1;

    private String logGroupName = DEFAULT_AWS_LOG_GROUP_NAME;
    private String logStreamName;
    private String regionName;
    private String accessKey;
    private String secretKey;

    private AWSLogsClient awsLogsClient = null;
    private volatile boolean queueFull = false;

    public CloudWatchAppender() {
    }

    @Override
    public void activateOptions() {

        if (getLayout() == null) {
            setLayout(new PatternLayout());
        }

        try {

            if (accessKey != null && secretKey != null) {
                BasicAWSCredentials creds = new BasicAWSCredentials(accessKey, secretKey);
                awsLogsClient = new AWSLogsClient(creds);
            } else {
                awsLogsClient = new AWSLogsClient(); // this should pull the credentials automatically from the
                                                     // environment
            }

            if (regionName != null)
                awsLogsClient.setRegion(RegionUtils.getRegion(regionName));

            if (logStreamName == null) {
                logStreamName = ENV_LOG_STREAM_NAME;
            }
            if (logStreamName == null) {
                logStreamName = AWS_INSTANCE_ID;
            }

            this.sequenceTokenCache = createLogGroupAndLogStreamIfNeeded(logGroupName, logStreamName);

            debug("Starting cloudWatchAppender for: " + logGroupName + ":" + logStreamName);
            deliveryThread = new Thread(messageProcessor, "CloudWatchAppenderDeliveryThread");
            deliveryThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Runnable messageProcessor = new Runnable() {
        @Override
        public void run() {
            debug("Draining queue for " + logStreamName + " stream every " + (flushPeriodMillis / 1000) + "s...");
            while (!shutdown) {
                try {
                    flush();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                if (!shutdown && queue.size() < AWS_DRAIN_LIMIT) {
                    try {
                        synchronized (monitor) {
                            monitor.wait(flushPeriodMillis);
                        }
                    } catch (InterruptedException ix) {
                        ix.printStackTrace();
                    }
                }
            }

            while (!queue.isEmpty()) {
                flush();
            }
        }
    };

    private void flush() {
        int drained;
        final List<InputLogEvent> logEvents = new ArrayList<>(AWS_DRAIN_LIMIT);
        do {
            drained = queue.drainTo(logEvents, AWS_DRAIN_LIMIT);
            if (logEvents.isEmpty()) {
                break;
            }
            Collections.sort(logEvents, new Comparator<InputLogEvent>() {
                @Override
                public int compare(InputLogEvent o1, InputLogEvent o2) {
                    return o1.getTimestamp().compareTo(o2.getTimestamp());
                }
            });
            if (lastReportedTimestamp > 0) {
                // in the off chance that the new events start with older TS than the last sent event
                // reset their timestamps to the last timestamp until we reach an event with
                // higher timestamp
                for (InputLogEvent event : logEvents) {
                    if (event.getTimestamp() < lastReportedTimestamp)
                        event.setTimestamp(lastReportedTimestamp);
                    else
                        break;
                }
            }
            lastReportedTimestamp = logEvents.get(logEvents.size() - 1).getTimestamp();
            final PutLogEventsRequest putLogEventsRequest = new PutLogEventsRequest(logGroupName, logStreamName,
                    logEvents);
            putLogEventsRequest.setSequenceToken(sequenceTokenCache);
            try {
                // 1 MB or 10000 messages AWS cap!
                final PutLogEventsResult putLogEventsResult = awsLogsClient.putLogEvents(putLogEventsRequest);
                sequenceTokenCache = putLogEventsResult.getNextSequenceToken();
            } catch (final DataAlreadyAcceptedException daae) {
                debug("DataAlreadyAcceptedException, will reset the token to the expected one");
                sequenceTokenCache = daae.getExpectedSequenceToken();
            } catch (final InvalidSequenceTokenException iste) {
                debug("InvalidSequenceTokenException, will reset the token to the expected one");
                sequenceTokenCache = iste.getExpectedSequenceToken();
            } catch (Exception e) {
                debug("Error writing logs");
                e.printStackTrace();
            }
            logEvents.clear();
        } while (drained >= AWS_DRAIN_LIMIT);
    }

    /**
     * Create AWS log event based on the log4j log event and add it to the queue.
     */
    public void append(final LoggingEvent event) {
        final InputLogEvent awsLogEvent = new InputLogEvent();
        final long timestamp = event.getTimeStamp();
        final String message = getLayout().format(event);
        awsLogEvent.setTimestamp(timestamp);
        awsLogEvent.setMessage(message);
        if (!queue.offer(awsLogEvent) && !queueFull) {
            debug("Log queue is full!");
            queueFull = true;
        } else if (queueFull)
            queueFull = false;
    }

    /**
     * Create log group and log stream if needed.
     *
     * @param logGroupName the name of the log group
     * @param logStreamName the name of the stream
     * @return sequence token for the created stream
     */
    private String createLogGroupAndLogStreamIfNeeded(String logGroupName, String logStreamName) {
        final DescribeLogGroupsResult describeLogGroupsResult = awsLogsClient
                .describeLogGroups(new DescribeLogGroupsRequest().withLogGroupNamePrefix(logGroupName));
        boolean createLogGroup = true;
        if (describeLogGroupsResult != null && describeLogGroupsResult.getLogGroups() != null
                && !describeLogGroupsResult.getLogGroups().isEmpty()) {
            for (final LogGroup lg : describeLogGroupsResult.getLogGroups()) {
                if (logGroupName.equals(lg.getLogGroupName())) {
                    createLogGroup = false;
                    break;
                }
            }
        }
        if (createLogGroup) {
            debug("Creating logGroup: " + logGroupName);
            final CreateLogGroupRequest createLogGroupRequest = new CreateLogGroupRequest(logGroupName);
            awsLogsClient.createLogGroup(createLogGroupRequest);
        }
        String logSequenceToken = null;
        boolean createLogStream = true;
        final DescribeLogStreamsRequest describeLogStreamsRequest = new DescribeLogStreamsRequest(logGroupName)
                .withLogStreamNamePrefix(logStreamName);
        final DescribeLogStreamsResult describeLogStreamsResult = awsLogsClient
                .describeLogStreams(describeLogStreamsRequest);
        if (describeLogStreamsResult != null && describeLogStreamsResult.getLogStreams() != null
                && !describeLogStreamsResult.getLogStreams().isEmpty()) {
            for (final LogStream ls : describeLogStreamsResult.getLogStreams()) {
                if (logStreamName.equals(ls.getLogStreamName())) {
                    createLogStream = false;
                    logSequenceToken = ls.getUploadSequenceToken();
                }
            }
        }

        if (createLogStream) {
            debug("Creating logStream: " + logStreamName);
            final CreateLogStreamRequest createLogStreamRequest = new CreateLogStreamRequest(logGroupName,
                    logStreamName);
            awsLogsClient.createLogStream(createLogStreamRequest);
        }
        return logSequenceToken;
    }

    // tiny helper self-describing methods

    private String getTimeNow() {
        return simpleDateFormat.format(new Date());
    }

    private void debug(final String s) {
        System.out.println(getTimeNow() + " CloudWatchAppender: " + s);
    }

    @Override
    public void close() {
        shutdown = true;
        if (deliveryThread != null) {
            synchronized (monitor) {
                monitor.notify();
            }
            try {
                deliveryThread.join(SHUTDOWN_TIMEOUT_MILLIS);
            } catch (InterruptedException ix) {
                ix.printStackTrace();
            }
        }
        if (queue.size() > 0) {
            flush();
        }
    }

    @Override
    public boolean requiresLayout() {
        return true;
    }

    /**
     * Returns the AWS log group name
     * 
     * @return
     */
    public String getLogGroupName() {
        return logGroupName;
    }

    /**
     * Sets the AWS log group name
     * 
     * @param logGroupName
     */
    public void setLogGroupName(String logGroupName) {
        this.logGroupName = logGroupName;
    }

    /**
     * Returns the AWS log stream name
     * 
     * @return
     */
    public String getLogStreamName() {
        return logStreamName;
    }

    /**
     * Sets the AWS log stream name
     * 
     * @param logStreamName
     */
    public void setLogStreamName(String logStreamName) {
        this.logStreamName = logStreamName;
    }

    /**
     * Sets the log stream flush period
     * 
     * @param seconds flush period in seconds
     */
    public void setLogStreamFlushPeriodInSeconds(int seconds) {
        this.flushPeriodMillis = seconds * 1000;
    }

    /**
     * Returns the log stream flush period
     * 
     * @return flush period in seconds
     */
    public int getLogStreamFlushPeriodInSeconds() {
        return this.flushPeriodMillis / 1000;
    }

    /**
     * Returns the AWS region
     * 
     * @return
     */
    public String getAwsRegion() {
        return regionName;
    }

    /**
     * Sets the AWS region
     * 
     * @param regionName
     */
    public void setAwsRegion(String regionName) {
        this.regionName = regionName;
    }

    /**
     * Sets the AWS access key
     * 
     * @param accessKey
     */
    public void setAwsAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * Sets the AWS secret key
     * 
     * @param secretKey
     */
    public void setAwsSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

}
