/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.plugins.io;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.collections4.map.ListOrderedMap;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.nexial.commons.utils.TextUtils;
import org.nexial.commons.utils.TextUtils.CleanNumberStrategy;
import org.nexial.core.IntegrationConfigException;
import org.nexial.core.MemManager;
import org.nexial.core.utils.ConsoleUtils;

import com.univocity.parsers.common.record.RecordMetaData;
import com.univocity.parsers.csv.CsvParser;

import static java.lang.Double.MIN_VALUE;

class CsvExtendedComparison implements Serializable {
    private static final Map<String, ReportFormat> TYPES = new HashMap<>();

    static final String CSV_EXT_COMP_HEADER = "[csv >> compareExtended]: ";
    static final String PARSE_NUM_MSG = "resort to text comparison due to non-numeric value found: ";

    private String expectedContent;
    private List<String> expectedIdentityColumns;
    private CsvParser expectedParser;
    private List<String[]> expectedRecords;
    private List<String> expectedHeaders;
    private String actualContent;
    private List<String> actualIdentityColumns;
    private CsvParser actualParser;
    private List<String[]> actualRecords;
    private List<String> actualHeaders;
    private Map<String, String> fieldMapping;
    private List<String> ignoreFields;
    private List<String> numberFields;
    private List<String> caseInsensitiveFields;
    private List<String> autoTrimFields;
    private List<String> displayFields;
    private ReportFormat reportFormat;
    private String mismatchedField = "MISMATCHED FIELD";
    private String expectedField = "EXPECTED";
    private String actualField = "ACTUAL";
    private String identSeparator = "^";
    private String delimiter;
    private int maxColumns = -1;
    private int maxColumnWidth = -1;

    // support the comparison of field content as a list (ordered or unordered)
    private List<String> orderedListFields;
    private List<String> unorderedListFields;
    private String listDelim = ",";

    private File expectedFile;
    private File actualFile;

    public enum ReportFormat {
        CSV(".csv"),
        CSV_DOUBLE_QUOTES(".csv"),
        HTML(".html"),
        PLAIN(".txt");

        private String ext;

        ReportFormat(String ext) {
            this.ext = ext;
            TYPES.put(this.name(), this);
        }

        public String getExt() { return ext; }

        public static ReportFormat toReportFormat(String name) { return TYPES.get(name); }
    }

    public String getDelimiter() { return delimiter; }

    public void setDelimiter(String delimiter) { this.delimiter = delimiter; }

    public String getExpectedContent() { return expectedContent; }

    public void setExpectedContent(String expectedContent) { this.expectedContent = expectedContent; }

    public String getActualContent() { return actualContent; }

    public void setActualContent(String actualContent) { this.actualContent = actualContent; }

    public File getExpectedFile() { return expectedFile; }

    public void setExpectedFile(File expectedFile) { this.expectedFile = expectedFile; }

    public File getActualFile() { return actualFile; }

    public void setActualFile(File actualFile) { this.actualFile = actualFile; }

    public Map<String, String> getFieldMapping() { return fieldMapping; }

    public void setFieldMapping(Map<String, String> fieldMapping) { this.fieldMapping = fieldMapping; }

    public List<String> getIgnoreFields() { return ignoreFields; }

    public void setIgnoreFields(List<String> ignoreFields) { this.ignoreFields = ignoreFields; }

    public List<String> getNumberFields() { return numberFields; }

    public void setNumberFields(List<String> numberFields) { this.numberFields = numberFields; }

    public List<String> getCaseInsensitiveFields() { return caseInsensitiveFields; }

    public void setCaseInsensitiveFields(List<String> caseInsensitiveFields) {
        this.caseInsensitiveFields = caseInsensitiveFields;
    }

    public List<String> getAutoTrimFields() { return autoTrimFields; }

    public void setAutoTrimFields(List<String> autoTrimFields) { this.autoTrimFields = autoTrimFields; }

    public List<String> getExpectedIdentityColumns() { return expectedIdentityColumns; }

    public void setExpectedIdentityColumns(List<String> expectedIdentityColumns) {
        this.expectedIdentityColumns = expectedIdentityColumns;
    }

    public List<String> getActualIdentityColumns() { return actualIdentityColumns; }

    public void setActualIdentityColumns(List<String> actualIdentityColumns) {
        this.actualIdentityColumns = actualIdentityColumns;
    }

    public CsvParser getExpectedParser() { return expectedParser; }

    public void setExpectedParser(CsvParser expectedParser) { this.expectedParser = expectedParser; }

    public CsvParser getActualParser() { return actualParser; }

    public void setActualParser(CsvParser actualParser) { this.actualParser = actualParser; }

    public List<String> getDisplayFields() { return displayFields; }

    public void setDisplayFields(List<String> displayFields) { this.displayFields = displayFields; }

    public ReportFormat getReportFormat() { return reportFormat; }

    public void setReportFormat(ReportFormat reportFormat) { this.reportFormat = reportFormat; }

    public String getMismatchedField() { return mismatchedField; }

    public void setMismatchedField(String mismatchedField) {
        if (StringUtils.isNotBlank(mismatchedField)) { this.mismatchedField = mismatchedField; }
    }

    public void setMaxColumns(int maxColumns) { this.maxColumns = maxColumns; }

    public void setMaxColumnWidth(int maxColumnWidth) { this.maxColumnWidth = maxColumnWidth; }

    public String getExpectedField() { return expectedField; }

    public void setExpectedField(String expectedField) {
        if (StringUtils.isNotBlank(expectedField)) { this.expectedField = expectedField; }
    }

    public String getActualField() { return actualField; }

    public void setActualField(String actualField) {
        if (StringUtils.isNotBlank(actualField)) { this.actualField = actualField; }
    }

    public String getIdentSeparator() { return identSeparator; }

    public void setIdentSeparator(String identSeparator) { this.identSeparator = identSeparator; }

    public List<String> getOrderedListFields() { return orderedListFields; }

    public void setOrderedListFields(List<String> orderedListFields) { this.orderedListFields = orderedListFields; }

    public List<String> getUnorderedListFields() { return unorderedListFields; }

    public void setUnorderedListFields(List<String> unorderedListFields) {
        this.unorderedListFields = unorderedListFields;
    }

    public String getListDelim() { return listDelim; }

    public void setListDelim(String listDelim) { this.listDelim = listDelim; }

    public CsvComparisonResult compare() throws IntegrationConfigException, IOException {
        sanityChecks();

        // parse and sort
        parseExpected();
        MemManager.gc(this);
        MemManager.recordMemoryChanges("after parsing expected");

        parseActual();
        MemManager.gc(this);
        MemManager.recordMemoryChanges("after parsing actual");

        // check all the identity and mapping headers
        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "validate headers");
        validateHeaders();

        int expectedLineCount = expectedRecords.size();
        int actualLineCount = actualRecords.size();

        CsvComparisonResult result = new CsvComparisonResult();
        result.setExpectedHeaders(expectedHeaders);
        result.setActualHeaders(actualHeaders);
        result.setIdentityFields(expectedIdentityColumns);
        result.setDisplayFields(displayFields);
        result.setMismatchedField(mismatchedField);
        result.setExpectedField(expectedField);
        result.setActualField(actualField);
        result.setActualRowCount(actualLineCount);
        result.setExpectedRowCount(expectedLineCount);

        // loop through expected
        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "processing " + expectedLineCount + " rows in expected");
        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "processing " + actualLineCount + " rows in actual");

        int expectedCurrentLine = 0;
        int actualCurrentLine = 0;
        while (expectedCurrentLine < expectedLineCount && actualCurrentLine < actualLineCount) {
            String[] expectedRecord = expectedRecords.get(expectedCurrentLine);
            String[] actualRecord = actualRecords.get(actualCurrentLine);

            String expectedIdentity = expectedRecord[0];
            String actualIdentity = actualRecord[0];
            int identityCompared = expectedIdentity.compareTo(actualIdentity);

            // if identity matched
            if (identityCompared == 0) {
                // check all other mapped fields
                fieldMapping.forEach((expectedField, actualField) -> {
                    String expectedValue = expectedRecord[expectedHeaders.indexOf(expectedField) + 1];
                    String actualValue = actualRecord[actualHeaders.indexOf(actualField) + 1];

                    boolean compareAsText = true;

                    // is this numeric compare?
                    if (IterableUtils.contains(numberFields, expectedField)) {
                        double expected = toNum(expectedField, expectedValue);
                        double actual = toNum(actualField, actualValue);

                        // if both value are not parsed as number
                        if (expected == MIN_VALUE && actual == MIN_VALUE) {
                            compareAsText = true;
                        } else {
                            compareAsText = false;
                            if (expected != actual) {
                                result.addMismatched(expectedRecord, expectedField, expectedValue, actualValue);
                            }
                        }
                    }

                    // we are now dealing with text comparison
                    if (compareAsText) {
                        // is this auto-trim compare?
                        boolean autoTrim = IterableUtils.contains(autoTrimFields, expectedField);
                        if (autoTrim) {
                            expectedValue = StringUtils.trim(expectedValue);
                            actualValue = StringUtils.trim(actualValue);
                        }

                        // is this case-insensitive compare?
                        boolean insensitive = IterableUtils.contains(caseInsensitiveFields, expectedField);

                        // is this compressed list compare?
                        boolean asOrderedList = IterableUtils.contains(orderedListFields, expectedField);
                        boolean asUnorderedList = IterableUtils.contains(unorderedListFields, expectedField);

                        if (asOrderedList || asUnorderedList) {
                            List<String> expectedList =
                                TextUtils.toList(insensitive ? StringUtils.lowerCase(expectedValue) : expectedValue,
                                                 listDelim,
                                                 autoTrim);
                            List<String> actualList =
                                TextUtils.toList(insensitive ? StringUtils.lowerCase(actualValue) : actualValue,
                                                 listDelim, autoTrim);

                            if (asUnorderedList) {
                                expectedList.sort(Comparator.naturalOrder());
                                actualList.sort(Comparator.naturalOrder());
                            }

                            if (!CollectionUtils.isEqualCollection(expectedList, actualList)) {
                                result.addMismatched(expectedRecord, expectedField, expectedValue, actualValue);
                            }
                        } else {
                            // is this case-insensitive compare?
                            if (insensitive) {
                                if (!StringUtils.equalsIgnoreCase(expectedValue, actualValue)) {
                                    result.addMismatched(expectedRecord, expectedField, expectedValue, actualValue);
                                }
                            } else {
                                if (!StringUtils.equals(expectedValue, actualValue)) {
                                    result.addMismatched(expectedRecord, expectedField, expectedValue, actualValue);
                                }
                            }
                        }
                    }

                });

                // give a little feedback; let them know we are working on it
                if (expectedCurrentLine % 5000 == 0) {
                    MemManager.gc(this);
                    ConsoleUtils.log(CSV_EXT_COMP_HEADER + "processed line #" + expectedCurrentLine + "...");
                }

                expectedCurrentLine++;
                actualCurrentLine++;
                continue;
            }

            // if expected identity > actual identity
            if (identityCompared > 0) {
                result.addMissingExpected(actualRecord);
                actualCurrentLine++;
                continue;
            }

            // if expected identity < actual identity
            if (identityCompared < 0) {
                result.addMissingActual(expectedRecord);
                expectedCurrentLine++;
                continue;
            }
        }

        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "processed all lines");

        if (expectedCurrentLine < expectedLineCount) {
            for (int i = expectedCurrentLine; i < expectedLineCount; i++) {
                result.addMissingActual(expectedRecords.get(i));
            }
        }

        if (actualCurrentLine < actualLineCount) {
            for (int i = actualCurrentLine; i < actualLineCount; i++) {
                result.addMissingExpected(actualRecords.get(i));
            }
        }

        return result;
    }

    protected double toNum(String expectedField, String expectedValue) {
        double expected = MIN_VALUE;
        try {
            expected = NumberUtils.createDouble(TextUtils.cleanNumber(expectedValue,
                                                                      CleanNumberStrategy.CSV));
        } catch (IllegalArgumentException e) {
            ConsoleUtils.error("Field [" + expectedField + "]: " + PARSE_NUM_MSG + expectedValue);
        }
        return expected;
    }

    private void validateHeaders() throws IntegrationConfigException {
        // check mapping
        for (String field : fieldMapping.keySet()) {
            if (!expectedHeaders.contains(field)) {
                throw new IntegrationConfigException("Invalid field name found for expected: " + field);
            }
        }

        for (String field : fieldMapping.values()) {
            if (!actualHeaders.contains(field)) {
                throw new IntegrationConfigException("Invalid field name found for actual: " + field);
            }
        }
    }

    private void parseExpected() throws IOException {
        expectedHeaders = new ArrayList<>();
        expectedRecords = new ArrayList<>();
        if (expectedFile != null) {
            expectedRecords = parseContent(expectedParser, expectedFile, expectedHeaders, expectedIdentityColumns);
        } else {
            expectedRecords = parseContent(expectedParser, expectedContent, expectedHeaders, expectedIdentityColumns);
        }

        if (MapUtils.isEmpty(fieldMapping)) {
            fieldMapping = new ListOrderedMap<>();
            expectedHeaders.forEach(header -> fieldMapping.put(header, header));
        }

        if (CollectionUtils.isNotEmpty(ignoreFields)) { ignoreFields.forEach(ignore -> fieldMapping.remove(ignore)); }
    }

    private void parseActual() throws IOException {
        actualHeaders = new ArrayList<>();
        actualRecords = new ArrayList<>();
        if (actualFile != null) {
            actualRecords = parseContent(actualParser, actualFile, actualHeaders, actualIdentityColumns);
        } else {
            actualRecords = parseContent(actualParser, actualContent, actualHeaders, actualIdentityColumns);
        }
    }

    private List<String[]> parseContent(CsvParser parser,
                                        String content,
                                        List<String> fileHeaders,
                                        List<String> identityColumns) throws IOException {

        StringReader reader = new StringReader(content);
        List<String[]> records = parser.parseAll(reader);
        reader.close();
        if (CollectionUtils.isEmpty(records)) { throw new IOException("No record parsed from content"); }

        postParsing(parser, records, fileHeaders, identityColumns);

        parser.stopParsing();
        parser = null;
        return records;
    }

    private List<String[]> parseContent(CsvParser parser,
                                        File file,
                                        List<String> fileHeaders,
                                        List<String> identityColumns) throws IOException {

        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "parsing file " + file);
        List<String[]> records = parser.parseAll(file);
        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "parsing file " + file + " - DONE");

        if (CollectionUtils.isEmpty(records)) { throw new IOException("No record parsed from content"); }

        postParsing(parser, records, fileHeaders, identityColumns);
        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "post-parsing - DONE");

        parser.stopParsing();
        parser = null;
        return records;
    }

    private void postParsing(CsvParser parser,
                             List<String[]> records,
                             List<String> fileHeaders,
                             List<String> identityColumns) throws IOException {
        // check file header
        RecordMetaData recordMetadata = parser.getRecordMetadata();
        if (recordMetadata != null) { fileHeaders.addAll(Arrays.asList(recordMetadata.headers())); }
        if (CollectionUtils.isEmpty(fileHeaders)) {
            throw new IOException("Unable to derive column headers from content");
        }

        ConsoleUtils.log(CSV_EXT_COMP_HEADER + "file header columns resolved to " + fileHeaders);

        // check identity fields
        for (String identColumn : identityColumns) {
            if (!fileHeaders.contains(identColumn)) {
                throw new IOException("Expected identity column not found: " + identColumn);
            }
        }

        for (int i = 0; i < records.size(); i++) {
            String[] record = records.get(i);
            String identity = identityColumns.stream()
                                             .map(column -> {
                                                 if (recordMetadata != null) {
                                                     if (!recordMetadata.containsColumn(column)) { return ""; }
                                                     int index = recordMetadata.indexOf(column);
                                                     return ArrayUtils.getLength(record) > index ? record[index] : "";
                                                 } else {
                                                     return "";
                                                 }
                                             })
                                             .collect(Collectors.joining(identSeparator));
            records.set(i, ArrayUtils.insert(0, record, identity));
        }

        // position 0 is the identity value
        records.sort(Comparator.comparing(row -> row[0]));
    }

    private void sanityChecks() throws IntegrationConfigException {
        if (StringUtils.isBlank(expectedContent) && expectedFile == null) {
            throw new IntegrationConfigException("No content for expected");
        }

        if (StringUtils.isBlank(actualContent) && actualFile == null) {
            throw new IntegrationConfigException("No content for actual");
        }

        if (MapUtils.isEmpty(fieldMapping)) {
            ConsoleUtils.log(CSV_EXT_COMP_HEADER + "No field mapping found; ASSUME SAME COLUMNS FOR EXPECTED/ACTUAL");
        }

        if (CollectionUtils.isEmpty(expectedIdentityColumns)) {
            throw new IntegrationConfigException("No identity column(s) specified for expected");
        }

        if (CollectionUtils.isEmpty(actualIdentityColumns)) {
            throw new IntegrationConfigException("No identity column(s) specified for actual");
        }

        if (CollectionUtils.isEmpty(displayFields)) {
            throw new IntegrationConfigException("No display column(s) specified");
        }

        if (expectedParser == null) {
            expectedParser = new CsvParserBuilder().setDelim(delimiter)
                                                   .setHasHeader(true)
                                                   .setMaxColumns(maxColumns)
                                                   .setMaxColumnWidth(maxColumnWidth)
                                                   .setQuote("\"")
                                                   .build();
        }

        if (actualParser == null) {
            actualParser = new CsvParserBuilder().setDelim(delimiter)
                                                 .setHasHeader(true)
                                                 .setMaxColumns(maxColumns)
                                                 .setMaxColumnWidth(maxColumnWidth)
                                                 .setQuote("\"")
                                                 .build();
        }

        if (reportFormat == null) { reportFormat = ReportFormat.CSV; }
    }
}
