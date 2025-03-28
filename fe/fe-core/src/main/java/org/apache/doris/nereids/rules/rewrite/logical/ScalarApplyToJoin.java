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

package org.apache.doris.nereids.rules.rewrite.logical;

import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.rules.rewrite.OneRewriteRuleFactory;
import org.apache.doris.nereids.trees.expressions.AssertNumRowsElement;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalApply;
import org.apache.doris.nereids.trees.plans.logical.LogicalAssertNumRows;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * Convert scalarApply to LogicalJoin.
 *
 * UnCorrelated -> CROSS_JOIN
 * Correlated -> LEFT_OUTER_JOIN
 */
public class ScalarApplyToJoin extends OneRewriteRuleFactory {
    @Override
    public Rule build() {
        return logicalApply().when(LogicalApply::isScalar).then(apply -> {
            if (apply.isCorrelated()) {
                return correlatedToJoin(apply);
            } else {
                return unCorrelatedToJoin(apply);
            }
        }).toRule(RuleType.SCALAR_APPLY_TO_JOIN);
    }

    private Plan unCorrelatedToJoin(LogicalApply apply) {
        LogicalAssertNumRows assertNumRows = new LogicalAssertNumRows(
                new AssertNumRowsElement(
                        1, apply.getSubqueryExpr().toString(),
                        AssertNumRowsElement.Assertion.EQ),
                (LogicalPlan) apply.right());
        LogicalJoin newJoin = new LogicalJoin<>(JoinType.CROSS_JOIN,
                (LogicalPlan) apply.left(), assertNumRows);
        List<Slot> projects = ((LogicalPlan) apply.left()).getOutput();
        return new LogicalProject(projects, newJoin);
    }

    private Plan correlatedToJoin(LogicalApply apply) {
        return new LogicalJoin<>(JoinType.LEFT_OUTER_JOIN,
                Lists.newArrayList(),
                apply.getCorrelationFilter(),
                (LogicalPlan) apply.left(),
                (LogicalPlan) apply.right());
    }
}
