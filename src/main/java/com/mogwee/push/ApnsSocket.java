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

import com.mogwee.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ApnsSocket
{
    private static final Logger LOG = Logger.getLogger();

    private final ApnsSocketFactory factory;

    private Socket socket = null;
    private OutputStream out = null;

    public ApnsSocket(ApnsSocketFactory factory)
    {
        this.factory = factory;
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
     * @throws IOException if an error occurs talking to Apple
     */
    public synchronized void send(String deviceToken, byte[] payloadBytes) throws IOException
    {
        byte[] tokenBytes = getTokenBytes(deviceToken);

        try {
            //       | 0 | token length | device token | payload length |     payload    |
            // bytes   1        2         token length         2          payload length
            int bufferSize = 1 + 2 + tokenBytes.length + 2 + payloadBytes.length;
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

            buffer.put((byte) 0);
            buffer.putShort((short) tokenBytes.length);
            buffer.put(tokenBytes);
            buffer.putShort((short) payloadBytes.length);
            buffer.put(payloadBytes);

            if (socket == null) {
                socket = factory.createSocket();
                out = socket.getOutputStream();
            }

            out.write(buffer.array());
            out.flush();
        }
        catch (IOException e) {
            LOG.infoDebugf(e, "Closing socket for %s: %s %s", factory, deviceToken, new String(payloadBytes, "UTF-8"));
            CloseableUtil.closeQuietly(socket);
            CloseableUtil.closeQuietly(out);
            socket = null;
            out = null;

            throw e;
        }
    }

    private byte[] getTokenBytes(String deviceToken)
    {
        byte[] tokenBytes = new byte[deviceToken.length() / 2];

        for (int i = 0; i < deviceToken.length(); i += 2) {
            char c1 = deviceToken.charAt(i);
            char c2 = deviceToken.charAt(i + 1);
            int value = 0;

            if ('0' <= c1 && c1 <= '9') {
                value = c1 - '0';
            }
            else if ('a' <= c1 && c1 <= 'f') {
                value = c1 - ('a' - 10);
            }
            else if ('A' <= c1 && c1 <= 'f') {
                value = c1 - ('A' - 10);
            }

            value <<= 4;

            if ('0' <= c2 && c2 <= '9') {
                value |= c2 - '0';
            }
            else if ('a' <= c2 && c2 <= 'f') {
                value |= c2 - ('a' - 10);
            }
            else if ('A' <= c2 && c2 <= 'f') {
                value |= c2 - ('A' - 10);
            }

            tokenBytes[i / 2] = (byte) value;
        }

        return tokenBytes;
    }
}
