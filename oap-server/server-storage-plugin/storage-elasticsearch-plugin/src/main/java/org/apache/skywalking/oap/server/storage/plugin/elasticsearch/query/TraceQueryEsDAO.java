/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.elasticsearch.query;

import com.google.common.base.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.skywalking.library.elasticsearch.requests.search.BoolQueryBuilder;
import org.apache.skywalking.library.elasticsearch.requests.search.Query;
import org.apache.skywalking.library.elasticsearch.requests.search.RangeQueryBuilder;
import org.apache.skywalking.library.elasticsearch.requests.search.Search;
import org.apache.skywalking.library.elasticsearch.requests.search.SearchBuilder;
import org.apache.skywalking.library.elasticsearch.requests.search.Sort;
import org.apache.skywalking.library.elasticsearch.requests.search.aggregation.Aggregation;
import org.apache.skywalking.library.elasticsearch.response.search.SearchHit;
import org.apache.skywalking.library.elasticsearch.response.search.SearchResponse;
import org.apache.skywalking.oap.server.core.analysis.IDManager;
import org.apache.skywalking.oap.server.core.analysis.manual.searchtag.Tag;
import org.apache.skywalking.oap.server.core.analysis.manual.segment.SegmentRecord;
import org.apache.skywalking.oap.server.core.analysis.manual.segment.TraceTagAutocompleteData;
import org.apache.skywalking.oap.server.core.query.type.BasicTrace;
import org.apache.skywalking.oap.server.core.query.type.QueryOrder;
import org.apache.skywalking.oap.server.core.query.type.Span;
import org.apache.skywalking.oap.server.core.query.type.TraceBrief;
import org.apache.skywalking.oap.server.core.query.type.TraceState;
import org.apache.skywalking.oap.server.core.storage.query.ITraceQueryDAO;
import org.apache.skywalking.oap.server.core.storage.type.HashMapConverter;
import org.apache.skywalking.oap.server.library.client.elasticsearch.ElasticSearchClient;
import org.apache.skywalking.oap.server.library.util.BooleanUtils;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.library.util.StringUtil;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.EsDAO;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.IndexController;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.TimeRangeIndexNameGenerator;

public class TraceQueryEsDAO extends EsDAO implements ITraceQueryDAO {

    private final int segmentQueryMaxSize;

    public TraceQueryEsDAO(ElasticSearchClient client, int segmentQueryMaxSize) {
        super(client);
        this.segmentQueryMaxSize = segmentQueryMaxSize;
    }

    @Override
    public TraceBrief queryBasicTraces(long startSecondTB,
                                       long endSecondTB,
                                       long minDuration,
                                       long maxDuration,
                                       String serviceId,
                                       String serviceInstanceId,
                                       String endpointId,
                                       String traceId,
                                       int limit,
                                       int from,
                                       TraceState traceState,
                                       QueryOrder queryOrder,
                                       final List<Tag> tags) throws IOException {
        final BoolQueryBuilder query = Query.bool();

        if (startSecondTB != 0 && endSecondTB != 0) {
            query.must(Query.range(SegmentRecord.TIME_BUCKET).gte(startSecondTB).lte(endSecondTB));
        }

        if (minDuration != 0 || maxDuration != 0) {
            RangeQueryBuilder rangeQueryBuilder = Query.range(SegmentRecord.LATENCY);
            if (minDuration != 0) {
                rangeQueryBuilder.gte(minDuration);
            }
            if (maxDuration != 0) {
                rangeQueryBuilder.lte(maxDuration);
            }
            query.must(rangeQueryBuilder);
        }
        if (StringUtil.isNotEmpty(serviceId)) {
            query.must(Query.term(SegmentRecord.SERVICE_ID, serviceId));
        }
        if (StringUtil.isNotEmpty(serviceInstanceId)) {
            query.must(Query.term(SegmentRecord.SERVICE_INSTANCE_ID, serviceInstanceId));
        }
        if (!Strings.isNullOrEmpty(endpointId)) {
            query.must(Query.term(SegmentRecord.ENDPOINT_ID, endpointId));
        }
        if (!Strings.isNullOrEmpty(traceId)) {
            query.must(Query.term(SegmentRecord.TRACE_ID, traceId));
        }
        switch (traceState) {
            case ERROR:
                query.must(Query.match(SegmentRecord.IS_ERROR, BooleanUtils.TRUE));
                break;
            case SUCCESS:
                query.must(Query.match(SegmentRecord.IS_ERROR, BooleanUtils.FALSE));
                break;
        }

        final SearchBuilder search = Search.builder().query(query);

        switch (queryOrder) {
            case BY_START_TIME:
                search.sort(SegmentRecord.START_TIME, Sort.Order.DESC);
                break;
            case BY_DURATION:
                search.sort(SegmentRecord.LATENCY, Sort.Order.DESC);
                break;
        }
        if (CollectionUtils.isNotEmpty(tags)) {
            BoolQueryBuilder tagMatchQuery = Query.bool();
            tags.forEach(tag -> tagMatchQuery.must(Query.term(SegmentRecord.TAGS, tag.toString())));
            query.must(tagMatchQuery);
        }
        search.size(limit).from(from);

        final SearchResponse response = getClient().search(
            new TimeRangeIndexNameGenerator(
                IndexController.LogicIndicesRegister.getPhysicalTableName(SegmentRecord.INDEX_NAME),
                startSecondTB,
                endSecondTB
            ), search.build());
        final TraceBrief traceBrief = new TraceBrief();
        traceBrief.setTotal(response.getHits().getTotal());

        for (SearchHit searchHit : response.getHits().getHits()) {
            BasicTrace basicTrace = new BasicTrace();

            basicTrace.setSegmentId((String) searchHit.getSource().get(SegmentRecord.SEGMENT_ID));
            basicTrace.setStart(String.valueOf(searchHit.getSource().get(SegmentRecord.START_TIME)));
            basicTrace.getEndpointNames().add(
                IDManager.EndpointID.analysisId(
                    (String) searchHit.getSource().get(SegmentRecord.ENDPOINT_ID)
                ).getEndpointName());
            basicTrace.setDuration(((Number) searchHit.getSource().get(SegmentRecord.LATENCY)).intValue());
            basicTrace.setError(
                BooleanUtils.valueToBoolean(
                    ((Number) searchHit.getSource().get(SegmentRecord.IS_ERROR)).intValue()
                )
            );
            basicTrace.getTraceIds().add((String) searchHit.getSource().get(SegmentRecord.TRACE_ID));
            traceBrief.getTraces().add(basicTrace);
        }

        return traceBrief;
    }

    @Override
    public List<SegmentRecord> queryByTraceId(String traceId) throws IOException {
        final String index =
            IndexController.LogicIndicesRegister.getPhysicalTableName(SegmentRecord.INDEX_NAME);

        final SearchBuilder search =
            Search.builder()
                  .query(Query.term(SegmentRecord.TRACE_ID, traceId))
                  .size(segmentQueryMaxSize);

        final SearchResponse response = getClient().search(index, search.build());

        List<SegmentRecord> segmentRecords = new ArrayList<>();
        for (SearchHit searchHit : response.getHits().getHits()) {
            SegmentRecord segmentRecord = new SegmentRecord.Builder().storage2Entity(
                new HashMapConverter.ToEntity(searchHit.getSource()));
            segmentRecords.add(segmentRecord);
        }
        return segmentRecords;
    }

    @Override
    public List<Span> doFlexibleTraceQuery(String traceId) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public Set<String> queryTraceTagAutocompleteKeys(final long startSecondTB,
                                                     final long endSecondTB) throws IOException {
        BoolQueryBuilder query = Query.bool();
        appendTagAutocompleteCondition(startSecondTB, endSecondTB, query);
        final SearchBuilder search = Search.builder().query(query);
        search.aggregation(Aggregation.terms(TraceTagAutocompleteData.TAG_KEY)
                                      .field(TraceTagAutocompleteData.TAG_KEY));

        final SearchResponse response = getClient().search(
            new TimeRangeIndexNameGenerator(TraceTagAutocompleteData.INDEX_NAME, startSecondTB, endSecondTB),
            search.build()
        );
        Map<String, Object> terms =
            (Map<String, Object>) response.getAggregations().get(TraceTagAutocompleteData.TAG_KEY);
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) terms.get("buckets");
        Set<String> tagKeys = new HashSet<>();
        for (Map<String, Object> bucket : buckets) {
            String tagKey = (String) bucket.get("key");
            if (bucket.get("key") == null) {
                continue;
            }
            tagKeys.add(tagKey);
        }
        return tagKeys;
    }

    @Override
    public Set<String> queryTraceTagAutocompleteValues(final String tagKey, final int limit, final long startSecondTB,
                                                       final long endSecondTB) throws IOException {
        BoolQueryBuilder query = Query.bool().must(Query.term(TraceTagAutocompleteData.TAG_KEY, tagKey));
        appendTagAutocompleteCondition(startSecondTB, endSecondTB, query);
        final SearchBuilder search = Search.builder().query(query).size(limit);

        final SearchResponse response = getClient().search(
            new TimeRangeIndexNameGenerator(
                IndexController.LogicIndicesRegister.getPhysicalTableName(TraceTagAutocompleteData.INDEX_NAME),
                startSecondTB, endSecondTB
            ),
            search.build()
        );
        Set<String> tagValues = new HashSet<>();
        for (SearchHit searchHit : response.getHits().getHits()) {
            TraceTagAutocompleteData tag = new TraceTagAutocompleteData.Builder().storage2Entity(
                new HashMapConverter.ToEntity(searchHit.getSource()));
            tagValues.add(tag.getTagValue());
        }
        return tagValues;
    }

    private void appendTagAutocompleteCondition(final long startSecondTB, final long endSecondTB, final BoolQueryBuilder query) {
        long startMinTB = startSecondTB / 100;
        long endMinTB = endSecondTB / 100;
        final RangeQueryBuilder rangeQuery = Query.range(TraceTagAutocompleteData.TIME_BUCKET);
        if (startMinTB > 0) {
            rangeQuery.gte(startMinTB);
        }
        if (endMinTB > 0) {
            rangeQuery.lte(endMinTB);
        }
        if (startMinTB > 0 || endMinTB > 0) {
            query.must(rangeQuery);
        }
    }
}
