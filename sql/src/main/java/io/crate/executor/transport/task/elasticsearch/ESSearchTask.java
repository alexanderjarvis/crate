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

package io.crate.executor.transport.task.elasticsearch;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.crate.exceptions.FailedShardsException;
import io.crate.executor.QueryResult;
import io.crate.executor.Task;
import io.crate.executor.TaskResult;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.doc.DocSysColumns;
import io.crate.planner.node.dql.ESSearchNode;
import io.crate.planner.symbol.Reference;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.*;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class ESSearchTask implements Task<QueryResult> {

    private final ESLogger logger = Loggers.getLogger(this.getClass());

    private final ESSearchNode searchNode;
    private final TransportSearchAction transportSearchAction;
    private final SettableFuture<QueryResult> result;
    private final List<ListenableFuture<QueryResult>> results;
    private final ESQueryBuilder queryBuilder;

    public ESSearchTask(ESSearchNode searchNode,
                        TransportSearchAction transportSearchAction) {
        this.searchNode = searchNode;
        this.transportSearchAction = transportSearchAction;
        this.queryBuilder = new ESQueryBuilder();

        result = SettableFuture.create();
        results = Arrays.<ListenableFuture<QueryResult>>asList(result);
    }

    @Override
    public void start() {
        final SearchRequest request = new SearchRequest();

        final ESFieldExtractor[] extractor = buildExtractor(searchNode.outputs());
        final int numColumns = searchNode.outputs().size();

        try {
            request.source(queryBuilder.convert(searchNode), false);
            request.indices(searchNode.indices());
            request.routing(searchNode.whereClause().clusteredBy().orNull());

            if (logger.isDebugEnabled()) {
                logger.debug(request.source().toUtf8());
            }

            transportSearchAction.execute(request, new SearchResponseListener(result, extractor, numColumns));
        } catch (IOException e) {
            result.setException(e);
        }
    }

    private ESFieldExtractor[] buildExtractor(final List<? extends Reference> outputs) {
        ESFieldExtractor[] extractors = new ESFieldExtractor[outputs.size()];
        int i = 0;
        for (final Reference reference : outputs) {
            final ColumnIdent columnIdent = reference.info().ident().columnIdent();
            if (DocSysColumns.VERSION.equals(columnIdent)) {
                extractors[i] = new ESFieldExtractor() {
                    @Override
                    public Object extract(SearchHit hit) {
                        return hit.getVersion();
                    }
                };
            } else if (DocSysColumns.ID.equals(columnIdent)) {
                extractors[i] = new ESFieldExtractor() {
                    @Override
                    public Object extract(SearchHit hit) {
                        return new BytesRef(hit.getId());
                    }
                };
            } else if (DocSysColumns.DOC.equals(columnIdent)) {
                extractors[i] = new ESFieldExtractor() {
                    @Override
                    public Object extract(SearchHit hit) {
                        return hit.getSource();
                    }
                };
            } else if (DocSysColumns.RAW.equals(columnIdent)) {
                extractors[i] = new ESFieldExtractor() {
                    @Override
                    public Object extract(SearchHit hit) {
                        return hit.getSourceRef().toBytesRef();
                    }
                };
            } else if (DocSysColumns.SCORE.equals(columnIdent)) {
                extractors[i] = new ESFieldExtractor() {
                    @Override
                    public Object extract(SearchHit hit) {
                        return hit.getScore();
                    }
                };
            } else if (searchNode.partitionBy().contains(reference.info())) {
                extractors[i] = new ESFieldExtractor.PartitionedByColumnExtractor(
                        reference, searchNode.partitionBy()
                );
            } else {
                extractors[i] = new ESFieldExtractor.Source(columnIdent);
            }
            i++;
        }
        return extractors;
    }

    @Override
    public List<ListenableFuture<QueryResult>> result() {
        return results;
    }

    @Override
    public void upstreamResult(List<ListenableFuture<TaskResult>> result) {
        throw new UnsupportedOperationException("Can't have upstreamResults");
    }

    static class SearchResponseListener implements ActionListener<SearchResponse> {

        private final SettableFuture<QueryResult> result;
        private final ESFieldExtractor[] extractor;
        private final int numColumns;

        private final ESLogger logger = Loggers.getLogger(getClass());

        public SearchResponseListener(SettableFuture<QueryResult> result,
                                      ESFieldExtractor[] extractor,
                                      int numColumns) {

            this.result = result;
            this.extractor = extractor;
            this.numColumns = numColumns;
        }

        @Override
        public void onResponse(SearchResponse searchResponse) {
            if (searchResponse.getFailedShards() > 0) {
                onFailure(new FailedShardsException(searchResponse.getShardFailures()));
            } else {
                final SearchHit[] hits = searchResponse.getHits().getHits();
                final Object[][] rows = new Object[hits.length][numColumns];

                for (int r = 0; r < hits.length; r++) {
                    rows[r] = new Object[numColumns];
                    for (int c = 0; c < numColumns; c++) {
                        rows[r][c] = extractor[c].extract(hits[r]);
                    }
                }

                result.set(new QueryResult(rows));
            }
        }

        @Override
        public void onFailure(Throwable e) {
            if (e instanceof SearchPhaseExecutionException) {
                logger.error("Error executing SELECT statement", e);
                ShardSearchFailure[] shardSearchFailures = ((SearchPhaseExecutionException) e).shardFailures();
                if (shardSearchFailures.length > 0) {
                    result.setException(Throwables.getRootCause(shardSearchFailures[0].failure()));
                    return;
                }
            }
            result.setException(e);
        }
    }
}
