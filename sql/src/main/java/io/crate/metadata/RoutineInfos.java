/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
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
package io.crate.metadata;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterators;
import io.crate.operation.aggregation.AggregationFunction;
import io.crate.planner.symbol.SymbolFormatter;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import static io.crate.metadata.FulltextAnalyzerResolver.CustomType;

public class RoutineInfos implements Iterable<RoutineInfo> {

    private final ESLogger logger = Loggers.getLogger(getClass());
    private FulltextAnalyzerResolver ftResolver;
    private Functions functions;

    private static final String NO_DESC = "";

    private enum RoutineType {
        ANALYZER(CustomType.ANALYZER.getName().toUpperCase()),
        CHAR_FILTER(CustomType.CHAR_FILTER.getName().toUpperCase()),
        TOKEN_FILTER("TOKEN_FILTER"),
        TOKENIZER(CustomType.TOKENIZER.getName().toUpperCase()),
        FUNCTION("FUNCTION")
        ;
        private String name;
        private RoutineType(String name) {
            this.name = name;
        }
        public String getName() {
            return name;
        }
    }

    public RoutineInfos(Functions functions, FulltextAnalyzerResolver ftResolver) {
        this.functions = functions;
        this.ftResolver = ftResolver;
    }

    private Iterator<RoutineInfo> builtInAnalyzers() {
        return Iterators.transform(
                ftResolver.getBuiltInAnalyzers().iterator(),
                new Function<String, RoutineInfo>() {
            @Nullable
            @Override
            public RoutineInfo apply(@Nullable String input) {
                return new RoutineInfo(input,
                        RoutineType.ANALYZER.getName(), NO_DESC, true);
            }
        });
    }

    private Iterator<RoutineInfo> builtInCharFilters() {
        return Iterators.transform(
                ftResolver.getBuiltInCharFilters().iterator(),
                new Function<String, RoutineInfo>() {
            @Nullable
            @Override
            public RoutineInfo apply(@Nullable String input) {
                return new RoutineInfo(input,
                        RoutineType.CHAR_FILTER.getName(), NO_DESC, true);
            }
        });
    }

    private Iterator<RoutineInfo> builtInTokenFilters() {
        return Iterators.transform(
                ftResolver.getBuiltInTokenFilters().iterator(),
                new Function<String, RoutineInfo>() {
            @Nullable
            @Override
            public RoutineInfo apply(@Nullable String input) {
                return new RoutineInfo(input,
                        RoutineType.TOKEN_FILTER.getName(), NO_DESC, true);
            }
        });
    }

    private Iterator<RoutineInfo> builtInTokenizers() {
        return Iterators.transform(
                ftResolver.getBuiltInTokenizers().iterator(),
                new Function<String, RoutineInfo>() {
            @Nullable
            @Override
            public RoutineInfo apply(@Nullable String input) {
                return new RoutineInfo(input,
                        RoutineType.TOKENIZER.getName(), NO_DESC, true);
            }
        });
    }

    private Iterator<RoutineInfo> customIterators() {
        try {
            Iterator<RoutineInfo> cAnalyzersIterator;
            Iterator<RoutineInfo> cCharFiltersIterator;
            Iterator<RoutineInfo> cTokenFiltersIterator;
            Iterator<RoutineInfo> cTokenizersIterator;
            cAnalyzersIterator = Iterators.transform(
                    ftResolver.getCustomAnalyzers().entrySet().iterator(),
                    new Function<Map.Entry<String, Settings>, RoutineInfo>() {
                        @Nullable
                        @Override
                        public RoutineInfo apply(@Nullable Map.Entry<String, Settings> input) {
                            String definition = ftResolver.getCustomAnalyzerSource(input.getKey());
                            if (definition == null) {
                                definition = NO_DESC;
                            }
                            return new RoutineInfo(input.getKey(),
                                    RoutineType.ANALYZER.getName(),
                                    definition,
                                    false);
                        }
                    });
            cCharFiltersIterator = Iterators.transform(
                    ftResolver.getCustomCharFilters().entrySet().iterator(),
                    new Function<Map.Entry<String, Settings>, RoutineInfo>() {
                        @Nullable
                        @Override
                        public RoutineInfo apply(@Nullable Map.Entry<String, Settings> input) {
                            return new RoutineInfo(input.getKey(),
                                    RoutineType.CHAR_FILTER.getName(),
                                    NO_DESC,
                                    false);
                        }
                    });
            cTokenFiltersIterator = Iterators.transform(
                    ftResolver.getCustomTokenFilters().entrySet().iterator(),
                    new Function<Map.Entry<String, Settings>, RoutineInfo>() {
                        @Nullable
                        @Override
                        public RoutineInfo apply(@Nullable Map.Entry<String, Settings> input) {
                            return new RoutineInfo(input.getKey(),
                                    RoutineType.TOKEN_FILTER.getName(),
                                    NO_DESC,
                                    false);
                        }
                    });
            cTokenizersIterator = Iterators.transform(
                    ftResolver.getCustomTokenizers().entrySet().iterator(),
                    new Function<Map.Entry<String, Settings>, RoutineInfo>() {
                        @Nullable
                        @Override
                        public RoutineInfo apply(@Nullable Map.Entry<String, Settings> input) {
                            return new RoutineInfo(input.getKey(),
                                    RoutineType.TOKENIZER.getName(),
                                    NO_DESC,
                                    false);
                        }
                    });
            return Iterators.concat(cAnalyzersIterator, cCharFiltersIterator,
                    cTokenFiltersIterator, cTokenizersIterator);
        } catch (IOException e) {
            logger.error("Could not retrieve custom routines", e);
            return null;
        }
    }

    private Iterator<RoutineInfo> functions() {
        return FluentIterable.from(functions.functions()).transform(new Function<FunctionImplementation, RoutineInfo>() {
            @Nullable
            @Override
            public RoutineInfo apply(@Nullable FunctionImplementation input) {
                if (input instanceof Scalar || input instanceof AggregationFunction) {
                    return new RoutineInfo(input.info().ident().name(), RoutineType.FUNCTION.getName(),
                            SymbolFormatter.FunctionFormatter.format(input.info()), true);
                }
                return null;
            }
        }).filter(new Predicate<RoutineInfo>() {
            @Override
            public boolean apply(@Nullable RoutineInfo input) {
                return input != null;
            }
        }).iterator();
    }

    @Override
    public Iterator<RoutineInfo> iterator() {
        return Iterators.concat(
                builtInAnalyzers(),
                builtInCharFilters(),
                builtInTokenFilters(),
                builtInTokenizers(),
                customIterators(),
                functions()
        );
    }
}
