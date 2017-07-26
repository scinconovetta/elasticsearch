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
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANYDa
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.mapper;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.DoubleRange;
import org.apache.lucene.document.FloatRange;
import org.apache.lucene.document.IntRange;
import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.document.InetAddressRange;
import org.apache.lucene.document.LongRange;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.geo.ShapeRelation;
import org.elasticsearch.common.joda.DateMathParser;
import org.elasticsearch.common.joda.FormatDateTimeFormatter;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.LocaleUtils;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.NumberFieldMapper.NumberType;
import org.elasticsearch.index.query.QueryShardContext;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.index.mapper.TypeParsers.parseDateTimeFormatter;
import static org.elasticsearch.index.query.RangeQueryBuilder.GT_FIELD;
import static org.elasticsearch.index.query.RangeQueryBuilder.GTE_FIELD;
import static org.elasticsearch.index.query.RangeQueryBuilder.LT_FIELD;
import static org.elasticsearch.index.query.RangeQueryBuilder.LTE_FIELD;

/** A {@link FieldMapper} for indexing numeric and date ranges, and creating queries */
public class RangeFieldMapper extends FieldMapper {
    public static final boolean DEFAULT_INCLUDE_UPPER = true;
    public static final boolean DEFAULT_INCLUDE_LOWER = true;

    public static class Defaults {
        public static final Explicit<Boolean> COERCE = new Explicit<>(true, false);
    }

    // this is private since it has a different default
    static final Setting<Boolean> COERCE_SETTING =
        Setting.boolSetting("index.mapping.coerce", true, Setting.Property.IndexScope);

    public static class Builder extends FieldMapper.Builder<Builder, RangeFieldMapper> {
        private Boolean coerce;
        private Locale locale;

        public Builder(String name, RangeType type) {
            super(name, new RangeFieldType(type), new RangeFieldType(type));
            builder = this;
            locale = Locale.ROOT;
        }

        @Override
        public RangeFieldType fieldType() {
            return (RangeFieldType)fieldType;
        }

        @Override
        public Builder docValues(boolean docValues) {
            if (docValues == true) {
                throw new IllegalArgumentException("field [" + name + "] does not currently support " + TypeParsers.DOC_VALUES);
            }
            return super.docValues(docValues);
        }

        public Builder coerce(boolean coerce) {
            this.coerce = coerce;
            return builder;
        }

        protected Explicit<Boolean> coerce(BuilderContext context) {
            if (coerce != null) {
                return new Explicit<>(coerce, true);
            }
            if (context.indexSettings() != null) {
                return new Explicit<>(COERCE_SETTING.get(context.indexSettings()), false);
            }
            return Defaults.COERCE;
        }

        public Builder dateTimeFormatter(FormatDateTimeFormatter dateTimeFormatter) {
            fieldType().setDateTimeFormatter(dateTimeFormatter);
            return this;
        }

        @Override
        public Builder nullValue(Object nullValue) {
            throw new IllegalArgumentException("Field [" + name() + "] does not support null value.");
        }

        public void locale(Locale locale) {
            this.locale = locale;
        }

        @Override
        protected void setupFieldType(BuilderContext context) {
            super.setupFieldType(context);
            FormatDateTimeFormatter dateTimeFormatter = fieldType().dateTimeFormatter;
            if (fieldType().rangeType == RangeType.DATE) {
                if (!locale.equals(dateTimeFormatter.locale())) {
                    fieldType().setDateTimeFormatter(new FormatDateTimeFormatter(dateTimeFormatter.format(),
                        dateTimeFormatter.parser(), dateTimeFormatter.printer(), locale));
                }
            } else if (dateTimeFormatter != null) {
                throw new IllegalArgumentException("field [" + name() + "] of type [" + fieldType().rangeType
                    + "] should not define a dateTimeFormatter unless it is a " + RangeType.DATE + " type");
            }
        }

        @Override
        public RangeFieldMapper build(BuilderContext context) {
            setupFieldType(context);
            return new RangeFieldMapper(name, fieldType, defaultFieldType, coerce(context), includeInAll,
                context.indexSettings(), multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        final RangeType type;

        public TypeParser(RangeType type) {
            this.type = type;
        }

        @Override
        public Mapper.Builder<?,?> parse(String name, Map<String, Object> node,
                                         ParserContext parserContext) throws MapperParsingException {
            Builder builder = new Builder(name, type);
            TypeParsers.parseField(builder, name, node, parserContext);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String propName = entry.getKey();
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    throw new MapperParsingException("Property [null_value] is not supported for [" + this.type.name
                            + "] field types.");
                } else if (propName.equals("coerce")) {
                    builder.coerce(TypeParsers.nodeBooleanValue(name, "coerce", propNode));
                    iterator.remove();
                } else if (propName.equals("locale")) {
                    builder.locale(LocaleUtils.parse(propNode.toString()));
                    iterator.remove();
                } else if (propName.equals("format")) {
                    builder.dateTimeFormatter(parseDateTimeFormatter(propNode));
                    iterator.remove();
                } else if (TypeParsers.parseMultiField(builder, name, parserContext, propName, propNode)) {
                    iterator.remove();
                }
            }
            return builder;
        }
    }

    public static final class RangeFieldType extends MappedFieldType {
        protected RangeType rangeType;
        protected FormatDateTimeFormatter dateTimeFormatter;
        protected DateMathParser dateMathParser;

        public RangeFieldType(RangeType type) {
            super();
            this.rangeType = Objects.requireNonNull(type);
            setTokenized(false);
            setHasDocValues(false);
            setOmitNorms(true);
            if (rangeType == RangeType.DATE) {
                setDateTimeFormatter(DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER);
            }
        }

        public RangeFieldType(RangeFieldType other) {
            super(other);
            this.rangeType = other.rangeType;
            if (other.dateTimeFormatter() != null) {
                setDateTimeFormatter(other.dateTimeFormatter);
            }
        }

        @Override
        public MappedFieldType clone() {
            return new RangeFieldType(this);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) return false;
            RangeFieldType that = (RangeFieldType) o;
            return Objects.equals(rangeType, that.rangeType) &&
            (rangeType == RangeType.DATE) ?
                Objects.equals(dateTimeFormatter.format(), that.dateTimeFormatter.format())
                && Objects.equals(dateTimeFormatter.locale(), that.dateTimeFormatter.locale())
                : dateTimeFormatter == null && that.dateTimeFormatter == null;
        }

        @Override
        public int hashCode() {
            return (dateTimeFormatter == null) ? Objects.hash(super.hashCode(), rangeType)
                : Objects.hash(super.hashCode(), rangeType, dateTimeFormatter.format(), dateTimeFormatter.locale());
        }

        @Override
        public String typeName() {
            return rangeType.name;
        }

        @Override
        public void checkCompatibility(MappedFieldType fieldType, List<String> conflicts, boolean strict) {
            super.checkCompatibility(fieldType, conflicts, strict);
            if (strict) {
                RangeFieldType other = (RangeFieldType)fieldType;
                if (this.rangeType != other.rangeType) {
                    conflicts.add("mapper [" + name()
                        + "] is attempting to update from type [" + rangeType.name
                        + "] to incompatible type [" + other.rangeType.name + "].");
                }
                if (this.rangeType == RangeType.DATE) {
                    if (Objects.equals(dateTimeFormatter().format(), other.dateTimeFormatter().format()) == false) {
                        conflicts.add("mapper [" + name()
                            + "] is used by multiple types. Set update_all_types to true to update [format] across all types.");
                    }
                    if (Objects.equals(dateTimeFormatter().locale(), other.dateTimeFormatter().locale()) == false) {
                        conflicts.add("mapper [" + name()
                            + "] is used by multiple types. Set update_all_types to true to update [locale] across all types.");
                    }
                }
            }
        }

        public FormatDateTimeFormatter dateTimeFormatter() {
            return dateTimeFormatter;
        }

        public void setDateTimeFormatter(FormatDateTimeFormatter dateTimeFormatter) {
            checkIfFrozen();
            this.dateTimeFormatter = dateTimeFormatter;
            this.dateMathParser = new DateMathParser(dateTimeFormatter);
        }

        protected DateMathParser dateMathParser() {
            return dateMathParser;
        }

        @Override
        public Query termQuery(Object value, QueryShardContext context) {
            Query query = rangeQuery(value, value, true, true, ShapeRelation.INTERSECTS, context);
            if (boost() != 1f) {
                query = new BoostQuery(query, boost());
            }
            return query;
        }

        public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper,
                                ShapeRelation relation, QueryShardContext context) {
            failIfNotIndexed();
            return rangeQuery(lowerTerm, upperTerm, includeLower, includeUpper, relation, null, dateMathParser, context);
        }

        public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper,
                                ShapeRelation relation, DateTimeZone timeZone, DateMathParser parser, QueryShardContext context) {
            return rangeType.rangeQuery(name(), lowerTerm, upperTerm, includeLower, includeUpper, relation, timeZone, parser, context);
        }
    }

    private Boolean includeInAll;
    private Explicit<Boolean> coerce;

    private RangeFieldMapper(
        String simpleName,
        MappedFieldType fieldType,
        MappedFieldType defaultFieldType,
        Explicit<Boolean> coerce,
        Boolean includeInAll,
        Settings indexSettings,
        MultiFields multiFields,
        CopyTo copyTo) {
        super(simpleName, fieldType, defaultFieldType, indexSettings, multiFields, copyTo);
        this.coerce = coerce;
        this.includeInAll = includeInAll;
    }

    @Override
    public RangeFieldType fieldType() {
        return (RangeFieldType) super.fieldType();
    }

    @Override
    protected String contentType() {
        return fieldType.typeName();
    }

    @Override
    protected RangeFieldMapper clone() {
        return (RangeFieldMapper) super.clone();
    }

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        final boolean includeInAll = context.includeInAll(this.includeInAll, this);
        Range range;
        if (context.externalValueSet()) {
            range = context.parseExternalValue(Range.class);
        } else {
            XContentParser parser = context.parser();
            if (parser.currentToken() == XContentParser.Token.START_OBJECT) {
                RangeFieldType fieldType = fieldType();
                RangeType rangeType = fieldType.rangeType;
                String fieldName = null;
                Object from = rangeType.minValue();
                Object to = rangeType.maxValue();
                boolean includeFrom = DEFAULT_INCLUDE_LOWER;
                boolean includeTo = DEFAULT_INCLUDE_UPPER;
                XContentParser.Token token;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        fieldName = parser.currentName();
                    } else {
                        if (fieldName.equals(GT_FIELD.getPreferredName())) {
                            includeFrom = false;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                from = rangeType.parseFrom(fieldType, parser, coerce.value(), includeFrom);
                            }
                        } else if (fieldName.equals(GTE_FIELD.getPreferredName())) {
                            includeFrom = true;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                from = rangeType.parseFrom(fieldType, parser, coerce.value(), includeFrom);
                            }
                        } else if (fieldName.equals(LT_FIELD.getPreferredName())) {
                            includeTo = false;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                to = rangeType.parseTo(fieldType, parser, coerce.value(), includeTo);
                            }
                        } else if (fieldName.equals(LTE_FIELD.getPreferredName())) {
                            includeTo = true;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                to = rangeType.parseTo(fieldType, parser, coerce.value(), includeTo);
                            }
                        } else {
                            throw new MapperParsingException("error parsing field [" +
                                name() + "], with unknown parameter [" + fieldName + "]");
                        }
                    }
                }
                range = new Range(rangeType, from, to, includeFrom, includeTo);
            } else {
                throw new MapperParsingException("error parsing field ["
                    + name() + "], expected an object but got " + parser.currentName());
            }
        }
        if (includeInAll) {
            context.allEntries().addText(fieldType.name(), range.toString(), fieldType.boost());
        }
        boolean indexed = fieldType.indexOptions() != IndexOptions.NONE;
        boolean docValued = fieldType.hasDocValues();
        boolean stored = fieldType.stored();
        fields.addAll(fieldType().rangeType.createFields(name(), range, indexed, docValued, stored));
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        super.doMerge(mergeWith, updateAllTypes);
        RangeFieldMapper other = (RangeFieldMapper) mergeWith;
        this.includeInAll = other.includeInAll;
        if (other.coerce.explicit()) {
            this.coerce = other.coerce;
        }
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);

        if (fieldType().rangeType == RangeType.DATE
                && (includeDefaults || (fieldType().dateTimeFormatter() != null
                && fieldType().dateTimeFormatter().format().equals(DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.format()) == false))) {
            builder.field("format", fieldType().dateTimeFormatter().format());
        }
        if (fieldType().rangeType == RangeType.DATE
                && (includeDefaults || (fieldType().dateTimeFormatter() != null
                && fieldType().dateTimeFormatter().locale() != Locale.ROOT))) {
            builder.field("locale", fieldType().dateTimeFormatter().locale());
        }
        if (includeDefaults || coerce.explicit()) {
            builder.field("coerce", coerce.value());
        }
        if (includeInAll != null) {
            builder.field("include_in_all", includeInAll);
        } else if (includeDefaults) {
            builder.field("include_in_all", false);
        }
    }

    /** Enum defining the type of range */
    public enum RangeType {
        IP("ip_range") {
            @Override
            public Field getRangeField(String name, Range r) {
                return new InetAddressRange(name, (InetAddress)r.from, (InetAddress)r.to);
            }
            @Override
            public InetAddress parseFrom(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException {
                InetAddress address = InetAddresses.forString(parser.text());
                return included ? address : nextUp(address);
            }
            @Override
            public InetAddress parseTo(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException {
                InetAddress address = InetAddresses.forString(parser.text());
                return included ? address : nextDown(address);
            }
            @Override
            public InetAddress parse(Object value, boolean coerce) {
                return value instanceof InetAddress ? (InetAddress) value : InetAddresses.forString((String) value);
            }
            @Override
            public InetAddress minValue() {
                return InetAddressPoint.MIN_VALUE;
            }
            @Override
            public InetAddress maxValue() {
                return InetAddressPoint.MAX_VALUE;
            }
            @Override
            public InetAddress nextUp(Object value) {
                return InetAddressPoint.nextUp((InetAddress)value);
            }
            @Override
            public InetAddress nextDown(Object value) {
                return InetAddressPoint.nextDown((InetAddress)value);
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                InetAddress lower = (InetAddress)from;
                InetAddress upper = (InetAddress)to;
                return InetAddressRange.newWithinQuery(field,
                    includeLower ? lower : nextUp(lower), includeUpper ? upper : nextDown(upper));
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                InetAddress lower = (InetAddress)from;
                InetAddress upper = (InetAddress)to;
                return InetAddressRange.newContainsQuery(field,
                    includeLower ? lower : nextUp(lower), includeUpper ? upper : nextDown(upper));
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                InetAddress lower = (InetAddress)from;
                InetAddress upper = (InetAddress)to;
                return InetAddressRange.newIntersectsQuery(field,
                    includeLower ? lower : nextUp(lower), includeUpper ? upper : nextDown(upper));
            }
            public String toString(InetAddress address) {
                return InetAddresses.toAddrString(address);
            }
        },
        DATE("date_range", NumberType.LONG) {
            @Override
            public Field getRangeField(String name, Range r) {
                return new LongRange(name, new long[] {((Number)r.from).longValue()}, new long[] {((Number)r.to).longValue()});
            }
            private Number parse(DateMathParser dateMathParser, String dateStr) {
                return dateMathParser.parse(dateStr, () -> {throw new IllegalArgumentException("now is not used at indexing time");});
            }
            @Override
            public Number parseFrom(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException {
                Number value = parse(fieldType.dateMathParser, parser.text());
                return included ? value : nextUp(value);
            }
            @Override
            public Number parseTo(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException{
                Number value = parse(fieldType.dateMathParser, parser.text());
                return included ? value : nextDown(value);
            }
            @Override
            public Long minValue() {
                return Long.MIN_VALUE;
            }
            @Override
            public Long maxValue() {
                return Long.MAX_VALUE;
            }
            @Override
            public Long nextUp(Object value) {
                return (long) LONG.nextUp(value);
            }
            @Override
            public Long nextDown(Object value) {
                return (long) LONG.nextDown(value);
            }
            @Override
            public Query rangeQuery(String field, Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper,
                                    ShapeRelation relation, @Nullable DateTimeZone timeZone, @Nullable DateMathParser parser,
                                    QueryShardContext context) {
                DateTimeZone zone = (timeZone == null) ? DateTimeZone.UTC : timeZone;
                DateMathParser dateMathParser = (parser == null) ?
                    new DateMathParser(DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER) : parser;
                Long low = lowerTerm == null ? Long.MIN_VALUE :
                    dateMathParser.parse(lowerTerm instanceof BytesRef ? ((BytesRef) lowerTerm).utf8ToString() : lowerTerm.toString(),
                        context::nowInMillis, false, zone);
                Long high = upperTerm == null ? Long.MAX_VALUE :
                    dateMathParser.parse(upperTerm instanceof BytesRef ? ((BytesRef) upperTerm).utf8ToString() : upperTerm.toString(),
                        context::nowInMillis, false, zone);

                return super.rangeQuery(field, low, high, includeLower, includeUpper, relation, zone, dateMathParser, context);
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                return LONG.withinQuery(field, from, to, includeLower, includeUpper);
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                return LONG.containsQuery(field, from, to, includeLower, includeUpper);
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                return LONG.intersectsQuery(field, from, to, includeLower, includeUpper);
            }
        },
        // todo support half_float
        FLOAT("float_range", NumberType.FLOAT) {
            @Override
            public Float minValue() {
                return Float.NEGATIVE_INFINITY;
            }
            @Override
            public Float maxValue() {
                return Float.POSITIVE_INFINITY;
            }
            @Override
            public Float nextUp(Object value) {
                return Math.nextUp(((Number)value).floatValue());
            }
            @Override
            public Float nextDown(Object value) {
                return Math.nextDown(((Number)value).floatValue());
            }
            @Override
            public Field getRangeField(String name, Range r) {
                return new FloatRange(name, new float[] {((Number)r.from).floatValue()}, new float[] {((Number)r.to).floatValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return FloatRange.newWithinQuery(field,
                    new float[] {includeFrom ? (Float)from : Math.nextUp((Float)from)},
                    new float[] {includeTo ? (Float)to : Math.nextDown((Float)to)});
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return FloatRange.newContainsQuery(field,
                    new float[] {includeFrom ? (Float)from : Math.nextUp((Float)from)},
                    new float[] {includeTo ? (Float)to : Math.nextDown((Float)to)});
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return FloatRange.newIntersectsQuery(field,
                    new float[] {includeFrom ? (Float)from : Math.nextUp((Float)from)},
                    new float[] {includeTo ? (Float)to : Math.nextDown((Float)to)});
            }
        },
        DOUBLE("double_range", NumberType.DOUBLE) {
            @Override
            public Double minValue() {
                return Double.NEGATIVE_INFINITY;
            }
            @Override
            public Double maxValue() {
                return Double.POSITIVE_INFINITY;
            }
            @Override
            public Double nextUp(Object value) {
                return Math.nextUp(((Number)value).doubleValue());
            }
            @Override
            public Double nextDown(Object value) {
                return Math.nextDown(((Number)value).doubleValue());
            }
            @Override
            public Field getRangeField(String name, Range r) {
                return new DoubleRange(name, new double[] {((Number)r.from).doubleValue()}, new double[] {((Number)r.to).doubleValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return DoubleRange.newWithinQuery(field,
                    new double[] {includeFrom ? (Double)from : Math.nextUp((Double)from)},
                    new double[] {includeTo ? (Double)to : Math.nextDown((Double)to)});
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return DoubleRange.newContainsQuery(field,
                    new double[] {includeFrom ? (Double)from : Math.nextUp((Double)from)},
                    new double[] {includeTo ? (Double)to : Math.nextDown((Double)to)});
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return DoubleRange.newIntersectsQuery(field,
                    new double[] {includeFrom ? (Double)from : Math.nextUp((Double)from)},
                    new double[] {includeTo ? (Double)to : Math.nextDown((Double)to)});
            }
        },
        // todo add BYTE support
        // todo add SHORT support
        INTEGER("integer_range", NumberType.INTEGER) {
            @Override
            public Integer minValue() {
                return Integer.MIN_VALUE;
            }
            @Override
            public Integer maxValue() {
                return Integer.MAX_VALUE;
            }
            @Override
            public Integer nextUp(Object value) {
                return ((Number)value).intValue() + 1;
            }
            @Override
            public Integer nextDown(Object value) {
                return ((Number)value).intValue() - 1;
            }
            @Override
            public Field getRangeField(String name, Range r) {
                return new IntRange(name, new int[] {((Number)r.from).intValue()}, new int[] {((Number)r.to).intValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return IntRange.newWithinQuery(field, new int[] {(Integer)from + (includeFrom ? 0 : 1)},
                    new int[] {(Integer)to - (includeTo ? 0 : 1)});
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return IntRange.newContainsQuery(field, new int[] {(Integer)from + (includeFrom ? 0 : 1)},
                    new int[] {(Integer)to - (includeTo ? 0 : 1)});
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return IntRange.newIntersectsQuery(field, new int[] {(Integer)from + (includeFrom ? 0 : 1)},
                    new int[] {(Integer)to - (includeTo ? 0 : 1)});
            }
        },
        LONG("long_range", NumberType.LONG) {
            @Override
            public Long minValue() {
                return Long.MIN_VALUE;
            }
            @Override
            public Long maxValue() {
                return Long.MAX_VALUE;
            }
            @Override
            public Long nextUp(Object value) {
                return ((Number)value).longValue() + 1;
            }
            @Override
            public Long nextDown(Object value) {
                return ((Number)value).longValue() - 1;
            }
            @Override
            public Field getRangeField(String name, Range r) {
                return new LongRange(name, new long[] {((Number)r.from).longValue()},
                    new long[] {((Number)r.to).longValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return LongRange.newWithinQuery(field,  new long[] {(Long)from + (includeFrom ? 0 : 1)},
                    new long[] {(Long)to - (includeTo ? 0 : 1)});
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return LongRange.newContainsQuery(field,  new long[] {(Long)from + (includeFrom ? 0 : 1)},
                    new long[] {(Long)to - (includeTo ? 0 : 1)});
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return LongRange.newIntersectsQuery(field,  new long[] {(Long)from + (includeFrom ? 0 : 1)},
                    new long[] {(Long)to - (includeTo ? 0 : 1)});
            }
        };

        RangeType(String name) {
            this.name = name;
            this.numberType = null;
        }

        RangeType(String name, NumberType type) {
            this.name = name;
            this.numberType = type;
        }

        /** Get the associated type name. */
        public final String typeName() {
            return name;
        }

        public abstract Field getRangeField(String name, Range range);
        public List<IndexableField> createFields(String name, Range range, boolean indexed, boolean docValued, boolean stored) {
            assert range != null : "range cannot be null when creating fields";
            List<IndexableField> fields = new ArrayList<>();
            if (indexed) {
                fields.add(getRangeField(name, range));
            }
            // todo add docValues ranges once aggregations are supported
            if (stored) {
                fields.add(new StoredField(name, range.toString()));
            }
            return fields;
        }
        /** parses from value. rounds according to included flag */
        public Object parseFrom(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included) throws IOException {
            Number value = numberType.parse(parser, coerce);
            return included ? value : (Number)nextUp(value);
        }
        /** parses to value. rounds according to included flag */
        public Object parseTo(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included) throws IOException {
            Number value = numberType.parse(parser, coerce);
            return included ? value : (Number)nextDown(value);
        }

        public abstract Object minValue();
        public abstract Object maxValue();
        public abstract Object nextUp(Object value);
        public abstract Object nextDown(Object value);
        public abstract Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo);
        public abstract Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo);
        public abstract Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo);
        public Object parse(Object value, boolean coerce) {
            return numberType.parse(value, coerce);
        }
        public Query rangeQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo,
                ShapeRelation relation, @Nullable DateTimeZone timeZone, @Nullable DateMathParser dateMathParser,
                QueryShardContext context) {
            Object lower = from == null ? minValue() : parse(from, false);
            Object upper = to == null ? maxValue() : parse(to, false);
            if (relation == ShapeRelation.WITHIN) {
                return withinQuery(field, lower, upper, includeFrom, includeTo);
            } else if (relation == ShapeRelation.CONTAINS) {
                return containsQuery(field, lower, upper, includeFrom, includeTo);
            }
            return intersectsQuery(field, lower, upper, includeFrom, includeTo);
        }

        public final String name;
        private final NumberType numberType;
    }

    /** Class defining a range */
    public static class Range {
        RangeType type;
        private Object from;
        private Object to;
        private boolean includeFrom;
        private boolean includeTo;

        public Range(RangeType type, Object from, Object to, boolean includeFrom, boolean includeTo) {
            this.type = type;
            this.from = from;
            this.to = to;
            this.includeFrom = includeFrom;
            this.includeTo = includeTo;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(includeFrom ? '[' : '(');
            Object f = includeFrom || from.equals(type.minValue()) ? from : type.nextDown(from);
            Object t = includeTo || to.equals(type.maxValue()) ? to : type.nextUp(to);
            sb.append(type == RangeType.IP ? InetAddresses.toAddrString((InetAddress)f) : f.toString());
            sb.append(" : ");
            sb.append(type == RangeType.IP ? InetAddresses.toAddrString((InetAddress)t) : t.toString());
            sb.append(includeTo ? ']' : ')');
            return sb.toString();
        }
    }
}
