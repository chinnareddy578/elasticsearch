/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import org.apache.lucene.queries.ExtendedCommonTermsQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.common.lucene.search.MultiPhrasePrefixQuery;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.search.MatchQuery;
import org.elasticsearch.index.search.MatchQuery.ZeroTermsQuery;

import java.io.IOException;
import java.util.Locale;

import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class MatchQueryBuilderTests extends AbstractQueryTestCase<MatchQueryBuilder> {
    @Override
    protected MatchQueryBuilder doCreateTestQueryBuilder() {
        String fieldName = randomFrom(STRING_FIELD_NAME, BOOLEAN_FIELD_NAME, INT_FIELD_NAME,
                DOUBLE_FIELD_NAME, DATE_FIELD_NAME);
        if (fieldName.equals(DATE_FIELD_NAME)) {
            assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        }
        Object value;
        if (fieldName.equals(STRING_FIELD_NAME)) {
            int terms = randomIntBetween(0, 3);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < terms; i++) {
                builder.append(randomAsciiOfLengthBetween(1, 10)).append(" ");
            }
            value = builder.toString().trim();
        } else {
            value = getRandomValueForFieldName(fieldName);
        }

        MatchQueryBuilder matchQuery = new MatchQueryBuilder(fieldName, value);
        matchQuery.type(randomFrom(MatchQuery.Type.values()));
        matchQuery.operator(randomFrom(Operator.values()));

        if (randomBoolean()) {
            matchQuery.analyzer(randomFrom("simple", "keyword", "whitespace"));
        }

        if (randomBoolean()) {
            matchQuery.slop(randomIntBetween(0, 10));
        }

        if (randomBoolean()) {
            matchQuery.fuzziness(randomFuzziness(fieldName));
        }

        if (randomBoolean()) {
            matchQuery.prefixLength(randomIntBetween(0, 10));
        }

        if (randomBoolean()) {
            matchQuery.minimumShouldMatch(randomMinimumShouldMatch());
        }

        if (randomBoolean()) {
            matchQuery.fuzzyRewrite(getRandomRewriteMethod());
        }

        if (randomBoolean()) {
            matchQuery.fuzzyTranspositions(randomBoolean());
        }

        if (randomBoolean()) {
            matchQuery.lenient(randomBoolean());
        }

        if (randomBoolean()) {
            matchQuery.zeroTermsQuery(randomFrom(MatchQuery.ZeroTermsQuery.values()));
        }

        if (randomBoolean()) {
            matchQuery.cutoffFrequency((float) 10 / randomIntBetween(1, 100));
        }
        return matchQuery;
    }

    @Override
    protected void doAssertLuceneQuery(MatchQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        assertThat(query, notNullValue());

        if (query instanceof MatchAllDocsQuery) {
            assertThat(queryBuilder.zeroTermsQuery(), equalTo(ZeroTermsQuery.ALL));
            return;
        }

        switch (queryBuilder.type()) {
        case BOOLEAN:
            assertThat(query, either(instanceOf(BooleanQuery.class)).or(instanceOf(ExtendedCommonTermsQuery.class))
                    .or(instanceOf(TermQuery.class)).or(instanceOf(FuzzyQuery.class)));
            break;
        case PHRASE:
            assertThat(query, either(instanceOf(BooleanQuery.class)).or(instanceOf(PhraseQuery.class))
                    .or(instanceOf(TermQuery.class)).or(instanceOf(FuzzyQuery.class)));
            break;
        case PHRASE_PREFIX:
            assertThat(query, either(instanceOf(BooleanQuery.class)).or(instanceOf(MultiPhrasePrefixQuery.class))
                    .or(instanceOf(TermQuery.class)).or(instanceOf(FuzzyQuery.class)));
            break;
        }

        MappedFieldType fieldType = context.fieldMapper(queryBuilder.fieldName());
        if (query instanceof TermQuery && fieldType != null) {
            String queryValue = queryBuilder.value().toString();
            if (queryBuilder.analyzer() == null || queryBuilder.analyzer().equals("simple")) {
                queryValue = queryValue.toLowerCase(Locale.ROOT);
            }
            Query expectedTermQuery = fieldType.termQuery(queryValue, context);
            assertEquals(expectedTermQuery, query);
        }

        if (query instanceof BooleanQuery) {
            BooleanQuery bq = (BooleanQuery) query;
            if (queryBuilder.minimumShouldMatch() != null) {
                // calculate expected minimumShouldMatch value
                int optionalClauses = 0;
                for (BooleanClause c : bq.clauses()) {
                    if (c.getOccur() == BooleanClause.Occur.SHOULD) {
                        optionalClauses++;
                    }
                }
                int msm = Queries.calculateMinShouldMatch(optionalClauses, queryBuilder.minimumShouldMatch());
                assertThat(bq.getMinimumNumberShouldMatch(), equalTo(msm));
            }
            if (queryBuilder.analyzer() == null && queryBuilder.value().toString().length() > 0) {
                assertEquals(bq.clauses().size(), queryBuilder.value().toString().split(" ").length);
            }
        }

        if (query instanceof ExtendedCommonTermsQuery) {
            assertTrue(queryBuilder.cutoffFrequency() != null);
            ExtendedCommonTermsQuery ectq = (ExtendedCommonTermsQuery) query;
            assertEquals(queryBuilder.cutoffFrequency(), ectq.getMaxTermFrequency(), Float.MIN_VALUE);
        }

        if (query instanceof FuzzyQuery) {
            assertTrue(queryBuilder.fuzziness() != null);
            FuzzyQuery fuzzyQuery = (FuzzyQuery) query;
            // depending on analyzer being set or not we can have term lowercased along the way, so to simplify test we just
            // compare lowercased terms here
            String originalTermLc = queryBuilder.value().toString().toLowerCase(Locale.ROOT);
            String actualTermLc = fuzzyQuery.getTerm().text().toLowerCase(Locale.ROOT);
            assertThat(actualTermLc, equalTo(originalTermLc));
            assertThat(queryBuilder.prefixLength(), equalTo(fuzzyQuery.getPrefixLength()));
            assertThat(queryBuilder.fuzzyTranspositions(), equalTo(fuzzyQuery.getTranspositions()));
        }
    }

    public void testIllegalValues() {
        try {
            new MatchQueryBuilder(null, "value");
            fail("value must not be non-null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            new MatchQueryBuilder("fieldName", null);
            fail("value must not be non-null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        MatchQueryBuilder matchQuery = new MatchQueryBuilder("fieldName", "text");
        try {
            matchQuery.prefixLength(-1);
            fail("must not be positive");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            matchQuery.maxExpansions(-1);
            fail("must not be positive");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            matchQuery.operator(null);
            fail("must not be non-null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            matchQuery.type(null);
            fail("must not be non-null");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        try {
            matchQuery.zeroTermsQuery(null);
            fail("must not be non-null");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    public void testBadAnalyzer() throws IOException {
        MatchQueryBuilder matchQuery = new MatchQueryBuilder("fieldName", "text");
        matchQuery.analyzer("bogusAnalyzer");
        try {
            matchQuery.toQuery(createShardContext());
            fail("Expected QueryShardException");
        } catch (QueryShardException e) {
            assertThat(e.getMessage(), containsString("analyzer [bogusAnalyzer] not found"));
        }
    }

    public void testPhrasePrefixMatchQuery() throws IOException {
        String json1 = "{\n" +
                "    \"match_phrase_prefix\" : {\n" +
                "        \"message\" : \"this is a test\"\n" +
                "    }\n" +
                "}";

        String expected = "{\n" +
                "  \"match\" : {\n" +
                "    \"message\" : {\n" +
                "      \"query\" : \"this is a test\",\n" +
                "      \"type\" : \"phrase_prefix\",\n" +
                "      \"operator\" : \"OR\",\n" +
                "      \"slop\" : 0,\n" +
                "      \"prefix_length\" : 0,\n" +
                "      \"max_expansions\" : 50,\n" +
                "      \"fuzzy_transpositions\" : true,\n" +
                "      \"lenient\" : false,\n" +
                "      \"zero_terms_query\" : \"NONE\",\n" +
                "      \"boost\" : 1.0\n" +
                "    }\n" +
                "  }\n" +
                "}";
        MatchQueryBuilder qb = (MatchQueryBuilder) parseQuery(json1);
        checkGeneratedJson(expected, qb);

        String json2 = "{\n" +
                "    \"match\" : {\n" +
                "        \"message\" : {\n" +
                "            \"query\" : \"this is a test\",\n" +
                "            \"type\" : \"phrase_prefix\"\n" +
                "        }\n" +
                "    }\n" +
                "}";
        qb = (MatchQueryBuilder) parseQuery(json2);
        checkGeneratedJson(expected, qb);

        String json3 = "{\n" +
                "    \"match_phrase_prefix\" : {\n" +
                "        \"message\" : {\n" +
                "            \"query\" : \"this is a test\",\n" +
                "            \"max_expansions\" : 10\n" +
                "        }\n" +
                "    }\n" +
                "}";
        expected = "{\n" +
                "  \"match\" : {\n" +
                "    \"message\" : {\n" +
                "      \"query\" : \"this is a test\",\n" +
                "      \"type\" : \"phrase_prefix\",\n" +
                "      \"operator\" : \"OR\",\n" +
                "      \"slop\" : 0,\n" +
                "      \"prefix_length\" : 0,\n" +
                "      \"max_expansions\" : 10,\n" +
                "      \"fuzzy_transpositions\" : true,\n" +
                "      \"lenient\" : false,\n" +
                "      \"zero_terms_query\" : \"NONE\",\n" +
                "      \"boost\" : 1.0\n" +
                "    }\n" +
                "  }\n" +
                "}";
        qb = (MatchQueryBuilder) parseQuery(json3);
        checkGeneratedJson(expected, qb);
    }

    public void testSimpleMatchQuery() throws IOException {
        String json = "{\n" +
                "  \"match\" : {\n" +
                "    \"message\" : {\n" +
                "      \"query\" : \"to be or not to be\",\n" +
                "      \"type\" : \"boolean\",\n" +
                "      \"operator\" : \"AND\",\n" +
                "      \"slop\" : 0,\n" +
                "      \"prefix_length\" : 0,\n" +
                "      \"max_expansions\" : 50,\n" +
                "      \"fuzzy_transpositions\" : true,\n" +
                "      \"lenient\" : false,\n" +
                "      \"zero_terms_query\" : \"ALL\",\n" +
                "      \"boost\" : 1.0\n" +
                "    }\n" +
                "  }\n" +
                "}";
        MatchQueryBuilder qb = (MatchQueryBuilder) parseQuery(json);
        checkGeneratedJson(json, qb);

        assertEquals(json, "to be or not to be", qb.value());
        assertEquals(json, Operator.AND, qb.operator());
    }
}
