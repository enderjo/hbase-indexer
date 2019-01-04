/*
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ngdata.hbaseindexer.morphline;

import com.google.common.base.Preconditions;
import com.ngdata.hbaseindexer.parse.ByteArrayExtractor;
import com.ngdata.hbaseindexer.parse.ByteArrayValueMapper;
import com.ngdata.hbaseindexer.parse.ByteArrayValueMappers;
import com.ngdata.hbaseindexer.parse.extract.PrefixMatchingCellExtractor;
import com.ngdata.hbaseindexer.parse.extract.PrefixMatchingQualifierExtractor;
import com.ngdata.hbaseindexer.parse.extract.SingleCellExtractor;
import com.typesafe.config.Config;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.kitesdk.morphline.api.*;
import org.kitesdk.morphline.base.AbstractCommand;
import org.kitesdk.morphline.base.Configs;
import org.kitesdk.morphline.base.Fields;
import org.kitesdk.morphline.base.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Command that extracts cells from the given HBase Result, and transforms the resulting values into a
 * SolrInputDocument.
 */
public final class ExtractHBaseCellsBuilder implements CommandBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractHBaseCellsBuilder.class);

    @Override
    public Collection<String> getNames() {
        return Collections.singletonList("extractHBaseCells");
    }

    @Override
    public Command build(Config config, Command parent, Command child, MorphlineContext context) {
        return new ExtractHBaseCells(this, config, parent, child, context);
    }

    // /////////////////////////////////////////////////////////////////////////////
    // Nested classes:
    // /////////////////////////////////////////////////////////////////////////////

    /**
     * Specifies where values to index should be extracted from in an HBase {@code KeyValue}.
     */
    private enum LowerCaseValueSource {
        /**
         * Extract values to index from the column qualifier of a {@code KeyValue}.
         * we expect lowercase in config file!
         */
        qualifier,

        /**
         * Extract values to index from the cell value of a {@code KeyValue}.
         * we expect lowercase in config file!
         */
        value
    }

    // /////////////////////////////////////////////////////////////////////////////
    // Nested classes:
    // /////////////////////////////////////////////////////////////////////////////

    private static final class ExtractHBaseCells extends AbstractCommand {

        private final List<Mapping> mappings = new ArrayList<>();

        public ExtractHBaseCells(CommandBuilder builder, Config config, Command parent, Command child, MorphlineContext context) {
            super(builder, config, parent, child, context);
            for (Config mapping : getConfigs().getConfigList(config, "mappings")) {
                mappings.add(new Mapping(mapping, context));
            }
            validateArguments();
        }

        @Override
        protected boolean doProcess(Record record) {
            Result result = (Result) record.getFirstValue(Fields.ATTACHMENT_BODY);
            Preconditions.checkNotNull(result);
            removeAttachments(record);
            for (Mapping mapping : mappings) {
                mapping.apply(result, record);
            }
            // pass record to next command in chain:
            return super.doProcess(record);
        }

        private void removeAttachments(Record outputRecord) {
            outputRecord.removeAll(Fields.ATTACHMENT_BODY);
            outputRecord.removeAll(Fields.ATTACHMENT_MIME_TYPE);
            outputRecord.removeAll(Fields.ATTACHMENT_CHARSET);
            outputRecord.removeAll(Fields.ATTACHMENT_NAME);
        }

    }

    // /////////////////////////////////////////////////////////////////////////////
    // Nested classes:
    // /////////////////////////////////////////////////////////////////////////////

    private static final class Mapping {

        private final String inputColumn;
        private final byte[] columnFamily;
        private final byte[] qualifier;
        private final boolean isWildCard;
        private final String outputFieldName;
        private final List<String> outputFieldNames;
        private final boolean isDynamicOutputFieldName;
        private final ByteArrayExtractor extractor;
        private final String type;
        private final ByteArrayValueMapper byteArrayMapper;
        /**
         * 指定字段是否允许为空值 null或"",默认为true
         */
        private final boolean isAllowEmpty;

        /**
         * also see ByteArrayExtractors
         *
         * @param config
         * @param context
         */
        //
        public Mapping(Config config, MorphlineContext context) {
            Configs configs = new Configs();
            this.isAllowEmpty = configs.getBoolean(config, "isAllowEmpty", true);
            this.inputColumn = resolveColumnName(configs.getString(config, "inputColumn"));
            this.columnFamily = Bytes.toBytes(splitFamilyAndQualifier(inputColumn)[0]);

            String qualifierString = splitFamilyAndQualifier(inputColumn)[1];
            this.isWildCard = qualifierString.endsWith("*");
            if (isWildCard) {
                qualifierString = qualifierString.substring(0, qualifierString.length() - 1);
            }
            this.qualifier = Bytes.toBytes(qualifierString);

            String outputField = configs.getString(config, "outputField", null);
            this.outputFieldNames = configs.getStringList(config, "outputFields", null);
            if (outputField == null && outputFieldNames == null) {
                throw new MorphlineCompilationException("Either outputField or outputFields must be defined", config);
            }
            if (outputField != null && outputFieldNames != null) {
                throw new MorphlineCompilationException("Must not define both outputField and outputFields at the same time", config);
            }
            if (outputField == null) {
                this.isDynamicOutputFieldName = false;
                this.outputFieldName = null;
            } else {
                this.isDynamicOutputFieldName = outputField.endsWith("*");
                if (isDynamicOutputFieldName) {
                    this.outputFieldName = outputField.substring(0, outputField.length() - 1);
                } else {
                    this.outputFieldName = outputField;
                }
            }

            this.type = configs.getString(config, "type", "byte[]");
            // pass through byte[] to downstream morphline commands without conversion
            if (type.equals("byte[]")) {
                this.byteArrayMapper = new ByteArrayValueMapper() {
                    @Override
                    public Collection map(byte[] input) {
                        return Collections.singletonList(input);
                    }
                };
            } else {
                this.byteArrayMapper = ByteArrayValueMappers.getMapper(type);
            }

            LowerCaseValueSource source = new Validator<LowerCaseValueSource>().validateEnum(config,
                    configs.getString(config, "source", LowerCaseValueSource.value.toString()),
                    LowerCaseValueSource.class);

            if (source == LowerCaseValueSource.value) {
                if (isWildCard) {
                    this.extractor = new PrefixMatchingCellExtractor(columnFamily, qualifier);
                } else {
                    this.extractor = new SingleCellExtractor(columnFamily, qualifier);
                }
            } else {
                if (isWildCard) {
                    this.extractor = new PrefixMatchingQualifierExtractor(columnFamily, qualifier);
                } else {
                    throw new IllegalArgumentException("Can't create a non-prefix-based qualifier extractor");
                }
            }

            configs.validateArguments(config);
            if (context instanceof HBaseMorphlineContext) {
                ((HBaseMorphlineContext) context).getExtractors().add(this.extractor);
            }
        }

        private static String[] splitFamilyAndQualifier(String fieldValueExpression) {
            String[] splits = fieldValueExpression.split(":", 2);
            if (splits.length != 2) {
                throw new IllegalArgumentException("Invalid field value expression: " + fieldValueExpression);
            }
            return splits;
        }

        public void apply(Result result, Record record) {
            if (outputFieldNames != null) {
                extractWithMultipleOutputFieldNames(result, record);
            } else if (isDynamicOutputFieldName && isWildCard) {
                extractWithDynamicOutputFieldNames(result, record);
            } else {
                extractWithSingleOutputField(result, record);
            }
        }

        /**
         * Override for custom name resolution, if desired. For example you could override this to translate human
         * readable names to Kiji-encoded names.
         */
        protected String resolveColumnName(String inputColumn) {
            return inputColumn;
        }

        /**
         * 检测是否为空值(null或者"")
         *
         * @param value 需要校验的值
         * @return boolean
         */
        private boolean isEmpty(Object value) {
            if (value == null) {
                return true;
            }

            return StringUtils.trimToNull(value.toString()) == null;
        }

        private void extractWithSingleOutputField(Result result, Record record) {
            Iterator<byte[]> iter = extractor.extract(result).iterator();
            while (iter.hasNext()) {
                for (Object value : byteArrayMapper.map(iter.next())) {
                    if (!isAllowEmpty && isEmpty(value)) {
                        LOG.debug("the value of \"" + inputColumn + "\" is empty, this column will be ignored!");
                        continue;
                    }
                    record.put(outputFieldName, value);
                }
            }
        }

        private void extractWithMultipleOutputFieldNames(Result result, Record record) {
            Iterator<byte[]> iter = extractor.extract(result).iterator();
            for (int i = 0; i < outputFieldNames.size() && iter.hasNext(); i++) {
                byte[] input = iter.next();
                String outputField = outputFieldNames.get(i);
                // empty column name indicates omit this field on output
                if (outputField.length() > 0) {
                    for (Object value : byteArrayMapper.map(input)) {
                        if (!isAllowEmpty && isEmpty(value)) {
                            LOG.debug("the value of \"" + inputColumn + "\" is empty, this column will be ignored!");
                            continue;
                        }
                        record.put(outputField, value);
                    }
                }
            }
        }

        private void extractWithDynamicOutputFieldNames(Result result, Record record) {
            Iterator<byte[]> iter = extractor.extract(result).iterator();
            NavigableMap<byte[], byte[]> qualifiersToValues = result.getFamilyMap(columnFamily);
            if (qualifiersToValues != null) {
                for (byte[] matchingQualifier : qualifiersToValues.navigableKeySet().tailSet(qualifier)) {
                    if (Bytes.startsWith(matchingQualifier, qualifier)) {
                        byte[] tail = Bytes.tail(matchingQualifier, matchingQualifier.length - qualifier.length);
                        String outputField = outputFieldName + Bytes.toString(tail);
                        for (Object value : byteArrayMapper.map(iter.next())) {
                            if (!isAllowEmpty && isEmpty(value)) {
                                LOG.debug("the value of \"" + inputColumn + "\" is empty, this column will be ignored!");
                                continue;
                            }
                            record.put(outputField, value);
                        }
                    } else {
                        break;
                    }
                }
                assert !iter.hasNext();
            }
        }
    }
}
