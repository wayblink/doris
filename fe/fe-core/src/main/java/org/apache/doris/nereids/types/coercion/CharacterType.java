// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.types.coercion;

import org.apache.doris.catalog.Type;
import org.apache.doris.nereids.types.DataType;
import org.apache.doris.nereids.types.StringType;

/**
 * Abstract type for all characters type in Nereids.
 */
public class CharacterType extends PrimitiveType {

    public static final CharacterType INSTANCE = new CharacterType(-1);

    protected final int len;

    public CharacterType(int len) {
        this.len = len;
    }

    public int getLen() {
        return len;
    }

    @Override
    public Type toCatalogDataType() {
        throw new RuntimeException("CharacterType is only used for implicit cast.");
    }

    @Override
    public boolean acceptsType(DataType other) {
        return other instanceof CharacterType;
    }

    @Override
    public DataType defaultConcreteType() {
        return StringType.INSTANCE;
    }
}
