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
import java.lang.reflect.Array;
import java.util.*;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StrTokenizer;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;

import org.nexial.commons.utils.TextUtils;
import org.nexial.core.excel.Excel;
import org.nexial.core.excel.Excel.Worksheet;
import org.nexial.core.excel.ExcelAddress;
import org.nexial.core.model.StepResult;
import org.nexial.core.plugins.base.BaseCommand;

import static org.nexial.core.utils.CheckUtils.*;
import static org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK;

public class ExcelCommand extends BaseCommand {
    @Override
    public String getTarget() { return "excel"; }

    public StepResult clear(String file, String worksheet, String range) throws IOException {
        Excel excel = deriveExcel(file);

        requires(StringUtils.isNotBlank(range) && StringUtils.contains(range, ":"), "invalid Excel range", range);
        ExcelAddress addr = new ExcelAddress(range);

        excel.requireWorksheet(worksheet, true).clearCells(addr);
        excel.save();

        return StepResult.success("Data at " + range + " cleared for " + file + "#" + worksheet);
    }

    public StepResult saveRange(String var, String file, String worksheet, String range) throws IOException {
        requiresValidVariableName(var);

        Excel excel = deriveExcel(file);
        Worksheet sheet = excel.worksheet(worksheet);
        requires(sheet != null && sheet.getSheet() != null, "invalid worksheet", worksheet);

        requires(StringUtils.isNotBlank(range), "invalid cell range", range);
        ExcelAddress addr = new ExcelAddress(range);
        List<List<XSSFCell>> rows = sheet.cells(addr);
        Map<String, String> data = new LinkedHashMap<>();
        for (List<XSSFCell> row : rows) {
            for (XSSFCell cell : row) { data.put(cell.getReference(), Excel.getCellValue(cell)); }
        }

        if (MapUtils.isEmpty(data)) {
            context.removeData(var);
            return StepResult.success("No data found");
        }

        context.setData(var, data);
        return StepResult.success(data.size() + " cells read and stored to '" + var + "'");
    }

    public StepResult saveData(String var, String file, String worksheet, String range) throws IOException {
        requiresValidVariableName(var);

        Excel excel = deriveExcel(file);
        Worksheet sheet = excel.worksheet(worksheet);
        requires(sheet != null && sheet.getSheet() != null, "invalid worksheet", worksheet);

        requires(StringUtils.isNotBlank(range), "invalid cell range", range);
        ExcelAddress addr = new ExcelAddress(range);
        List<List<XSSFCell>> rows = sheet.cells(addr);
        List<List<String>> data = new ArrayList<>();
        for (List<XSSFCell> row : rows) {
            List<String> rowData = new ArrayList<>();
            for (XSSFCell cell : row) { rowData.add(Excel.getCellValue(cell)); }
            data.add(rowData);
        }

        if (CollectionUtils.isEmpty(data)) {
            context.removeData(var);
            return StepResult.success("No data found");
        }

        context.setData(var, data.size() == 1 ? data.get(0) : data);
        return StepResult.success(data.size() + " cells read and stored to '" + var + "'");
    }

    public StepResult writeVar(String var, String file, String worksheet, String startCell) throws IOException {
        requiresValidVariableName(var);
        requiresNotBlank(startCell, "invalid cell address", startCell);

        Excel excel = deriveExcel(file);
        XSSFSheet sheet = excel.requireWorksheet(worksheet, true).getSheet();
        addData(sheet, new ExcelAddress(startCell), to2dStringList(var));
        excel.save();

        return StepResult.success("Data saved to " + file + "#" + worksheet);
    }

    public StepResult write(String file, String worksheet, String startCell, String data) throws IOException {
        requiresNotBlank(startCell, "invalid cell address", startCell);
        requiresNotNull(data, "Invalid data to write", data);

        String delim = context.getTextDelim();
        List<List<String>> data2d = TextUtils.to2dList(data, "\n", delim);
        // need to remove the `\,` with just `,` before writing to Excel
        for (List<String> row : data2d) {
            for (int i = 0; i < row.size(); i++) {
                row.set(i, StringUtils.replace(row.get(i), "\\" + delim, delim));
            }
        }

        Excel excel = deriveExcel(file);
        XSSFSheet sheet = excel.requireWorksheet(worksheet, true).getSheet();
        addData(sheet, new ExcelAddress(startCell), data2d);
        excel.save();

        return StepResult.success("Data saved to " + file + "#" + worksheet);
    }

    public StepResult writeAcross(String file, String worksheet, String startCell, String array) throws IOException {
        requiresNotBlank(startCell, "invalid cell address", startCell);
        requiresNotBlank(worksheet, "invalid worksheet name", worksheet);
        requiresNotBlank(array, "Invalid array to write", array);

        deriveExcel(file).requireWorksheet(worksheet, true)
                         .writeAcross(new ExcelAddress(startCell),
                                      TextUtils.toList(array, context.getTextDelim(), false));

        return StepResult.success("Data saved to " + file + "#" + worksheet);
    }

    public StepResult writeDown(String file, String worksheet, String startCell, String array) throws IOException {
        requiresNotBlank(startCell, "invalid cell address", startCell);
        requiresNotBlank(worksheet, "invalid worksheet name", worksheet);
        requiresNotBlank(array, "Invalid array to write", array);

        deriveExcel(file).requireWorksheet(worksheet, true)
                         .writeDown(new ExcelAddress(startCell),
                                    TextUtils.toList(array, context.getTextDelim(), false));

        return StepResult.success("Data saved to " + file + "#" + worksheet);
    }

    protected Excel deriveExcel(String file) throws IOException { return new Excel(deriveReadableFile(file)); }

    protected File deriveReadableFile(String file) {
        // sanity check
        requires(StringUtils.isNotBlank(file), "invalid file", file);

        File excelFile = new File(file);
        requires(excelFile.isFile() && excelFile.canRead() && excelFile.length() > 100, "unreadable file", file);
        return excelFile;
    }

    protected void addData(XSSFSheet sheet, ExcelAddress addr, List<List<String>> dataRows) {
        int startRowIndex = addr.getRowStartIndex();
        int endRowIndex = startRowIndex + dataRows.size();
        int startColIndex = addr.getColumnStartIndex();

        for (int i = startRowIndex; i < endRowIndex; i++) {
            XSSFRow row = sheet.getRow(i);
            if (row == null) { row = sheet.createRow(i); }

            List<String> data = dataRows.get(i - startRowIndex);

            int endColIndex = startColIndex + data.size();
            for (int j = startColIndex; j < endColIndex; j++) {
                XSSFCell cell = row.getCell(j, CREATE_NULL_AS_BLANK);
                cell.setCellValue(data.get(j - startColIndex));
            }
        }
    }

    protected List<List<String>> to2dStringList(String var) {
        List<List<String>> dataRows = new ArrayList<>();

        Object dataObject = context.getObjectData(var);
        Class<?> clazz = dataObject.getClass();

        if (clazz.isArray()) {
            int arraySize = ArrayUtils.getLength(dataObject);
            for (int i = 0; i < arraySize; i++) { dataRows.add(toStringList(Array.get(dataObject, i))); }
            return dataRows;
        }

        if (List.class.isAssignableFrom(clazz)) {
            List list = (List) dataObject;
            if (CollectionUtils.isEmpty(list)) { return dataRows; }

            Class listType = list.get(0).getClass();
            if (Map.class.isAssignableFrom(listType)) {
                // special treatment for List of Map; this is probably SQL result via db plugin
                Map firstItem = (Map) list.get(0);
                String[] headers = (String[]) firstItem.keySet().toArray(new String[firstItem.size()]);
                dataRows.add(Arrays.asList(headers));

                for (Object item : list) {
                    Map map = (Map) item;
                    Object[] values = new Object[map.size()];
                    for (int i = 0; i < headers.length; i++) {
                        String header = headers[i];
                        values[i] = map.get(header);
                    }
                    dataRows.add(toStringList(values));
                }

                return dataRows;
            }

            for (Object item : list) { dataRows.add(toStringList(item)); }
            return dataRows;
        }

        if (Iterable.class.isAssignableFrom(clazz)) {
            for (Object item : (Iterable) dataObject) { dataRows.add(toStringList(item)); }
            return dataRows;
        }

        if (dataObject instanceof String) {
            return TextUtils.to2dList(((String) dataObject), "\n", context.getTextDelim());
        }

        dataRows.add(Collections.singletonList(Objects.toString(dataObject)));
        return dataRows;
    }

    protected List<List<String>> stringTo2dList(String data) {
        List<List<String>> dataRows = new ArrayList<>();
        if (StringUtils.isEmpty(data)) { return dataRows; }

        List<String> list = TextUtils.toList(StringUtils.remove(data, '\r'), "\n", false);

        String delim = context.getTextDelim();

        list.forEach(item -> dataRows.add(new StrTokenizer(item, delim).setIgnoreEmptyTokens(false).getTokenList()));
        return dataRows;
    }

    protected List<String> toStringList(Object data) {
        List<String> list = new ArrayList<>();
        Class<?> clazz = data.getClass();

        if (clazz.isArray()) {
            int arraySize = ArrayUtils.getLength(data);
            for (int i = 0; i < arraySize; i++) { list.add(Objects.toString(Array.get(data, i))); }
            return list;
        }

        if (Iterable.class.isAssignableFrom(clazz)) {
            for (Object item : (Iterable) data) { list.add(Objects.toString(item)); }
            return list;
        }

        if (data instanceof String) {
            list.addAll(new StrTokenizer((String) data, context.getTextDelim()).setIgnoreEmptyTokens(false)
                                                                               .getTokenList());
            return list;
        }

        list.add(Objects.toString(data));
        return list;
    }

}
