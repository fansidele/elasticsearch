/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.fetch.subphase;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.FetchContext;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.FetchSubPhaseProcessor;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.Map;

public final class FetchSourcePhase implements FetchSubPhase {
    @Override
    public FetchSubPhaseProcessor getProcessor(FetchContext fetchContext) {
        FetchSourceContext fetchSourceContext = fetchContext.fetchSourceContext();
        if (fetchSourceContext == null || fetchSourceContext.fetchSource() == false) {
            return null;
        }
        String index = fetchContext.getIndexName();
        assert fetchSourceContext.fetchSource();

        return new FetchSubPhaseProcessor() {
            private int fastPath;

            @Override
            public void setNextReader(LeafReaderContext readerContext) {

            }

            @Override
            public void process(HitContext hitContext) {
                if (fetchContext.getSearchExecutionContext().isSourceEnabled() == false) {
                    if (containsFilters(fetchSourceContext)) {
                        throw new IllegalArgumentException(
                            "unable to fetch fields from _source field: _source is disabled in the mappings for index [" + index + "]"
                        );
                    }
                    return;
                }
                hitExecute(fetchSourceContext, hitContext);
            }

            private void hitExecute(FetchSourceContext fetchSourceContext, HitContext hitContext) {
                final boolean nestedHit = hitContext.hit().getNestedIdentity() != null;
                Source source = hitContext.source();

                // If this is a parent document and there are no source filters, then add the source as-is.
                if (nestedHit == false && containsFilters(fetchSourceContext) == false) {
                    hitContext.hit().sourceRef(source.internalSourceRef());
                    fastPath++;
                    return;
                }

                // Otherwise, filter the source and add it to the hit.
                Map<String, Object> value = source.filter(fetchSourceContext);
                if (nestedHit) {
                    value = getNestedSource(value, hitContext);
                }

                try {
                    final int initialCapacity = nestedHit ? 1024 : Math.min(1024, source.internalSourceRef().length());
                    hitContext.hit().sourceRef(objectToBytes(value, source.sourceContentType(), initialCapacity));
                } catch (IOException e) {
                    throw new ElasticsearchException("Error filtering source", e);
                }
            }

            @Override
            public Map<String, Object> getDebugInfo() {
                return Map.of("fast_path", fastPath);
            }
        };
    }

    private static boolean containsFilters(FetchSourceContext context) {
        return context.includes().length != 0 || context.excludes().length != 0;
    }

    public static BytesReference objectToBytes(Object value, XContentType xContentType, int initialCapacity) throws IOException {
        BytesStreamOutput streamOutput = new BytesStreamOutput(initialCapacity);
        XContentBuilder builder = new XContentBuilder(xContentType.xContent(), streamOutput);
        if (value != null) {
            builder.value(value);
        } else {
            // This happens if the source filtering could not find the specified in the _source.
            // Just doing `builder.value(null)` is valid, but the xcontent validation can't detect what format
            // it is. In certain cases, for example response serialization we fail if no xcontent type can't be
            // detected. So instead we just return an empty top level object. Also this is in inline with what was
            // being return in this situation in 5.x and earlier.
            builder.startObject();
            builder.endObject();
        }
        return BytesReference.bytes(builder);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getNestedSource(Map<String, Object> sourceAsMap, HitContext hitContext) {
        for (SearchHit.NestedIdentity o = hitContext.hit().getNestedIdentity(); o != null; o = o.getChild()) {
            sourceAsMap = (Map<String, Object>) sourceAsMap.get(o.getField().string());
            if (sourceAsMap == null) {
                return null;
            }
        }
        return sourceAsMap;
    }
}
