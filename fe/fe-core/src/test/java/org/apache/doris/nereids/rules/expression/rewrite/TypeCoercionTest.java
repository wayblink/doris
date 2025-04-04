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

package org.apache.doris.nereids.rules.expression.rewrite;

import org.apache.doris.nereids.parser.NereidsParser;
import org.apache.doris.nereids.rules.expression.rewrite.rules.TypeCoercion;
import org.apache.doris.nereids.trees.expressions.CaseWhen;
import org.apache.doris.nereids.trees.expressions.Cast;
import org.apache.doris.nereids.trees.expressions.Divide;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.InPredicate;
import org.apache.doris.nereids.trees.expressions.WhenClause;
import org.apache.doris.nereids.trees.expressions.functions.Avg;
import org.apache.doris.nereids.trees.expressions.functions.Substring;
import org.apache.doris.nereids.trees.expressions.functions.Sum;
import org.apache.doris.nereids.trees.expressions.functions.Year;
import org.apache.doris.nereids.trees.expressions.literal.BigIntLiteral;
import org.apache.doris.nereids.trees.expressions.literal.BooleanLiteral;
import org.apache.doris.nereids.trees.expressions.literal.DateLiteral;
import org.apache.doris.nereids.trees.expressions.literal.DoubleLiteral;
import org.apache.doris.nereids.trees.expressions.literal.IntegerLiteral;
import org.apache.doris.nereids.trees.expressions.literal.SmallIntLiteral;
import org.apache.doris.nereids.trees.expressions.literal.StringLiteral;
import org.apache.doris.nereids.types.BigIntType;
import org.apache.doris.nereids.types.DoubleType;
import org.apache.doris.nereids.types.IntegerType;
import org.apache.doris.nereids.types.StringType;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class TypeCoercionTest {

    private final NereidsParser parser = new NereidsParser();
    private final ExpressionRuleExecutor executor = new ExpressionRuleExecutor(ImmutableList.of(TypeCoercion.INSTANCE));

    @Test
    public void testSubStringImplicitCast() {
        Expression expression = new Substring(
                new StringLiteral("abc"),
                new StringLiteral("1"),
                new StringLiteral("3")
        );
        Expression expected = new Substring(
                new StringLiteral("abc"),
                new Cast(new StringLiteral("1"), IntegerType.INSTANCE),
                new Cast(new StringLiteral("3"), IntegerType.INSTANCE)
        );
        assertRewrite(expected, expression);
    }

    @Test
    public void testLikeImplicitCast() {
        String expression = "1 like 5";
        String expected = "cast(1 as string) like cast(5 as string)";
        assertRewrite(expected, expression);
    }

    @Test
    public void testRegexImplicitCast() {
        String expression = "1 regexp 5";
        String expected = "cast(1 as string) regexp cast(5 as string)";
        assertRewrite(expected, expression);
    }

    @Test
    public void testYearImplicitCast() {
        Expression expression = new Year(new DateLiteral("2022-01-01"));
        Expression expected = new Year(new DateLiteral("2022-01-01"));
        assertRewrite(expected, expression);
    }

    @Test
    public void testSumImplicitCast() {
        Expression expression = new Sum(new StringLiteral("1"));
        Expression expected = new Sum(new Cast(new StringLiteral("1"), DoubleType.INSTANCE));
        assertRewrite(expected, expression);
    }

    @Test
    public void testAvgImplicitCast() {
        Expression expression = new Avg(new StringLiteral("1"));
        Expression expected = new Avg(new Cast(new StringLiteral("1"), DoubleType.INSTANCE));
        assertRewrite(expected, expression);
    }

    @Test
    public void testCaseWhenTypeCoercion() {
        WhenClause actualWhenClause1 = new WhenClause(new BooleanLiteral(true), new SmallIntLiteral((short) 1));
        WhenClause actualWhenClause2 = new WhenClause(new BooleanLiteral(true), new DoubleLiteral(1.5));
        List<WhenClause> actualWhenClauses = Lists.newArrayList(actualWhenClause1, actualWhenClause2);
        Expression actualDefaultValue = new IntegerLiteral(1);
        Expression actual = new CaseWhen(actualWhenClauses, actualDefaultValue);

        WhenClause expectedWhenClause1 = new WhenClause(new BooleanLiteral(true),
                new Cast(new SmallIntLiteral((short) 1), DoubleType.INSTANCE));
        WhenClause expectedWhenClause2 = new WhenClause(new BooleanLiteral(true),
                new DoubleLiteral(1.5));
        List<WhenClause> expectedWhenClauses = Lists.newArrayList(expectedWhenClause1, expectedWhenClause2);
        Expression expectedDefaultValue = new Cast(new IntegerLiteral(1), DoubleType.INSTANCE);
        Expression expected = new CaseWhen(expectedWhenClauses, expectedDefaultValue);

        assertRewrite(expected, actual);
    }

    @Test
    public void testInPredicate() {
        Expression actualCompare = new DoubleLiteral(1.5);
        Expression actualOption1 = new StringLiteral("hello");
        Expression actualOption2 = new IntegerLiteral(1);
        List<Expression> actualOptions = Lists.newArrayList(actualOption1, actualOption2);
        Expression actual = new InPredicate(actualCompare, actualOptions);

        Expression expectedCompare = new Cast(new DoubleLiteral(1.5), StringType.INSTANCE);
        Expression expectedOption1 = new StringLiteral("hello");
        Expression expectedOption2 = new Cast(new IntegerLiteral(1), StringType.INSTANCE);
        List<Expression> expectedOptions = Lists.newArrayList(expectedOption1, expectedOption2);
        Expression expected = new InPredicate(expectedCompare, expectedOptions);

        assertRewrite(expected, actual);
    }

    @Test
    public void testBinaryOperator() {
        Expression actual = new Divide(new SmallIntLiteral((short) 1), new BigIntLiteral(10L));
        Expression expected = new Divide(new Cast(new SmallIntLiteral((short) 1), BigIntType.INSTANCE),
                new BigIntLiteral(10L));
        assertRewrite(expected, actual);
    }

    private void assertRewrite(Expression expected, Expression expression) {
        Expression rewrittenExpression = executor.rewrite(expression);
        Assertions.assertEquals(expected, rewrittenExpression);
    }

    private void assertRewrite(String expected, String expression) {
        Expression needRewriteExpression = parser.parseExpression(expression);
        Expression expectedExpression = parser.parseExpression(expected);
        assertRewrite(expectedExpression, needRewriteExpression);
    }
}
