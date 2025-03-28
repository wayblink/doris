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

package org.apache.doris.nereids.glue.translator;

import org.apache.doris.catalog.OlapTable;
import org.apache.doris.nereids.properties.LogicalProperties;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.GreaterThan;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.literal.IntegerLiteral;
import org.apache.doris.nereids.trees.expressions.literal.Literal;
import org.apache.doris.nereids.trees.plans.physical.PhysicalFilter;
import org.apache.doris.nereids.trees.plans.physical.PhysicalOlapScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalProject;
import org.apache.doris.nereids.types.IntegerType;
import org.apache.doris.planner.OlapScanNode;
import org.apache.doris.planner.PlanFragment;
import org.apache.doris.planner.PlanNode;

import mockit.Injectable;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class PhysicalPlanTranslatorTest {

    @Test
    public void testOlapPrune(@Mocked OlapTable t1, @Injectable LogicalProperties placeHolder) throws Exception {
        List<String> qualifierList = new ArrayList<>();
        qualifierList.add("test");
        qualifierList.add("t1");
        List<Slot> t1Output = new ArrayList<>();
        SlotReference col1 = new SlotReference("col1", IntegerType.INSTANCE);
        SlotReference col2 = new SlotReference("col2", IntegerType.INSTANCE);
        SlotReference col3 = new SlotReference("col2", IntegerType.INSTANCE);
        t1Output.add(col1);
        t1Output.add(col2);
        t1Output.add(col3);
        LogicalProperties t1Properties = new LogicalProperties(() -> t1Output);
        PhysicalOlapScan scan = new PhysicalOlapScan(t1, qualifierList, 0L,
                Collections.emptyList(), Collections.emptyList(), null,
                Optional.empty(),
                t1Properties);
        Literal t1FilterRight = new IntegerLiteral(1);
        Expression t1FilterExpr = new GreaterThan(col1, t1FilterRight);
        PhysicalFilter<PhysicalOlapScan> filter =
                new PhysicalFilter(t1FilterExpr, placeHolder, scan);
        List<NamedExpression> projList = new ArrayList<>();
        projList.add(col2);
        PhysicalProject<PhysicalFilter> project = new PhysicalProject(projList,
                placeHolder, filter);
        PlanTranslatorContext planTranslatorContext = new PlanTranslatorContext();
        PhysicalPlanTranslator translator = new PhysicalPlanTranslator();
        PlanFragment fragment = translator.visitPhysicalProject(project, planTranslatorContext);
        PlanNode planNode = fragment.getPlanRoot();
        List<OlapScanNode> scanNodeList = new ArrayList<>();
        planNode.collect(OlapScanNode.class::isInstance, scanNodeList);
        Assertions.assertEquals(2, scanNodeList.get(0).getTupleDesc().getMaterializedSlots().size());
    }
}
