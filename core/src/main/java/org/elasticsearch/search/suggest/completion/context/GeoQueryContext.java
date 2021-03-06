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

package org.elasticsearch.search.suggest.completion.context;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.search.suggest.completion.context.GeoContextMapping.CONTEXT_BOOST;
import static org.elasticsearch.search.suggest.completion.context.GeoContextMapping.CONTEXT_NEIGHBOURS;
import static org.elasticsearch.search.suggest.completion.context.GeoContextMapping.CONTEXT_PRECISION;
import static org.elasticsearch.search.suggest.completion.context.GeoContextMapping.CONTEXT_VALUE;

/**
 * Defines the query context for {@link GeoContextMapping}
 */
public final class GeoQueryContext implements ToXContent {
    private final GeoPoint geoPoint;
    private final int boost;
    private final int precision;
    private final List<Integer> neighbours;

    private GeoQueryContext(GeoPoint geoPoint, int boost, int precision, List<Integer> neighbours) {
        this.geoPoint = geoPoint;
        this.boost = boost;
        this.precision = precision;
        this.neighbours = neighbours;
    }

    /**
     * Returns the geo point of the context
     */
    public GeoPoint getGeoPoint() {
        return geoPoint;
    }

    /**
     * Returns the query-time boost of the context
     */
    public int getBoost() {
        return boost;
    }

    /**
     * Returns the precision (length) for the geohash
     */
    public int getPrecision() {
        return precision;
    }

    /**
     * Returns the precision levels at which geohash cells neighbours are considered
     */
    public List<Integer> getNeighbours() {
        return neighbours;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private GeoPoint geoPoint;
        private int boost = 1;
        private int precision = -1;
        private List<Integer> neighbours = Collections.emptyList();

        public Builder() {
        }

        /**
         * Sets the query-time boost for the context
         * Defaults to 1
         */
        public Builder setBoost(int boost) {
            this.boost = boost;
            return this;
        }

        /**
         * Sets the precision level for computing the geohash from the context geo point.
         * Defaults to using index-time precision level
         */
        public Builder setPrecision(int precision) {
            this.precision = precision;
            return this;
        }

        /**
         * Sets the precision levels at which geohash cells neighbours are considered.
         * Defaults to only considering neighbours at the index-time precision level
         */
        public Builder setNeighbours(List<Integer> neighbours) {
            this.neighbours = neighbours;
            return this;
        }

        /**
         * Sets the geo point of the context.
         * This is a required field
         */
        public Builder setGeoPoint(GeoPoint geoPoint) {
            this.geoPoint = geoPoint;
            return this;
        }

        private double lat = Double.NaN;
        void setLat(double lat) {
            this.lat = lat;
        }

        private double lon = Double.NaN;
        void setLon(double lon) {
            this.lon = lon;
        }

        public GeoQueryContext build() {
            if (geoPoint == null) {
                if (Double.isNaN(lat) == false && Double.isNaN(lon) == false) {
                    geoPoint = new GeoPoint(lat, lon);
                } else {
                    throw new IllegalArgumentException("no geohash or geo point provided");
                }
            }
            return new GeoQueryContext(geoPoint, boost, precision, neighbours);
        }
    }

    private static ObjectParser<GeoQueryContext.Builder, Void> GEO_CONTEXT_PARSER = new ObjectParser<>("geo", null);
    static {
        GEO_CONTEXT_PARSER.declareField((parser, geoQueryContext, geoContextMapping) -> geoQueryContext.setGeoPoint(GeoUtils.parseGeoPoint(parser)), new ParseField("context"), ObjectParser.ValueType.OBJECT);
        GEO_CONTEXT_PARSER.declareInt(GeoQueryContext.Builder::setBoost, new ParseField("boost"));
        // TODO : add string support for precision for GeoUtils.geoHashLevelsForPrecision()
        GEO_CONTEXT_PARSER.declareInt(GeoQueryContext.Builder::setPrecision, new ParseField("precision"));
        // TODO : add string array support for precision for GeoUtils.geoHashLevelsForPrecision()
        GEO_CONTEXT_PARSER.declareIntArray(GeoQueryContext.Builder::setNeighbours, new ParseField("neighbours"));
        GEO_CONTEXT_PARSER.declareDouble(GeoQueryContext.Builder::setLat, new ParseField("lat"));
        GEO_CONTEXT_PARSER.declareDouble(GeoQueryContext.Builder::setLon, new ParseField("lon"));
    }

    public static GeoQueryContext parse(XContentParser parser) throws IOException {
        XContentParser.Token token = parser.currentToken();
        GeoQueryContext.Builder builder = new Builder();
        if (token == XContentParser.Token.START_OBJECT) {
            GEO_CONTEXT_PARSER.parse(parser, builder);
        } else if (token == XContentParser.Token.VALUE_STRING) {
            builder.setGeoPoint(GeoPoint.fromGeohash(parser.text()));
        } else {
            throw new ElasticsearchParseException("geo context must be an object or string");
        }
        return builder.build();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.startObject(CONTEXT_VALUE);
        builder.field("lat", geoPoint.getLat());
        builder.field("lon", geoPoint.getLon());
        builder.endObject();
        builder.field(CONTEXT_BOOST, boost);
        builder.field(CONTEXT_NEIGHBOURS, neighbours);
        builder.field(CONTEXT_PRECISION, precision);
        builder.endObject();
        return builder;
    }
}
