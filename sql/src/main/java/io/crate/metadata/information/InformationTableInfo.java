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

package io.crate.metadata.information;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.crate.analyze.WhereClause;
import io.crate.metadata.*;
import io.crate.metadata.table.AbstractTableInfo;
import io.crate.planner.RowGranularity;
import io.crate.types.DataType;

import javax.annotation.Nullable;
import java.util.*;

public class InformationTableInfo extends AbstractTableInfo {

    public static class Builder {

        private final ImmutableList.Builder<ReferenceInfo> columns = ImmutableList.builder();
        private final ImmutableMap.Builder<ColumnIdent, ReferenceInfo> references = ImmutableMap.builder();
        private final ImmutableList.Builder<ColumnIdent> primaryKey = ImmutableList.builder();

        private final TableIdent ident;

        public Builder(String name) {
            this.ident = new TableIdent(InformationSchemaInfo.NAME, name);
        }

        public Builder add(String column, DataType type, List<String> path) {
            return add(column, type, path, ReferenceInfo.ObjectType.STRICT);
        }

        public Builder add(String column, DataType type, List<String> path, ReferenceInfo.ObjectType objectType) {
            ReferenceInfo info = new ReferenceInfo(new ReferenceIdent(ident, column, path),
                    RowGranularity.DOC, type, objectType, ReferenceInfo.IndexType.NOT_ANALYZED);
            if (info.ident().isColumn()) {
                columns.add(info);
            }
            references.put(info.ident().columnIdent(), info);
            return this;
        }

        public Builder addPrimaryKey(String column) {
            primaryKey.add(ColumnIdent.fromPath(column));
            return this;
        }

        public InformationTableInfo build() {
            return new InformationTableInfo(ident, columns.build(), references.build(), primaryKey.build());
        }


    }

    private final Routing routing;
    private final TableIdent ident;
    private final ImmutableList<ColumnIdent> primaryKey;

    private final ImmutableMap<ColumnIdent, ReferenceInfo> references;
    private final ImmutableList<ReferenceInfo> columns;


    private InformationTableInfo(TableIdent ident,
                                 ImmutableList<ReferenceInfo> columns,
                                 ImmutableMap<ColumnIdent, ReferenceInfo> references,
                                 ImmutableList<ColumnIdent> primaryKey) {
        this.ident = ident;
        this.columns = columns;
        this.references = references;
        this.primaryKey = primaryKey;
        Map<String, Map<String, Set<Integer>>> locations = new HashMap<>(1);
        Map<String, Set<Integer>> tableLocation = new HashMap<>(1);
        tableLocation.put(ident.fqn(), null);
        locations.put(null, tableLocation);
        this.routing = new Routing(locations);
    }

    @Nullable
    @Override
    public ReferenceInfo getColumnInfo(ColumnIdent columnIdent) {
        return references.get(columnIdent);
    }

    @Override
    public Collection<ReferenceInfo> columns() {
        return columns;
    }

    @Override
    public RowGranularity rowGranularity() {
        return RowGranularity.DOC;
    }

    @Override
    public Routing getRouting(WhereClause whereClause) {
        return routing;
    }

    @Override
    public TableIdent ident() {
        return ident;
    }

    @Override
    public List<ColumnIdent> primaryKey() {
        return primaryKey;
    }

    @Override
    public String[] concreteIndices() {
        return new String[]{ident.name()};
    }

    @Override
    public Iterator<ReferenceInfo> iterator() {
        return references.values().iterator();
    }
}
