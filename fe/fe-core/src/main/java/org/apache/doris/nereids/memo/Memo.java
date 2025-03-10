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

package org.apache.doris.nereids.memo;

import org.apache.doris.common.IdGenerator;
import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.StatementContext;
import org.apache.doris.nereids.properties.LogicalProperties;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.plans.GroupPlan;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Representation for memo in cascades optimizer.
 */
public class Memo {
    // generate group id in memo is better for test, since we can reproduce exactly same Memo.
    private final IdGenerator<GroupId> groupIdGenerator = GroupId.createGenerator();
    private final Map<GroupId, Group> groups = Maps.newLinkedHashMap();
    // we could not use Set, because Set does not have get method.
    private final Map<GroupExpression, GroupExpression> groupExpressions = Maps.newHashMap();
    private Group root;

    public Memo(Plan plan) {
        root = init(plan);
    }

    public Group getRoot() {
        return root;
    }

    public List<Group> getGroups() {
        return ImmutableList.copyOf(groups.values());
    }

    public Map<GroupExpression, GroupExpression> getGroupExpressions() {
        return groupExpressions;
    }

    /**
     * Add plan to Memo.
     * TODO: add ut later
     *
     * @param plan {@link Plan} or {@link Expression} to be added
     * @param target target group to add node. null to generate new Group
     * @param rewrite whether to rewrite the node to the target group
     * @return CopyInResult, in which the generateNewExpression is true if a newly generated
     *                       groupExpression added into memo, and the correspondingExpression
     *                       is the corresponding group expression of the plan
     */
    public CopyInResult copyIn(Plan plan, @Nullable Group target, boolean rewrite) {
        if (rewrite) {
            return doRewrite(plan, target);
        } else {
            return doCopyIn(plan, target);
        }
    }

    public Plan copyOut() {
        return copyOut(root, false);
    }

    public Plan copyOut(boolean includeGroupExpression) {
        return copyOut(root, includeGroupExpression);
    }

    /**
     * copyOut the group.
     * @param group the group what want to copyOut
     * @param includeGroupExpression whether include group expression in the plan
     * @return plan
     */
    public Plan copyOut(Group group, boolean includeGroupExpression) {
        GroupExpression logicalExpression = group.getLogicalExpression();
        List<Plan> children = Lists.newArrayList();
        for (Group child : logicalExpression.children()) {
            children.add(copyOut(child, includeGroupExpression));
        }
        Plan planWithChildren = logicalExpression.getPlan().withChildren(children);

        Optional<GroupExpression> groupExpression = includeGroupExpression
                ? Optional.of(logicalExpression)
                : Optional.empty();

        return planWithChildren.withGroupExpression(groupExpression);
    }

    /**
     * Utility function to create a new {@link CascadesContext} with this Memo.
     */
    public CascadesContext newCascadesContext(StatementContext statementContext) {
        return new CascadesContext(this, statementContext);
    }

    /**
     * init memo by a first plan.
     * @param plan first plan
     * @return plan's corresponding group
     */
    private Group init(Plan plan) {
        Preconditions.checkArgument(!(plan instanceof GroupPlan), "Cannot init memo by a GroupPlan");

        // initialize children recursively
        List<Group> childrenGroups = plan.children()
                .stream()
                .map(this::init)
                .collect(ImmutableList.toImmutableList());

        plan = replaceChildrenToGroupPlan(plan, childrenGroups);
        GroupExpression newGroupExpression = new GroupExpression(plan, childrenGroups);
        Group group = new Group(groupIdGenerator.getNextId(), newGroupExpression, plan.getLogicalProperties());

        groups.put(group.getGroupId(), group);
        if (groupExpressions.containsKey(newGroupExpression)) {
            throw new IllegalStateException("groupExpression already exists in memo, maybe a bug");
        }
        groupExpressions.put(newGroupExpression, newGroupExpression);
        return group;
    }

    /**
     * add or replace the plan into the target group.
     *
     * the result truth table:
     * <pre>
     * +---------------------------------------+-----------------------------------+--------------------------------+
     * | case                                  | is generated new group expression | corresponding group expression |
     * +---------------------------------------+-----------------------------------+--------------------------------+
     * | case 1:                               |                                   |                                |
     * | if plan is GroupPlan                  |              false                |    existed group expression    |
     * | or plan has groupExpression           |                                   |                                |
     * +---------------------------------------+-----------------------------------+--------------------------------+
     * | case 2:                               |                                   |                                |
     * | if targetGroup is null                |              true                 |      new group expression      |
     * | and same group expression exist       |                                   |                                |
     * +---------------------------------------+-----------------------------------+--------------------------------+
     * | case 3:                               |                                   |                                |
     * | if targetGroup is null                |              true                 |      new group expression      |
     * | and same group expression not exits   |                                   |                                |
     * +---------------------------------------+-----------------------------------+--------------------------------+
     * | case 4:                               |                                   |                                |
     * | if targetGroup equal to the exists    |              true                 |      new group expression      |
     * | group expression's owner group        |                                   |                                |
     * +---------------------------------------+-----------------------------------+--------------------------------+
     * | case 5:                               |                                   |                                |
     * | if targetGroup not equal to the       |              false                |    existed group expression    |
     * | exists group expression's owner group |                                   |                                |
     * +---------------------------------------+-----------------------------------+--------------------------------+
     * </pre>
     *
     * @param plan the plan which want to rewrite or added
     * @param targetGroup target group to replace plan. null to generate new Group. It should be the ancestors
     *                    of the plan's group, or equals to the plan's group, we do not check this constraint
     *                    completely because of performance.
     * @return a pair, in which the first element is true if a newly generated groupExpression added into memo,
     *         and the second element is a reference of node in Memo
     */
    private CopyInResult doRewrite(Plan plan, @Nullable Group targetGroup) {
        Preconditions.checkArgument(plan != null, "plan can not be null");
        Preconditions.checkArgument(plan instanceof LogicalPlan, "only logical plan can be rewrite");

        // case 1: fast check the plan whether exist in the memo
        if (plan instanceof GroupPlan || plan.getGroupExpression().isPresent()) {
            return rewriteByExistedPlan(targetGroup, plan);
        }

        // try to create a new group expression
        List<Group> childrenGroups = rewriteChildrenPlansToGroups(plan, targetGroup);
        GroupExpression newGroupExpression = new GroupExpression(plan, childrenGroups);

        // slow check the groupExpression/plan whether exists in the memo
        GroupExpression existedExpression = groupExpressions.get(newGroupExpression);
        if (existedExpression == null) {
            return rewriteByNewGroupExpression(targetGroup, plan, newGroupExpression);
        } else {
            return rewriteByExistedGroupExpression(targetGroup, plan, existedExpression, newGroupExpression);
        }
    }

    /**
     * add the plan into the target group
     * @param plan the plan which want added
     * @param targetGroup target group to add plan. null to generate new Group. It should be the ancestors
     *                    of the plan's group, or equals to the plan's group, we do not check this constraint
     *                    completely because of performance.
     * @return a pair, in which the first element is true if a newly generated groupExpression added into memo,
     *         and the second element is a reference of node in Memo
     */
    private CopyInResult doCopyIn(Plan plan, @Nullable Group targetGroup) {
        Optional<GroupExpression> groupExpr = plan.getGroupExpression();
        if (groupExpr.isPresent() && groupExpressions.containsKey(groupExpr.get())) {
            return CopyInResult.of(false, groupExpr.get());
        }
        List<Group> childrenGroups = Lists.newArrayList();
        for (int i = 0; i < plan.children().size(); i++) {
            Plan child = plan.children().get(i);
            if (child instanceof GroupPlan) {
                childrenGroups.add(((GroupPlan) child).getGroup());
            } else if (child.getGroupExpression().isPresent()) {
                childrenGroups.add(child.getGroupExpression().get().getOwnerGroup());
            } else {
                childrenGroups.add(copyIn(child, null, false).correspondingExpression.getOwnerGroup());
            }
        }
        plan = replaceChildrenToGroupPlan(plan, childrenGroups);
        GroupExpression newGroupExpression = new GroupExpression(plan);
        newGroupExpression.setChildren(childrenGroups);
        return insertGroupExpression(newGroupExpression, targetGroup, plan.getLogicalProperties());
        // TODO: need to derive logical property if generate new group. currently we not copy logical plan into
    }

    private List<Group> rewriteChildrenPlansToGroups(Plan plan, Group targetGroup) {
        List<Group> childrenGroups = Lists.newArrayList();
        for (int i = 0; i < plan.children().size(); i++) {
            Plan child = plan.children().get(i);
            if (child instanceof GroupPlan) {
                GroupPlan childGroupPlan = (GroupPlan) child;
                validateRewriteChildGroup(childGroupPlan.getGroup(), targetGroup);
                childrenGroups.add(childGroupPlan.getGroup());
            } else if (child.getGroupExpression().isPresent()) {
                Group childGroup = child.getGroupExpression().get().getOwnerGroup();
                validateRewriteChildGroup(childGroup, targetGroup);
                childrenGroups.add(childGroup);
            } else {
                childrenGroups.add(doRewrite(child, null).correspondingExpression.getOwnerGroup());
            }
        }
        return childrenGroups;
    }

    private void validateRewriteChildGroup(Group childGroup, Group targetGroup) {
        /*
         * 'A => B(A)' is invalid equivalent transform because of dead loop.
         * see 'MemoRewriteTest.a2ba()'
         */
        if (childGroup == targetGroup) {
            throw new IllegalStateException("Can not add plan which is ancestor of the target plan");
        }
    }

    /**
     * Insert groupExpression to target group.
     * If group expression is already in memo and target group is not null, we merge two groups.
     * If target is null, generate new group.
     * If target is not null, add group expression to target group
     *
     * @param groupExpression groupExpression to insert
     * @param target target group to insert groupExpression
     * @return a pair, in which the first element is true if a newly generated groupExpression added into memo,
     *         and the second element is a reference of node in Memo
     */
    private CopyInResult insertGroupExpression(
            GroupExpression groupExpression, Group target, LogicalProperties logicalProperties) {
        GroupExpression existedGroupExpression = groupExpressions.get(groupExpression);
        if (existedGroupExpression != null) {
            if (target != null && !target.getGroupId().equals(existedGroupExpression.getOwnerGroup().getGroupId())) {
                mergeGroup(existedGroupExpression.getOwnerGroup(), target);
            }
            return CopyInResult.of(false, existedGroupExpression);
        }
        if (target != null) {
            target.addGroupExpression(groupExpression);
        } else {
            Group group = new Group(groupIdGenerator.getNextId(), groupExpression, logicalProperties);
            groups.put(group.getGroupId(), group);
        }
        groupExpressions.put(groupExpression, groupExpression);
        return CopyInResult.of(true, groupExpression);
    }

    /**
     * Merge two groups.
     * 1. find all group expression which has source as child
     * 2. replace its child with destination
     * 3. remove redundant group expression after replace child
     * 4. move all group expression in source to destination
     *
     * @param source source group
     * @param destination destination group
     * @return merged group
     */
    private Group mergeGroup(Group source, Group destination) {
        if (source.equals(destination)) {
            return source;
        }
        List<GroupExpression> needReplaceChild = Lists.newArrayList();
        groupExpressions.values().forEach(groupExpression -> {
            if (groupExpression.children().contains(source)) {
                if (groupExpression.getOwnerGroup().equals(destination)) {
                    // cycle, we should not merge
                    return;
                }
                needReplaceChild.add(groupExpression);
            }
        });
        for (GroupExpression groupExpression : needReplaceChild) {
            groupExpressions.remove(groupExpression);
            List<Group> children = groupExpression.children();
            // TODO: use a better way to replace child, avoid traversing all groupExpression
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).equals(source)) {
                    children.set(i, destination);
                }
            }
            GroupExpression that = groupExpressions.get(groupExpression);
            if (that != null && that.getOwnerGroup() != null
                    && !that.getOwnerGroup().equals(groupExpression.getOwnerGroup())) {
                // remove groupExpression from its owner group to avoid adding it to that.getOwnerGroup()
                // that.getOwnerGroup() already has this groupExpression.
                Group ownerGroup = groupExpression.getOwnerGroup();
                groupExpression.getOwnerGroup().removeGroupExpression(groupExpression);
                mergeGroup(ownerGroup, that.getOwnerGroup());
            } else {
                groupExpressions.put(groupExpression, groupExpression);
            }
        }
        if (!source.equals(destination)) {
            source.moveLogicalExpressionOwnership(destination);
            source.movePhysicalExpressionOwnership(destination);
            groups.remove(source.getGroupId());
        }
        return destination;
    }

    /**
     * Add enforcer expression into the target group.
     */
    public void addEnforcerPlan(GroupExpression groupExpression, Group group) {
        groupExpression.setOwnerGroup(group);
    }

    private CopyInResult rewriteByExistedPlan(Group targetGroup, Plan existedPlan) {
        GroupExpression existedLogicalExpression = existedPlan instanceof GroupPlan
                ? ((GroupPlan) existedPlan).getGroup().getLogicalExpression() // get first logicalGroupExpression
                : existedPlan.getGroupExpression().get();
        if (targetGroup != null) {
            Group existedGroup = existedLogicalExpression.getOwnerGroup();
            // clear targetGroup, from exist group move all logical groupExpression
            // and logicalProperties to target group
            eliminateFromGroupAndMoveToTargetGroup(existedGroup, targetGroup, existedPlan.getLogicalProperties());
        }
        return CopyInResult.of(false, existedLogicalExpression);
    }

    private CopyInResult rewriteByNewGroupExpression(Group targetGroup, Plan newPlan,
            GroupExpression newGroupExpression) {
        if (targetGroup == null) {
            // case 2:
            // if not exist target group and not exist the same group expression,
            // then create new group with the newGroupExpression
            Group newGroup = new Group(groupIdGenerator.getNextId(), newGroupExpression,
                    newPlan.getLogicalProperties());
            groups.put(newGroup.getGroupId(), newGroup);
            groupExpressions.put(newGroupExpression, newGroupExpression);
        } else {
            // case 3:
            // if exist the target group, clear all origin group expressions in the
            // existedExpression's owner group and reset logical properties, the
            // newGroupExpression is the init logical group expression.
            reInitGroup(targetGroup, newGroupExpression, newPlan.getLogicalProperties());

            // note: put newGroupExpression must behind recycle existedExpression(reInitGroup method),
            //       because existedExpression maybe equal to the newGroupExpression and recycle
            //       existedExpression will recycle newGroupExpression
            groupExpressions.put(newGroupExpression, newGroupExpression);
        }
        return CopyInResult.of(true, newGroupExpression);
    }

    private CopyInResult rewriteByExistedGroupExpression(Group targetGroup, Plan transformedPlan,
            GroupExpression existedExpression, GroupExpression newExpression) {
        if (targetGroup != null && !targetGroup.equals(existedExpression.getOwnerGroup())) {
            // case 4:
            existedExpression.propagateApplied(newExpression);
            moveParentExpressionsReference(existedExpression.getOwnerGroup(), targetGroup);
            recycleGroup(existedExpression.getOwnerGroup());
            reInitGroup(targetGroup, newExpression, transformedPlan.getLogicalProperties());

            // note: put newGroupExpression must behind recycle existedExpression(reInitGroup method),
            //       because existedExpression maybe equal to the newGroupExpression and recycle
            //       existedExpression will recycle newGroupExpression
            groupExpressions.put(newExpression, newExpression);
            return CopyInResult.of(true, newExpression);
        } else {
            // case 5:
            // if targetGroup is null or targetGroup equal to the existedExpression's ownerGroup,
            // then recycle the temporary new group expression
            recycleExpression(newExpression);
            return CopyInResult.of(false, existedExpression);
        }
    }

    /**
     * eliminate fromGroup, clear targetGroup, then move the logical group expressions in the fromGroup to the toGroup.
     *
     * the scenario is:
     * ```
     *  Group 1(project, the targetGroup)                  Group 1(logicalOlapScan, the targetGroup)
     *               |                             =>
     *  Group 0(logicalOlapScan, the fromGroup)
     * ```
     *
     * we should recycle the group 0, and recycle all group expressions in group 1, then move the logicalOlapScan to
     * the group 1, and reset logical properties of the group 1.
     */
    private void eliminateFromGroupAndMoveToTargetGroup(Group fromGroup, Group targetGroup,
            LogicalProperties logicalProperties) {
        if (fromGroup == targetGroup) {
            return;
        }
        // simple check targetGroup is the ancestors of the fromGroup, not check completely because of performance
        if (fromGroup == root) {
            throw new IllegalStateException(
                    "TargetGroup should be ancestors of fromGroup, but fromGroup is root. Maybe a bug");
        }

        List<GroupExpression> logicalExpressions = fromGroup.clearLogicalExpressions();
        recycleGroup(fromGroup);

        recycleLogicalExpressions(targetGroup);
        recyclePhysicalExpressions(targetGroup);

        for (GroupExpression logicalExpression : logicalExpressions) {
            targetGroup.addLogicalExpression(logicalExpression);
        }
        targetGroup.setLogicalProperties(logicalProperties);
    }

    private void reInitGroup(Group group, GroupExpression initLogicalExpression, LogicalProperties logicalProperties) {
        recycleLogicalExpressions(group);
        recyclePhysicalExpressions(group);

        group.setLogicalProperties(logicalProperties);
        group.addLogicalExpression(initLogicalExpression);
    }

    private Plan replaceChildrenToGroupPlan(Plan plan, List<Group> childrenGroups) {
        if (childrenGroups.isEmpty()) {
            return plan;
        }
        List<Plan> groupPlanChildren = childrenGroups.stream()
                .map(GroupPlan::new)
                .collect(ImmutableList.toImmutableList());
        LogicalProperties logicalProperties = plan.getLogicalProperties();
        return plan.withChildren(groupPlanChildren)
            .withLogicalProperties(Optional.of(logicalProperties));
    }


    /*
     * the scenarios that 'parentGroupExpression == toGroup': eliminate the root group.
     * the fromGroup is group 1, the toGroup is group 2, we can not replace group2's
     * groupExpressions reference the child group which is group 2 (reference itself).
     *
     *   A(group 2)            B(group 2)
     *   |                     |
     *   B(group 1)      =>    C(group 0)
     *   |
     *   C(group 0)
     *
     *
     * note: the method don't save group and groupExpression to the memo, so you need
     *       save group and groupExpression to the memo at other place.
     */
    private void moveParentExpressionsReference(Group fromGroup, Group toGroup) {
        for (GroupExpression parentGroupExpression : fromGroup.getParentGroupExpressions()) {
            if (parentGroupExpression.getOwnerGroup() != toGroup) {
                parentGroupExpression.replaceChild(fromGroup, toGroup);
            }
        }
    }

    private void recycleGroup(Group group) {
        if (groups.get(group.getGroupId()) == group) {
            groups.remove(group.getGroupId());
        }
        recycleLogicalExpressions(group);
        recyclePhysicalExpressions(group);
    }

    private void recycleLogicalExpressions(Group group) {
        if (!group.getLogicalExpressions().isEmpty()) {
            for (GroupExpression logicalExpression : group.getLogicalExpressions()) {
                recycleExpression(logicalExpression);
            }
            group.clearLogicalExpressions();
        }
    }

    private void recyclePhysicalExpressions(Group group) {
        if (!group.getPhysicalExpressions().isEmpty()) {
            for (GroupExpression physicalExpression : group.getPhysicalExpressions()) {
                recycleExpression(physicalExpression);
            }
            group.clearPhysicalExpressions();
        }
    }

    private void recycleExpression(GroupExpression groupExpression) {
        if (groupExpressions.get(groupExpression) == groupExpression) {
            groupExpressions.remove(groupExpression);
        }
        for (Group childGroup : groupExpression.children()) {
            // if not any groupExpression reference child group, then recycle the child group
            if (childGroup.removeParentExpression(groupExpression) == 0) {
                recycleGroup(childGroup);
            }
        }
    }
}
