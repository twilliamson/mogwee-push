/*
 * Copyright 2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.mogwee.push;

import com.mogwee.executors.Executors;
import com.mogwee.logging.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ApnsSocket implements Closeable
{
    private static final Logger LOG = Logger.getLogger();

    private final ApnsSocketFactory factory;
    private final FeedbackService.FailedTokenProcessor failedTokenProcessor;
    private final ExecutorService reader = Executors.newSingleThreadExecutor("ApnsSocket Reader");
    private final String[] recentTokens = new String[100];
    private final AtomicInteger errorCount = new AtomicInteger(0);

    private Socket socket = null;
    private OutputStream out = null;
    private InputStream in = null;
    private int nextMessageId = 123; // start at something other than 0 out of paranoia...

    /**
     * Creates an ApnsSocket that logs but otherwise ignores error responses.
     * @param factory the parent socket factory
     */
    public ApnsSocket(ApnsSocketFactory factory)
    {
        this(factory, null);
    }

    /**
     * Creates an ApnsSocket that logs and reports error responses.
     * @param factory the parent socket factory
     * @param failedTokenProcessor callback handler for error responses
     */
    public ApnsSocket(ApnsSocketFactory factory, FeedbackService.FailedTokenProcessor failedTokenProcessor)
    {
        this.factory = factory;
        this.failedTokenProcessor = failedTokenProcessor;
    }

    /**
     * Identical to calling {@link #send(String, byte[], long, java.util.concurrent.TimeUnit)} with an expiration of 8 hours.
     * @param deviceToken the 64-hex-digit APNS token
     * @param payloadBytes the JSON object to send
     * @throws java.io.IOException if an error occurs talking to Apple
     */
    public synchronized void send(String deviceToken, byte[] payloadBytes) throws IOException
    {
        send(deviceToken, payloadBytes, 8, TimeUnit.HOURS);
    }

    /**
     * Sends the push notification.
     * More information about the JSON specified in {@code payloadBytes} can be found in the
     * <a href="http://developer.apple.com/library/ios/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/ApplePushService/ApplePushService.html#//apple_ref/doc/uid/TP40008194-CH100-SW1">"Notification Payload"
     * section of Apple's Local and Push Notification Programming Guide</a>.
     * <p>
     * Note that for the device token, Cocoa's {@code registerForRemoteNotificationTypes:} method
     * returns a string with &lt;, &gt;, and spaces in addition to the hex digits, e.g.,<br/>
     * {@code <1234abcd 1234abcd 1234abcd 1234abcd 1234abcd 1234abcd 1234abcd 1234abcd>}<br/>
     * All of these must be stripped.
     * <p>
     * Example using <a href="http://jackson.codehaus.org/">Jackson</a>'s ObjectMapper to serialize the JSON:
     * <pre> {@code
     * Map<String, Object> aps = new LinkedHashMap<String, Object>();
     *
     * aps.put("alert", "This is a push notification!");
     * aps.put("badge", 15);
     * aps.put("sound", "default");
     *
     * Map<String, Object> payload = new LinkedHashMap<String, Object>();
     *
     * payload.put("aps", aps);
     * payload.put("custom1", "Some custom value (could be a number or Map or whatever...)");
     * payload.put("custom2", 23);
     *
     * byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
     *
     * apnsSocket.send("1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd", payloadBytes);
     * }</pre>
     * @param deviceToken the 64-hex-digit APNS token
     * @param payloadBytes the JSON object to send
     * @param expiration how long to retry delivering to an unreachable device
     * @param expirationUnit the time unit of the expiration parameter
     * @throws IOException if an error occurs talking to Apple
     */
    public synchronized void send(String deviceToken, byte[] payloadBytes, long expiration, TimeUnit expirationUnit) throws IOException
    {
        byte[] tokenBytes = Hex.hexToBytes(deviceToken);

        try {
            //       | 0 | id | expiry | token length | device token | payload length |     payload    |
            // bytes   1    4     4           2         token length         2          payload length
            int bufferSize = 1 + 4 + 4 + 2 + tokenBytes.length + 2 + payloadBytes.length;
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            int messageId = nextMessageId;
            int expirationEpoch = (int) (TimeUnit.SECONDS.convert(DateTimeUtils.currentTimeMillis(), TimeUnit.MILLISECONDS) + TimeUnit.SECONDS.convert(expiration, expirationUnit));

            ++nextMessageId;
            buffer.put((byte) 1);
            buffer.putInt(messageId);
            buffer.putInt(expirationEpoch);
            buffer.putShort((short) tokenBytes.length);
            buffer.put(tokenBytes);
            buffer.putShort((short) payloadBytes.length);
            buffer.put(payloadBytes);

            if (socket == null) {
                socket = factory.createSocket();
                out = socket.getOutputStream();
                in = socket.getInputStream();
                reader.submit(new ErrorChecker());
            }

            byte[] array = buffer.array();

            recentTokens[messageId % recentTokens.length] = deviceToken;
            out.write(array);
            out.flush();
        }
        catch (IOException e) {
            LOG.infoDebugf(e, "Closing socket for %s: %s %s", factory, deviceToken, new String(payloadBytes, "UTF-8"));
            close();

            throw e;
        }
    }

    @Override
    public synchronized void close()
    {
        CloseableUtil.closeQuietly(socket);
        CloseableUtil.closeQuietly(out);
        CloseableUtil.closeQuietly(in);
        socket = null;
        out = null;
        in = null;

        for (int i = 0; i < recentTokens.length; i++) {
            recentTokens[i] = null;
        }
    }

    public int getErrorCount()
    {
        return errorCount.get();
    }

    private class ErrorChecker implements Runnable
    {
        @Override
        public void run()
        {
            try {
                //       | 8 | status | id |
                // bytes   1      1      4
                byte[] error = new byte[1 + 1 + 4];
                int offset = 0;
                int left = error.length;

                while (left > 0) {
                    int read = in.read(error, offset, left);

                    if (read < 0) {
                        if (left != error.length) {
                            LOG.warnf("Socket closed partway through error response: %s", Arrays.toString(error));
                        }

                        return;
                    }

                    left -= read;
                }

                ByteBuffer buffer = ByteBuffer.wrap(error);
                byte command = buffer.get();
                byte status = buffer.get();
                int problemMessageId = buffer.getInt();

                if (command != 8) {
                    LOG.warnf("Funny response from Apple: %s", Arrays.toString(error));
                }

                errorCount.incrementAndGet();

                synchronized (ApnsSocket.this) {
                    int messagesSentSinceProblem = nextMessageId - problemMessageId;

                    if (problemMessageId < 0 || messagesSentSinceProblem < 0) {
                        LOG.warnf("Server reported error %s for message in the future?! (%s > %s)", status, problemMessageId, nextMessageId);
                    }
                    else {
                        if (messagesSentSinceProblem >= recentTokens.length) {
                            LOG.warnf("Too many messages sent since error %s; some messages have been lost (%s - %s > %s)", status, nextMessageId, problemMessageId, recentTokens.length);
                        }
                        else {
                            String failedToken = recentTokens[problemMessageId % recentTokens.length];

                            LOG.infof("Server reported error %s for message %s sent to %s", status, problemMessageId, failedToken);

                            if (status == 8 && failedToken != null && failedTokenProcessor != null) {
                                // invalid token
                                failedTokenProcessor.tokenWithFailure(failedToken, new DateTime(DateTimeZone.UTC));
                            }
                        }
                    }
                }
            }
            catch (IOException e) {
                LOG.infoDebugf(e, "Stopping error reader for %s", factory);
            }
            catch (RuntimeException e) {
                LOG.warnf(e, "Stopping error reader for %s", factory);
            }
            finally {
                close();
            }
        }
    }
}
