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

import org.apache.doris.common.Pair;
import org.apache.doris.nereids.trees.expressions.EqualTo;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.visitor.PlanVisitor;
import org.apache.doris.nereids.util.ExpressionUtils;
import org.apache.doris.nereids.util.JoinUtils;
import org.apache.doris.nereids.util.SlotExtractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A MultiJoin represents a join of N inputs (NAry-Join).
 * The regular Join represent strictly binary input (Binary-Join).
 */
public class MultiJoin extends PlanVisitor<Void, Void> {
    /*
     *        topJoin
     *        /     \            MultiJoin
     *   bottomJoin  C  -->     /    |    \
     *     /    \              A     B     C
     *    A      B
     */
    public final List<Plan> joinInputs = new ArrayList<>();
    public final List<Expression> conjunctsForAllHashJoins = new ArrayList<>();
    private List<Expression> conjunctsKeepInFilter = new ArrayList<>();

    /**
     * reorderJoinsAccordingToConditions
     * @return join or filter
     */
    public Optional<Plan> reorderJoinsAccordingToConditions() {
        if (joinInputs.size() >= 2) {
            Plan root = reorderJoinsAccordingToConditions(joinInputs, conjunctsForAllHashJoins);
            if (!conjunctsKeepInFilter.isEmpty()) {
                root = new LogicalFilter(
                        ExpressionUtils.and(conjunctsKeepInFilter),
                        root
                );
            }
            return Optional.of(root);
        }
        return Optional.empty();
    }

    /**
     * Reorder join orders according to join conditions to eliminate cross join.
     * <p/>
     * Let's say we have input join tables: [t1, t2, t3] and
     * conjunctive predicates: [t1.id=t3.id, t2.id=t3.id]
     * The input join for t1 and t2 is cross join.
     * <p/>
     * The algorithm split join inputs into two groups: `left input` t1 and `candidate right input` [t2, t3].
     * Try to find an inner join from t1 and candidate right inputs [t2, t3], if any combination
     * of [Join(t1, t2), Join(t1, t3)] could be optimized to inner join according to the join conditions.
     * <p/>
     * As a result, Join(t1, t3) is an inner join.
     * Then the logic is applied to the rest of [Join(t1, t3), t2] recursively.
     */
    private Plan reorderJoinsAccordingToConditions(List<Plan> joinInputs, List<Expression> conjuncts) {
        if (joinInputs.size() == 2) {
            Pair<List<Expression>, List<Expression>> pair = JoinUtils.extractExpressionForHashTable(
                    joinInputs.get(0).getOutput().stream().map(SlotReference.class::cast).collect(Collectors.toList()),
                    joinInputs.get(1).getOutput().stream().map(SlotReference.class::cast).collect(Collectors.toList()),
                    conjuncts);
            List<Expression> joinConditions = pair.first;
            conjunctsKeepInFilter = pair.second;
            LogicalJoin join;
            if (joinConditions.isEmpty()) {
                join = new LogicalJoin(JoinType.CROSS_JOIN,
                        new ArrayList<>(),
                        Optional.empty(),
                        joinInputs.get(0), joinInputs.get(1));
            } else {
                join = new LogicalJoin(JoinType.INNER_JOIN,
                        new ArrayList<>(),
                        Optional.of(ExpressionUtils.and(joinConditions)),
                        joinInputs.get(0), joinInputs.get(1));
            }

            return join;
        }
        // input size >= 3;
        Plan left = joinInputs.get(0);
        List<Plan> candidate = joinInputs.subList(1, joinInputs.size());

        List<Slot> leftOutput = left.getOutput();
        Optional<Plan> rightOpt = candidate.stream().filter(right -> {
            List<Slot> rightOutput = right.getOutput();

            Set<Slot> joinOutput = getJoinOutput(left, right);
            Optional<Expression> joinCond = conjuncts.stream()
                    .filter(expr -> {
                        Set<Slot> exprInputSlots = SlotExtractor.extractSlot(expr);
                        if (exprInputSlots.isEmpty()) {
                            return false;
                        }

                        if (new HashSet<>(leftOutput).containsAll(exprInputSlots)) {
                            return false;
                        }

                        if (new HashSet<>(rightOutput).containsAll(exprInputSlots)) {
                            return false;
                        }

                        return joinOutput.containsAll(exprInputSlots);
                    }).findFirst();
            return joinCond.isPresent();
        }).findFirst();

        Plan right = rightOpt.orElseGet(() -> candidate.get(1));
        Pair<List<Expression>, List<Expression>> pair = JoinUtils.extractExpressionForHashTable(
                joinInputs.get(0).getOutput().stream().map(SlotReference.class::cast).collect(Collectors.toList()),
                joinInputs.get(1).getOutput().stream().map(SlotReference.class::cast).collect(Collectors.toList()),
                conjuncts);
        List<Expression> joinConditions = pair.first;
        List<Expression> nonJoinConditions = pair.second;
        LogicalJoin join;
        if (joinConditions.isEmpty()) {
            join = new LogicalJoin(JoinType.CROSS_JOIN, new ArrayList<Expression>(),
                    Optional.empty(),
                    left, right);
        } else {
            join = new LogicalJoin(JoinType.INNER_JOIN, new ArrayList<>(),
                    Optional.of(ExpressionUtils.and(joinConditions)),
                    left, right);
        }

        List<Plan> newInputs = new ArrayList<>();
        newInputs.add(join);
        newInputs.addAll(candidate.stream().filter(plan -> !right.equals(plan)).collect(Collectors.toList()));
        return reorderJoinsAccordingToConditions(newInputs, nonJoinConditions);
    }

    private Map<Boolean, List<Expression>> splitConjuncts(List<Expression> conjuncts, Set<Slot> slots) {
        return conjuncts.stream().collect(Collectors.partitioningBy(
                // TODO: support non equal to conditions.
                expr -> expr instanceof EqualTo && slots.containsAll(SlotExtractor.extractSlot(expr))));
    }

    private Set<Slot> getJoinOutput(Plan left, Plan right) {
        HashSet<Slot> joinOutput = new HashSet<>();
        joinOutput.addAll(left.getOutput());
        joinOutput.addAll(right.getOutput());
        return joinOutput;
    }

    @Override
    public Void visit(Plan plan, Void context) {
        for (Plan child : plan.children()) {
            child.accept(this, context);
        }
        return null;
    }

    @Override
    public Void visitLogicalFilter(LogicalFilter<Plan> filter, Void context) {
        Plan child = filter.child();
        if (child instanceof LogicalJoin) {
            conjunctsForAllHashJoins.addAll(ExpressionUtils.extractConjunction(filter.getPredicates()));
        }

        child.accept(this, context);
        return null;
    }

    @Override
    public Void visitLogicalJoin(LogicalJoin<Plan, Plan> join, Void context) {
        if (join.getJoinType() != JoinType.CROSS_JOIN && join.getJoinType() != JoinType.INNER_JOIN) {
            return null;
        }

        join.left().accept(this, context);
        join.right().accept(this, context);

        conjunctsForAllHashJoins.addAll(join.getHashJoinConjuncts());
        if (join.getOtherJoinCondition().isPresent()) {
            conjunctsForAllHashJoins.addAll(ExpressionUtils.extractConjunction(join.getOtherJoinCondition().get()));
        }

        if (!(join.left() instanceof LogicalJoin)) {
            joinInputs.add(join.left());
        }
        if (!(join.right() instanceof LogicalJoin)) {
            joinInputs.add(join.right());
        }
        return null;
    }
}
