/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.sql.tree;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;

public class ComparisonExpression
        extends Expression
{
    public enum Type
    {
        EQUAL("="),
        NOT_EQUAL("<>"),
        LESS_THAN("<"),
        LESS_THAN_OR_EQUAL("<="),
        GREATER_THAN(">"),
        GREATER_THAN_OR_EQUAL(">="),
        IS_DISTINCT_FROM("IS DISTINCT FROM");

        public static ImmutableMap<Type, Type> INVERSE_MAP = ImmutableMap.<Type, Type>builder()
                .put(EQUAL, NOT_EQUAL)
                .put(NOT_EQUAL, EQUAL)
                .put(LESS_THAN, GREATER_THAN_OR_EQUAL)
                .put(LESS_THAN_OR_EQUAL, GREATER_THAN)
                .put(GREATER_THAN, LESS_THAN_OR_EQUAL)
                .put(GREATER_THAN_OR_EQUAL, LESS_THAN)
                .build();

        private final String value;

        Type(String value)
        {
            this.value = value;
        }

        public String getValue()
        {
            return value;
        }

        public Type inverse() {
            return INVERSE_MAP.get(this);
        }
    }

    private final Type type;
    private final Expression left;
    private final Expression right;

    public ComparisonExpression(Type type, Expression left, Expression right)
    {
        Preconditions.checkNotNull(type, "type is null");
        Preconditions.checkNotNull(left, "left is null");
        Preconditions.checkNotNull(right, "right is null");

        this.type = type;
        this.left = left;
        this.right = right;
    }

    public Type getType()
    {
        return type;
    }

    public Expression getLeft()
    {
        return left;
    }

    public Expression getRight()
    {
        return right;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context)
    {
        return visitor.visitComparisonExpression(this, context);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ComparisonExpression that = (ComparisonExpression) o;

        if (!left.equals(that.left)) {
            return false;
        }
        if (!right.equals(that.right)) {
            return false;
        }
        if (type != that.type) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        int result = type.hashCode();
        result = 31 * result + left.hashCode();
        result = 31 * result + right.hashCode();
        return result;
    }

    public static Predicate<ComparisonExpression> matchesPattern(final Type type, final Class<?> left, final Class<?> right)
    {
        return new Predicate<ComparisonExpression>()
        {
            @Override
            public boolean apply(ComparisonExpression expression)
            {
                return expression.getType() == type &&
                        left.isAssignableFrom(expression.getLeft().getClass()) &&
                        right.isAssignableFrom(expression.getRight().getClass());
            }
        };
    }
}

