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

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.script.Script;

import java.io.IOException;
import java.util.Objects;

public class QueryParseContext implements ParseFieldMatcherSupplier {

    private static final ParseField CACHE = new ParseField("_cache").withAllDeprecated("Elasticsearch makes its own caching decisions");
    private static final ParseField CACHE_KEY = new ParseField("_cache_key").withAllDeprecated("Filters are always used as cache keys");

    private final XContentParser parser;
    private final IndicesQueriesRegistry indicesQueriesRegistry;
    private final ParseFieldMatcher parseFieldMatcher;
    private final String defaultScriptLanguage;

    public QueryParseContext(IndicesQueriesRegistry registry, XContentParser parser, ParseFieldMatcher parseFieldMatcher) {
        this(Script.DEFAULT_SCRIPT_LANG, registry, parser, parseFieldMatcher);
    }

    public QueryParseContext(String defaultScriptLanguage, IndicesQueriesRegistry registry, XContentParser parser,
                             ParseFieldMatcher parseFieldMatcher) {
        this.indicesQueriesRegistry = Objects.requireNonNull(registry, "indices queries registry cannot be null");
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.parseFieldMatcher = Objects.requireNonNull(parseFieldMatcher, "parse field matcher cannot be null");
        this.defaultScriptLanguage = defaultScriptLanguage;
    }

    public XContentParser parser() {
        return this.parser;
    }

    public boolean isDeprecatedSetting(String setting) {
        return this.parseFieldMatcher.match(setting, CACHE) || this.parseFieldMatcher.match(setting, CACHE_KEY);
    }

    /**
     * Parses a top level query including the query element that wraps it
     */
    public QueryBuilder parseTopLevelQueryBuilder() {
        try {
            QueryBuilder queryBuilder = null;
            for (XContentParser.Token token = parser.nextToken(); token != XContentParser.Token.END_OBJECT; token = parser.nextToken()) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    String fieldName = parser.currentName();
                    if ("query".equals(fieldName)) {
                        queryBuilder = parseInnerQueryBuilder();
                    } else {
                        throw new ParsingException(parser.getTokenLocation(), "request does not support [" + parser.currentName() + "]");
                    }
                }
            }
            return queryBuilder;
        } catch (ParsingException e) {
            throw e;
        } catch (Exception e) {
            throw new ParsingException(parser == null ? null : parser.getTokenLocation(), "Failed to parse", e);
        }
    }

    /**
     * Parses a query excluding the query element that wraps it
     */
    public QueryBuilder parseInnerQueryBuilder() throws IOException {
        if (parser.currentToken() != XContentParser.Token.START_OBJECT) {
            if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                throw new ParsingException(parser.getTokenLocation(), "[_na] query malformed, must start with start_object");
            }
        }
        if (parser.nextToken() == XContentParser.Token.END_OBJECT) {
            // we encountered '{}' for a query clause, it used to be supported, deprecated in 5.0 and removed in 6.0
            throw new IllegalArgumentException("query malformed, empty clause found at [" + parser.getTokenLocation() +"]");
        }
        if (parser.currentToken() != XContentParser.Token.FIELD_NAME) {
            throw new ParsingException(parser.getTokenLocation(), "[_na] query malformed, no field after start_object");
        }
        String queryName = parser.currentName();
        // move to the next START_OBJECT
        if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
            throw new ParsingException(parser.getTokenLocation(), "[" + queryName + "] query malformed, no start_object after query name");
        }
        QueryBuilder result = indicesQueriesRegistry.lookup(queryName, parseFieldMatcher, parser.getTokenLocation()).fromXContent(this);
        //end_object of the specific query (e.g. match, multi_match etc.) element
        if (parser.currentToken() != XContentParser.Token.END_OBJECT) {
            throw new ParsingException(parser.getTokenLocation(),
                    "[" + queryName + "] malformed query, expected [END_OBJECT] but found [" + parser.currentToken() + "]");
        }
        //end_object of the query object
        if (parser.nextToken() != XContentParser.Token.END_OBJECT) {
            throw new ParsingException(parser.getTokenLocation(),
                    "[" + queryName + "] malformed query, expected [END_OBJECT] but found [" + parser.currentToken() + "]");
        }
        return result;
    }

    @Override
    public ParseFieldMatcher getParseFieldMatcher() {
        return parseFieldMatcher;
    }

    /**
     * Returns the default scripting language, that should be used if scripts don't specify the script language
     * explicitly.
     */
    public String getDefaultScriptLanguage() {
        return defaultScriptLanguage;
    }
}
