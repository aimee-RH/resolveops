/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.support;

public final class SupportException extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT(400), NOT_AUTHENTICATED(401), NOT_FOUND_OR_FORBIDDEN(404),
        CONFLICT(409), INVALID_DIAGNOSIS(422), CONFIGURATION_REQUIRED(503), UNAVAILABLE(503);

        private final int httpStatus;
        Code(int httpStatus) { this.httpStatus = httpStatus; }
        public int httpStatus() { return httpStatus; }
    }

    private final Code code;

    public SupportException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
