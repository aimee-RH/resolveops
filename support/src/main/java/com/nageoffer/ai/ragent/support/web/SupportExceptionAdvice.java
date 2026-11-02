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

package com.nageoffer.ai.ragent.support.web;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.support.SupportException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = {"com.nageoffer.ai.ragent.support", "com.nageoffer.ai.ragent.agent.support"})
public class SupportExceptionAdvice {
    @ExceptionHandler(SupportException.class)
    public ResponseEntity<Result<Void>> handle(SupportException error) {
        return ResponseEntity.status(error.code().httpStatus())
                .body(new Result<Void>().setCode("SUPPORT_" + error.code().name()).setMessage(error.code().name()));
    }
}
