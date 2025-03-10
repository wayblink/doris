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

package org.apache.doris.nereids.trees.expressions;

import org.apache.doris.catalog.Column;
import org.apache.doris.nereids.trees.expressions.visitor.ExpressionVisitor;
import org.apache.doris.nereids.types.DataType;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;

/**
 * Reference to slot in expression.
 */
public class SlotReference extends Slot {
    private final ExprId exprId;
    private final String name;
    private final List<String> qualifier;
    private final DataType dataType;
    private final boolean nullable;

    public SlotReference(String name, DataType dataType) {
        this(NamedExpressionUtil.newExprId(), name, dataType, true, ImmutableList.of());
    }

    public SlotReference(String name, DataType dataType, boolean nullable) {
        this(NamedExpressionUtil.newExprId(), name, dataType, nullable, ImmutableList.of());
    }

    public SlotReference(String name, DataType dataType, boolean nullable, List<String> qualifier) {
        this(NamedExpressionUtil.newExprId(), name, dataType, nullable, qualifier);
    }

    /**
     * Constructor for SlotReference.
     *
     * @param exprId UUID for this slot reference
     * @param name slot reference name
     * @param dataType slot reference logical data type
     * @param nullable true if nullable
     * @param qualifier slot reference qualifier
     */
    public SlotReference(ExprId exprId, String name, DataType dataType, boolean nullable, List<String> qualifier) {
        this.exprId = exprId;
        this.name = name;
        this.dataType = dataType;
        this.qualifier = qualifier;
        this.nullable = nullable;
    }

    public static SlotReference fromColumn(Column column, List<String> qualifier) {
        DataType dataType = DataType.convertFromCatalogDataType(column.getType());
        return new SlotReference(column.getName(), dataType, column.isAllowNull(), qualifier);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ExprId getExprId() {
        return exprId;
    }

    @Override
    public List<String> getQualifier() {
        return qualifier;
    }

    @Override
    public DataType getDataType() {
        return dataType;
    }

    @Override
    public boolean nullable() {
        return nullable;
    }

    @Override
    public String toSql() {
        return name;
    }

    @Override
    public String toString() {
        // Just return name and exprId, add another method to show fully qualified name when it's necessary.
        return name + "#" + exprId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SlotReference that = (SlotReference) o;
        // The equals of slotRefrance only compares exprId,
        // because in subqueries with aliases,
        // there will be scenarios where the same exprId but different qualifiers are used,
        // resulting in an error due to different qualifiers during comparison.
        // eg:
        // select * from t6 where t6.k1 < (select max(aa) from (select v1 as aa from t7 where t6.k2=t7.v2) t2 )
        //
        // For aa, the qualifier of aa in the subquery is empty, but in the output column of agg,
        // the qualifier of aa is t2. but both actually represent the same column.
        return exprId.equals(that.exprId);
    }

    // The contains method needs to use hashCode, so similar to equals, it only compares exprId
    @Override
    public int hashCode() {
        return Objects.hash(exprId);
    }

    public Column getColumn() {
        return new Column(name, dataType.toCatalogDataType());
    }

    @Override
    public <R, C> R accept(ExpressionVisitor<R, C> visitor, C context) {
        return visitor.visitSlotReference(this, context);
    }

    @Override
    public SlotReference withChildren(List<Expression> children) {
        Preconditions.checkArgument(children.size() == 0);
        return this;
    }

    public Slot withNullable(boolean newNullable) {
        if (this.nullable == newNullable) {
            return this;
        }
        return new SlotReference(exprId, name, dataType, newNullable, qualifier);
    }

    @Override
    public Slot withQualifier(List<String> qualifiers) {
        return new SlotReference(exprId, name, dataType, nullable, qualifiers);
    }

}
