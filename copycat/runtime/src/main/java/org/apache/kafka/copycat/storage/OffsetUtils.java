/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package org.apache.kafka.copycat.storage;

import org.apache.kafka.copycat.data.CopycatSchema;
import org.apache.kafka.copycat.data.Schema;
import org.apache.kafka.copycat.errors.DataException;

import java.util.Map;

public class OffsetUtils {
    public static void validateFormat(Object offsetData) {
        if (!(offsetData instanceof Map))
            throw new DataException("Offsets must be specified as a Map");
        validateFormat((Map<Object, Object>) offsetData);
    }

    public static <K, V> void validateFormat(Map<K, V> offsetData) {
        for (Map.Entry<K, V> entry : offsetData.entrySet()) {
            if (!(entry.getKey() instanceof String))
                throw new DataException("Offsets may only use String keys");

            Object value = entry.getValue();
            if (value == null)
                continue;
            Schema.Type schemaType = CopycatSchema.schemaType(value.getClass());
            if (!schemaType.isPrimitive())
                throw new DataException("Offsets may only contain primitive types as values, but field " + entry.getKey() + " contains " + schemaType);
        }
    }
}
