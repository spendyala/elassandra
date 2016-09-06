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

package org.elasticsearch.common.lucene.search;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryWrapperFilter;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.index.mapper.internal.TokenFieldMapper;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;

/**
 *
 */
public class Queries {

	public static Query newTokenRangeQuery(Collection<Range<Token>> tokenRanges) {
		Query tokenRangeQuery = null;
        if (tokenRanges != null) {
            switch(tokenRanges.size()) {
                case 0:
                	break;
                case 1: 
                    Range<Token> unique_range = tokenRanges.iterator().next();
                    NumericRangeQuery<Long> nrq2 = NumericRangeQuery.newLongRange(TokenFieldMapper.NAME, 16, (Long) unique_range.left.getTokenValue(), (Long) unique_range.right.getTokenValue(), false, true);
                    tokenRangeQuery = nrq2;
                    break;
                default:
                    BooleanQuery.Builder bq2 = new BooleanQuery.Builder();
                    for (Range<Token> range : tokenRanges) {
                        // TODO: check the best precisionStep (6 by default), see https://lucene.apache.org/core/5_2_1/core/org/apache/lucene/search/NumericRangeQuery.html
                        NumericRangeQuery<Long> nrq = NumericRangeQuery.newLongRange(TokenFieldMapper.NAME, 16, (Long) range.left.getTokenValue(), (Long) range.right.getTokenValue(), false, true);
                        bq2.add(nrq, Occur.SHOULD);
                    }
                    tokenRangeQuery = bq2.build();
                    break;
            }
        }
        return tokenRangeQuery;
	}
	
    public static Query newMatchAllQuery() {
        return new MatchAllDocsQuery();
    }

    /** Return a query that matches no document. */
    public static Query newMatchNoDocsQuery() {
        return new BooleanQuery.Builder().build();
    }

    public static Filter newNestedFilter() {
        return new QueryWrapperFilter(new PrefixQuery(new Term(TypeFieldMapper.NAME, new BytesRef("__"))));
    }

    public static Filter newNonNestedFilter() {
        return new QueryWrapperFilter(not(newNestedFilter()));
    }

    public static BooleanQuery filtered(@Nullable Query query, @Nullable Query filter) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        if (query != null) {
            builder.add(new BooleanClause(query, Occur.MUST));
        }
        if (filter != null) {
            builder.add(new BooleanClause(filter, Occur.FILTER));
        }
        return builder.build();
    }

    /** Return a query that matches all documents but those that match the given query. */
    public static Query not(Query q) {
        return new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.MUST)
            .add(q, Occur.MUST_NOT)
            .build();
    }

    public static boolean isNegativeQuery(Query q) {
        if (!(q instanceof BooleanQuery)) {
            return false;
        }
        List<BooleanClause> clauses = ((BooleanQuery) q).clauses();
        if (clauses.isEmpty()) {
            return false;
        }
        for (BooleanClause clause : clauses) {
            if (!clause.isProhibited()) return false;
        }
        return true;
    }

    public static Query fixNegativeQueryIfNeeded(Query q) {
        if (isNegativeQuery(q)) {
            BooleanQuery bq = (BooleanQuery) q;
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.setDisableCoord(bq.isCoordDisabled());
            for (BooleanClause clause : bq) {
                builder.add(clause);
            }
            builder.add(newMatchAllQuery(), BooleanClause.Occur.MUST);
            return builder.build();
        }
        return q;
    }

    public static boolean isConstantMatchAllQuery(Query query) {
        if (query instanceof ConstantScoreQuery) {
            return isConstantMatchAllQuery(((ConstantScoreQuery) query).getQuery());
        } else if (query instanceof QueryWrapperFilter) {
            return isConstantMatchAllQuery(((QueryWrapperFilter) query).getQuery());
        } else if (query instanceof MatchAllDocsQuery) {
            return true;
        }
        return false;
    }

    public static BooleanQuery applyMinimumShouldMatch(BooleanQuery query, @Nullable String minimumShouldMatch) {
        if (minimumShouldMatch == null) {
            return query;
        }
        int optionalClauses = 0;
        for (BooleanClause c : query.clauses()) {
            if (c.getOccur() == BooleanClause.Occur.SHOULD) {
                optionalClauses++;
            }
        }

        int msm = calculateMinShouldMatch(optionalClauses, minimumShouldMatch);
        if (0 < msm) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.setDisableCoord(query.isCoordDisabled());
            for (BooleanClause clause : query) {
                builder.add(clause);
            }
            builder.setMinimumNumberShouldMatch(msm);
            BooleanQuery bq = builder.build();
            bq.setBoost(query.getBoost());
            query = bq;
        }
        return query;
    }

    private static Pattern spaceAroundLessThanPattern = Pattern.compile("(\\s+<\\s*)|(\\s*<\\s+)");
    private static Pattern spacePattern = Pattern.compile(" ");
    private static Pattern lessThanPattern = Pattern.compile("<");

    public static int calculateMinShouldMatch(int optionalClauseCount, String spec) {
        int result = optionalClauseCount;
        spec = spec.trim();

        if (-1 < spec.indexOf("<")) {
            /* we have conditional spec(s) */
            spec = spaceAroundLessThanPattern.matcher(spec).replaceAll("<");
            for (String s : spacePattern.split(spec)) {
                String[] parts = lessThanPattern.split(s, 0);
                int upperBound = Integer.parseInt(parts[0]);
                if (optionalClauseCount <= upperBound) {
                    return result;
                } else {
                    result = calculateMinShouldMatch
                            (optionalClauseCount, parts[1]);
                }
            }
            return result;
        }

        /* otherwise, simple expression */

        if (-1 < spec.indexOf('%')) {
            /* percentage - assume the % was the last char.  If not, let Integer.parseInt fail. */
            spec = spec.substring(0, spec.length() - 1);
            int percent = Integer.parseInt(spec);
            float calc = (result * percent) * (1 / 100f);
            result = calc < 0 ? result + (int) calc : (int) calc;
        } else {
            int calc = Integer.parseInt(spec);
            result = calc < 0 ? result + calc : calc;
        }

        return (optionalClauseCount < result ?
                optionalClauseCount : (result < 0 ? 0 : result));

    }
}
