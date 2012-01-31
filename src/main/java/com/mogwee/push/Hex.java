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

class Hex
{
    public static byte[] hexToBytes(String hex)
    {
        if ((hex.length() & 1) != 0) {
            hex += '0';
        }

        byte[] bytes = new byte[hex.length() / 2];

        for (int i = 0; i < hex.length(); i += 2) {
            char c1 = hex.charAt(i);
            char c2 = hex.charAt(i + 1);
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

            bytes[i / 2] = (byte) value;
        }

        return bytes;
    }
}
