/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.types;

import net.java.quickcheck.generator.iterable.Iterables;
import org.junit.Test;

import java.util.Arrays;

import static net.java.quickcheck.generator.PrimitiveGenerators.*;
import static org.junit.Assert.*;

public class TypeConversionTest {

    @Test
    public void numberConversionTest() throws Exception {
        for (Byte byteVal : Iterables.toIterable(bytes())) {
            for (DataType t : DataTypes.ALLOWED_CONVERSIONS.get(DataTypes.BYTE.id())) {
                t.value(byteVal);
            }
        }

        for (Integer shortVal : Iterables.toIterable(integers(
                (int) Byte.MIN_VALUE, (int) Byte.MAX_VALUE))) {
            for (DataType t : DataTypes.ALLOWED_CONVERSIONS.get(DataTypes.SHORT.id())) {
                t.value(shortVal.shortValue());
            }
        }

        for (Integer intValue : Iterables.toIterable(integers((int) Byte.MIN_VALUE, (int) Byte.MAX_VALUE))) {
            for (DataType t : DataTypes.ALLOWED_CONVERSIONS.get(DataTypes.INTEGER.id())) {
                t.value(intValue);
            }
        }

        for (Long longValue : Iterables.toIterable(longs((long) Byte.MIN_VALUE, (long) Byte.MAX_VALUE))) {
            for (DataType t : DataTypes.ALLOWED_CONVERSIONS.get(DataTypes.LONG.id())) {
                t.value(longValue);
            }
        }

        for (Double floatVal : Iterables.toIterable(doubles((double)Byte.MIN_VALUE, (double)Byte.MAX_VALUE))) {
            for (DataType t : DataTypes.ALLOWED_CONVERSIONS.get(DataTypes.FLOAT.id())) {
                t.value(floatVal.floatValue());
            }
        }

        for (Double doubleVal : Iterables.toIterable(doubles((double) Byte.MIN_VALUE, (double) Byte.MAX_VALUE))) {
            for (DataType t : DataTypes.ALLOWED_CONVERSIONS.get(DataTypes.DOUBLE.id())) {
                t.value(doubleVal);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testByteOutOfRangeNegative() throws Exception {
        DataTypes.BYTE.value(-129);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testByteOutOfRangePositive() throws Exception {
        DataTypes.BYTE.value(129);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testShortOutOfRangePositive() throws Exception {
        DataTypes.SHORT.value(Integer.MAX_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testShortOutOfRangeNegative() throws Exception {
        DataTypes.SHORT.value(Integer.MIN_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIntOutOfRangePositive() throws Exception {
        DataTypes.INTEGER.value(Long.MAX_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIntOutOfRangeNegative() throws Exception {
        DataTypes.INTEGER.value(Long.MIN_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFloatOutOfRangePositive() throws Exception {
        DataTypes.FLOAT.value(Double.MAX_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFloatOutOfRangenegative() throws Exception {
        DataTypes.FLOAT.value(-Double.MAX_VALUE);
    }

    @Test
    public void selfConversionTest() throws Exception {
        for (DataType type : com.google.common.collect.Iterables.concat(
                DataTypes.PRIMITIVE_TYPES,
                Arrays.asList(DataTypes.NULL, DataTypes.GEO_POINT, DataTypes.OBJECT))) {

            assertTrue(type.isConvertableTo(type));

            ArrayType arrayType = new ArrayType(type);
            assertTrue(arrayType.isConvertableTo(arrayType));

            SetType setType = new SetType(type);
            assertTrue(setType.isConvertableTo(setType));
        }
    }

    @Test
    public void testNotSupportedConversion() throws Exception {
        for (DataType type : com.google.common.collect.Iterables.concat(
                DataTypes.PRIMITIVE_TYPES,
                Arrays.asList(DataTypes.GEO_POINT, DataTypes.OBJECT))) {
            assertFalse(DataTypes.NOT_SUPPORTED.isConvertableTo(type));
        }
    }

    @Test
    public void testToNullConversions() throws Exception {
        for (DataType type : com.google.common.collect.Iterables.concat(
                DataTypes.PRIMITIVE_TYPES,
                Arrays.asList(DataTypes.NULL, DataTypes.GEO_POINT, DataTypes.OBJECT))) {

            assertTrue(type.isConvertableTo(DataTypes.NULL));
            assertTrue(DataTypes.NULL.isConvertableTo(type));
        }
    }


}
