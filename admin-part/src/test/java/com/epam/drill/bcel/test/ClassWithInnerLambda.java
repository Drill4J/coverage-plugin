/**
 * Copyright 2020 - 2022 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.drill.bcel.test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassWithInnerLambda {

    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("method", "lambda$method$1");
        put("lambda$method$1", "lambda$null$0");
    }};

    void method(List<String> strings) {
        strings.stream().map(str ->
                Arrays.stream(str.split(".")).map(it -> it + "2").collect(Collectors.toList())
        ).collect(Collectors.toList());
    }
}
