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
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.ReadableDateTime;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.net.Socket;

public class FeedbackService
{
    private static final Logger LOG = Logger.getLogger();
    private static final char[] HEX_CHARS = new char[] {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private final ApnsSocketFactory socketFactory;

    public static interface FailedTokenProcessor
    {
        public void tokenWithFailure(String token, ReadableDateTime timestamp);
    }

    public FeedbackService(ApnsSocketFactory socketFactory)
    {
        this.socketFactory = socketFactory;
    }

    public void processFailedTokens(FailedTokenProcessor processor) throws Exception
    {
        Socket socket = null;

        try {
            socket = socketFactory.createSocket();

            DataInputStream stream = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8096));
            byte[] tokenBuffer = new byte[28];

            while (true) {
                ReadableDateTime timestamp;

                try {
                    timestamp = new DateTime(stream.readInt() * 1000L, DateTimeZone.UTC);
                }
                catch (EOFException e) {
                    LOG.debug(e, "Encountered EOF signalling end of token stream");

                    return;
                }

                int tokenLength = stream.readUnsignedShort();

                if (tokenBuffer.length < tokenLength) {
                    LOG.infof("Increasing token buffer size to %s (this is unexpected)", tokenLength);
                    tokenBuffer = new byte[tokenLength];
                }

                int bytesRead = stream.read(tokenBuffer, 0, tokenLength);
                StringBuilder tokenBuilder = new StringBuilder(2 * tokenLength);

                if (bytesRead != tokenLength) {
                    LOG.warnf("Expected to read %s bytes, but only got %s", tokenLength, bytesRead);
                    tokenLength = bytesRead;
                }

                for (int i = 0; i < tokenLength; ++i) {
                    int b = tokenBuffer[i];

                    tokenBuilder.append(HEX_CHARS[(b >>> 4) & 0xf]);
                    tokenBuilder.append(HEX_CHARS[b & 0xf]);
                }

                String token = tokenBuilder.toString();

                LOG.debugf("Processing failure of %s that occurred %s", token, timestamp);
                processor.tokenWithFailure(token, timestamp);
            }
        }
        finally {
            CloseableUtil.closeQuietly(socket);
        }
    }
}
