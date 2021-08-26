/**
 * Copyright 2020 EPAM Systems
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
package com.epam.drill.instrumentation.data;

import java.util.Random;

public class InvokeCycles implements Runnable {

    @Override
    public void run() {
        Random random = new Random();
        long i = manyForInFor(random.nextLong());
        System.out.println("res====" + i);
    }

    public long manyForInFor(long start) {
        long sum = start;
        Random random = new Random();
        int firstRange = 2;
        int toMin = 9;
        int toMax = 2;
        for (int i10 = random.nextInt(firstRange); i10 <= random.nextInt(toMax) + toMin; i10++) {
            sum += i10;
            for (int i9 = random.nextInt(firstRange); i9 <= random.nextInt(toMax) + toMin; i9++) {
                sum += i9;
                for (int i8 = random.nextInt(firstRange); i8 <= random.nextInt(toMax) + toMin; i8++) {
                    sum += i8;
                    for (int i7 = random.nextInt(firstRange); i7 <= random.nextInt(toMax) + toMin; i7++) {
                        sum += i7;
                        for (int i6 = random.nextInt(firstRange); i6 <= random.nextInt(toMax) + toMin; i6++) {
                            sum += i6;
                            for (int i5 = random.nextInt(firstRange); i5 <= random.nextInt(toMax) + toMin; i5++) {
                                sum += i5;
                                for (int i4 = random.nextInt(firstRange); i4 <= random.nextInt(toMax) + toMin; i4++) {
                                    sum += i4;
                                    for (int i3 = random.nextInt(firstRange); i3 <= random.nextInt(toMax) + toMin; i3++) {
                                        sum += i3;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return sum;
    }

}
